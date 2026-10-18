package com.voicecaption.asr

import com.voicecaption.core.SpeechSegment

/**
 * Uses [fast] for everything except long, finished sentences in [longLanguages], which go to [long].
 *
 * Built for Korean captions in English: SenseVoice hears short lines quickly and literally, while
 * Whisper small (translating straight to English) handled long, complex sentences better in field
 * tests but invents plausible text for short noisy lines and is slower. Live partials always use
 * [fast] so captions stay quick. [outputLanguage] follows whichever recognizer handled the last call.
 */
class HybridRecognizer(
    private val fast: SpeechRecognizer,
    private val long: SpeechRecognizer,
    private val minLongMs: Long = 4_000,
    private val longLanguages: Set<String> = setOf("ko"),
) : SpeechRecognizer {
    private var lastOutputLanguage: String? = null

    override val outputLanguage: String? get() = lastOutputLanguage

    override suspend fun transcribe(segment: SpeechSegment, language: String): String = recognize(segment, language).text

    override suspend fun recognize(segment: SpeechSegment, language: String): Transcript {
        val primary = language.trim().lowercase().split('-', '_').first()
        val useLong = !segment.partial && segment.endMs - segment.startMs >= minLongMs && primary in longLanguages
        val chosen = if (useLong) long else fast
        val t = chosen.recognize(segment, language)
        lastOutputLanguage = chosen.outputLanguage
        return t
    }

    override fun close() {
        fast.close()
        long.close()
    }
}
