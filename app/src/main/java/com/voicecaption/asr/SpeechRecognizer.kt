package com.voicecaption.asr

import com.voicecaption.core.SpeechSegment

/**
 * Recognized [text] and, when the recognizer knows it, the [language] that was spoken (a lower-case
 * ISO 639-1 code such as "ja"). Null means unknown, never "auto".
 */
data class Transcript(val text: String, val language: String? = null)

/** Turns one VAD-cut [SpeechSegment] into text. */
interface SpeechRecognizer {
    /** [language] is an ISO 639-1 code ("en"); blank or "auto" lets the model detect it. */
    suspend fun transcribe(segment: SpeechSegment, language: String): String

    /**
     * Like [transcribe] but also reports the spoken language when known. Recognizers that detect
     * the language override this; the default has no language information.
     */
    suspend fun recognize(segment: SpeechSegment, language: String): Transcript =
        Transcript(transcribe(segment, language))

    /**
     * Non-null when the recognizer already outputs text in this language whatever the spoken
     * language is (e.g. Whisper's "translate" task gives English); the pipeline then skips its
     * own translation step.
     */
    val outputLanguage: String? get() = null

    /** Releases native resources. Idempotent; [transcribe] afterwards throws [IllegalStateException]. */
    fun close()
}

/** "<|ja|>" or " JA " -> "ja"; blank or "auto" -> null. */
internal fun normalizeDetectedLanguage(raw: String?): String? {
    val cleaned = raw.orEmpty().trim().removePrefix("<|").removeSuffix("|>").trim().lowercase()
    return cleaned.takeIf { it.isNotEmpty() && it != "auto" }
}
