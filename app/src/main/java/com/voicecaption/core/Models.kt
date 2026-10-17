package com.voicecaption.core

/** A fixed-size window of mono 16 kHz audio in [-1, 1]. */
class PcmFrame(val samples: FloatArray, val timestampMs: Long)

/**
 * A contiguous stretch of speech cut out by the VAD segmenter. [utterance] numbers the segments the
 * segmenter starts; a [partial] is a snapshot of a segment still being spoken and is later
 * superseded by the final segment with the same [utterance].
 */
class SpeechSegment(
    val samples: FloatArray,
    val startMs: Long,
    val endMs: Long,
    val utterance: Long = 0,
    val partial: Boolean = false,
)

data class Caption(
    val id: Long,
    val sourceText: String,
    val translatedText: String?,
    val isFinal: Boolean,
    val updatedAtMs: Long,
)

/** BASE/SMALL are Whisper sizes; SENSEVOICE is the experimental SenseVoice-Small recognizer. */
enum class ModelSize { BASE, SMALL, SENSEVOICE }

data class CaptionSettings(
    val sourceLang: String = "en",
    val targetLang: String = "en",
    val fontSizeSp: Int = 18,
    val opacity: Float = 0.8f,
    val bilingual: Boolean = false,
    val modelSize: ModelSize = ModelSize.BASE,
    /**
     * Pick the speech model from the spoken language ([activeModel]): SenseVoice for Japanese,
     * Chinese, Korean and Cantonese, otherwise the Whisper size in [modelSize]. Off = always
     * use [modelSize].
     */
    val autoModel: Boolean = true,
    /** Caption text colour (opaque RGB). */
    val textColor: Int = ColorMath.WHITE,
    /** Caption plate colour (opaque RGB); its transparency is [opacity]. */
    val plateColor: Int = ColorMath.BLACK,
) {
    /** The model that will really run (and be downloaded) for these settings. */
    val activeModel: ModelSize get() = ModelChoice.resolve(autoModel, modelSize, sourceLang)
}

/** Which speech model to use for a language. Pure. */
object ModelChoice {
    private val SENSEVOICE_FIRST = setOf("ja", "zh", "ko", "yue")

    fun resolve(auto: Boolean, preferred: ModelSize, sourceLang: String): ModelSize {
        if (!auto) return preferred
        val primary = sourceLang.trim().lowercase().split('-', '_').first()
        return when {
            primary in SENSEVOICE_FIRST -> ModelSize.SENSEVOICE
            preferred == ModelSize.SENSEVOICE -> ModelSize.BASE // Whisper covers every other language
            else -> preferred
        }
    }
}
