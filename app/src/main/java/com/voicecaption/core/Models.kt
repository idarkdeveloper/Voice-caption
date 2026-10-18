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
/** TURBO (Whisper large-v3-turbo, ~1 GB) is picked automatically for Filipino; it only transcribes. */
enum class ModelSize { BASE, SMALL, SENSEVOICE, TURBO }

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
    /** Debug builds only: show the speed and recognition stats line inside the caption box. */
    val showDiagnostics: Boolean = false,
    /** Also record the screen, with the captions on it, to Movies/TransCap while captions run. */
    val recordScreen: Boolean = false,
    /** Where the user dragged the translation box, in dp from the bottom edge; null = default spot. */
    val translationOffsetDp: Int? = null,
    /** Where the user dragged the original-language box, in dp from the bottom edge; null = stacked above the translation. */
    val originalOffsetDp: Int? = null,
) {
    /** The model that will really run (and be downloaded) for these settings. */
    val activeModel: ModelSize get() = ModelChoice.resolve(autoModel, modelSize, sourceLang)
}

/** Which speech model to use for a language. Pure. */
object ModelChoice {
    private val SENSEVOICE_FIRST = setOf("ja", "zh", "ko", "yue")

    fun resolve(auto: Boolean, preferred: ModelSize, sourceLang: String): ModelSize {
        val primary = sourceLang.trim().lowercase().split('-', '_').first()
        // SenseVoice does not know Filipino, so an explicit SenseVoice choice must not stay on it.
        if (!auto) return if (primary == "tl" && preferred == ModelSize.SENSEVOICE) ModelSize.TURBO else preferred
        return when {
            primary == "auto" -> if (preferred == ModelSize.SENSEVOICE) ModelSize.BASE else preferred // Whisper listens first
            primary in SENSEVOICE_FIRST -> ModelSize.SENSEVOICE
            primary == "tl" -> ModelSize.TURBO // Whisper small mishears Filipino; turbo + ML Kit measured far better
            preferred == ModelSize.SENSEVOICE -> ModelSize.BASE // Whisper covers every other language
            else -> preferred
        }
    }
}
