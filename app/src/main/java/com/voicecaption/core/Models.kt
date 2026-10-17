package com.voicecaption.core

/** A fixed-size window of mono 16 kHz audio in [-1, 1]. */
class PcmFrame(val samples: FloatArray, val timestampMs: Long)

/** A contiguous stretch of speech cut out by the VAD segmenter. */
class SpeechSegment(val samples: FloatArray, val startMs: Long, val endMs: Long)

data class Caption(
    val id: Long,
    val sourceText: String,
    val translatedText: String?,
    val isFinal: Boolean,
    val updatedAtMs: Long,
)

enum class ModelSize { BASE, SMALL }

data class CaptionSettings(
    val sourceLang: String = "en",
    val targetLang: String = "en",
    val fontSizeSp: Int = 18,
    val opacity: Float = 0.8f,
    val bilingual: Boolean = false,
    val modelSize: ModelSize = ModelSize.BASE,
)
