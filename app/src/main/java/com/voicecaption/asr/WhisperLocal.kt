package com.voicecaption.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.voicecaption.core.ModelSize
import com.voicecaption.core.SpeechSegment
import com.voicecaption.core.wrapNativeLoad
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Whisper via sherpa-onnx. [modelDir] is the `<size>/` directory installed by
 * [ModelManager] (encoder.int8.onnx, decoder.int8.onnx, tokens.txt); [size] is kept for logging
 * and future per-size tuning.
 *
 * The native recognizer is created lazily on the first [transcribe], on [Dispatchers.Default], so
 * constructing a WhisperLocal is free and the (slow, memory-heavy) model load never blocks the
 * caller's thread. A missing model file or a native-load failure surfaces as an exception from
 * that first [transcribe] ([java.io.FileNotFoundException] for missing files,
 * [IllegalStateException] for a native-load failure, `UnsatisfiedLinkError` included); a failed
 * load is retried on the next call.
 *
 * Calls are serialized (one native decode at a time); the recognizer is not thread-safe.
 */
class WhisperLocal(
    private val modelDir: File,
    val size: ModelSize,
    /** True: Whisper's "translate" task, which outputs English whatever language is spoken. */
    private val translateToEnglish: Boolean = false,
) : SpeechRecognizer {
    override val outputLanguage: String? get() = if (translateToEnglish) "en" else null

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var currentLanguage: String? = null
    private var closed = false

    override suspend fun transcribe(segment: SpeechSegment, language: String): String = recognize(segment, language).text

    override suspend fun recognize(segment: SpeechSegment, language: String): Transcript {
        check(!synchronized(lock) { closed }) { "WhisperLocal is closed" }
        if (segment.samples.isEmpty()) return Transcript("")
        // Filipino speech is full of English words; forcing Tagalog turns those into nonsense, so Whisper
        // detects each sentence itself (see AutoRecognizer for the same rule on "auto"). Music and
        // noise then get "detected" as Malay, Spanish and the like and come back as invented text, so
        // anything detected outside Filipino/English is decoded again as Filipino.
        val requested = normalizeWhisperLanguage(language)
        val lang = if (requested == "tl") "" else requested
        return withContext(Dispatchers.Default) {
            synchronized(lock) {
                check(!closed) { "WhisperLocal is closed" }
                var t = decode(segment, lang)
                if (requested == "tl" && t.language != null && t.language !in FILIPINO_OR_ENGLISH) t = decode(segment, "tl")
                if (RecognizedText.isHallucination(t.text)) Transcript("", t.language, t.event) else t
            }
        }
    }

    /** One native decode. Must be called holding [lock]. */
    private fun decode(segment: SpeechSegment, lang: String): Transcript {
        val rec = recognizerFor(lang)
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(segment.samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            return Transcript(result.text.trim(), normalizeDetectedLanguage(result.lang))
        } finally {
            stream.release()
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

    /** Must be called holding [lock]. Builds the recognizer once, then only swaps the language. */
    private fun recognizerFor(language: String): OfflineRecognizer {
        val existing = recognizer
        if (existing == null) {
            val config = buildConfig(language)
            val created = wrapNativeLoad("Whisper recognizer") { OfflineRecognizer(config = config) }
            recognizer = created
            currentLanguage = language
            return created
        }
        if (language != currentLanguage) {
            val config = buildConfig(language)
            wrapNativeLoad("Whisper recognizer") { existing.setConfig(config) }
            currentLanguage = language
        }
        return existing
    }

    private fun buildConfig(language: String): OfflineRecognizerConfig {
        val files = WhisperFiles.resolve(modelDir)
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = files.encoder,
                    decoder = files.decoder,
                    language = language,
                    task = if (translateToEnglish) "translate" else "transcribe",
                ),
                tokens = files.tokens,
                numThreads = whisperThreads(Runtime.getRuntime().availableProcessors()),
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        )
    }

    private companion object {
        val FILIPINO_OR_ENGLISH = setOf("tl", "en")
        const val SAMPLE_RATE = 16_000
    }
}
