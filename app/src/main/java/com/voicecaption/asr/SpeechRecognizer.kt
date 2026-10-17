package com.voicecaption.asr

import com.voicecaption.core.SpeechSegment

/** Turns one VAD-cut [SpeechSegment] into text. */
interface SpeechRecognizer {
    /** [language] is an ISO 639-1 code ("en"); blank or "auto" lets the model detect it. */
    suspend fun transcribe(segment: SpeechSegment, language: String): String

    /** Releases native resources. Idempotent; [transcribe] afterwards throws [IllegalStateException]. */
    fun close()
}
