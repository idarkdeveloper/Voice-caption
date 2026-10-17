package com.voicecaption.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.voicecaption.core.ModelSize
import com.voicecaption.core.SpeechSegment
import com.voicecaption.core.wrapNativeLoad
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Languages SenseVoice-Small knows; anything else is passed as "auto" so the model detects it. */
internal val SENSEVOICE_LANGUAGES: Set<String> = setOf("zh", "en", "ja", "ko", "yue")

/** Maps an app language tag to SenseVoice's language code, or "auto" when it is not supported. */
internal fun normalizeSenseVoiceLanguage(language: String): String {
    val primary = language.trim().lowercase().split('-', '_').first()
    return if (primary in SENSEVOICE_LANGUAGES) primary else "auto"
}

/**
 * Experimental on-device recognizer: SenseVoice-Small via sherpa-onnx (zh/en/ja/ko/yue). It
 * transcribes in the spoken language (no direct translation), so the pipeline's ML Kit step
 * translates the text. [modelDir] holds `model.int8.onnx` and `tokens.txt`.
 *
 * Same threading contract as [WhisperLocal]: the native recognizer is created lazily on the first
 * [transcribe] on [Dispatchers.Default], calls are serialized, and a failed load is retried.
 */
class SenseVoiceLocal(private val modelDir: File) : SpeechRecognizer {
    val size: ModelSize = ModelSize.SENSEVOICE

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var currentLanguage: String? = null
    private var closed = false

    override suspend fun transcribe(segment: SpeechSegment, language: String): String = recognize(segment, language).text

    override suspend fun recognize(segment: SpeechSegment, language: String): Transcript {
        check(!synchronized(lock) { closed }) { "SenseVoiceLocal is closed" }
        if (segment.samples.isEmpty()) return Transcript("")
        val lang = normalizeSenseVoiceLanguage(language)
        return withContext(Dispatchers.Default) {
            synchronized(lock) {
                check(!closed) { "SenseVoiceLocal is closed" }
                val rec = recognizerFor(lang)
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    Transcript(result.text.trim(), normalizeDetectedLanguage(result.lang), normalizeEvent(result.event))
                } finally {
                    stream.release()
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            recognizer?.release()
            recognizer = null
        }
    }

    /** Must be called holding [lock]. */
    private fun recognizerFor(language: String): OfflineRecognizer {
        val existing = recognizer
        if (existing == null) {
            val created = wrapNativeLoad("SenseVoice recognizer") { OfflineRecognizer(config = buildConfig(language)) }
            recognizer = created
            currentLanguage = language
            return created
        }
        if (language != currentLanguage) {
            wrapNativeLoad("SenseVoice recognizer") { existing.setConfig(buildConfig(language)) }
            currentLanguage = language
        }
        return existing
    }

    private fun buildConfig(language: String): OfflineRecognizerConfig {
        val model = File(modelDir, MODEL_FILE).also { if (!it.isFile) throw FileNotFoundException("SenseVoice model file missing: ${it.path}") }
        val tokens = File(modelDir, TOKENS_FILE).also { if (!it.isFile) throw FileNotFoundException("SenseVoice tokens file missing: ${it.path}") }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.path,
                    language = language,
                    useInverseTextNormalization = true,
                ),
                tokens = tokens.path,
                numThreads = whisperThreads(Runtime.getRuntime().availableProcessors()),
            ),
            decodingMethod = "greedy_search",
        )
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
    }
}
