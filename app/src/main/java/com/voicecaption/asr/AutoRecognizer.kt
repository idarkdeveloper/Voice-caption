package com.voicecaption.asr

import com.voicecaption.core.SpeechSegment

/** The language the user can pick for "listen and find out"; matches the settings value. */
const val AUTO_LANGUAGE = "auto"

/** True for blank or "auto" (any case). */
fun isAutoLanguage(code: String): Boolean = code.isBlank() || code.trim().equals(AUTO_LANGUAGE, ignoreCase = true)

/**
 * Recognizer for a film whose language is not known. It listens to the first few sentences with
 * [whisper] (which names the language it hears, for any of its languages), then keeps using the
 * language it settled on:
 * - Chinese, Japanese, Korean and Cantonese go to [senseVoice] when it is installed AND the caption
 *   language can be reached from it ([translatorReady]); SenseVoice does its own detection among
 *   those languages, so Cantonese is told apart from Mandarin.
 * - Everything else stays on [whisper], now told the language.
 * Only whole sentences vote (not live partials, which repeat the same sentence), and only ones with
 * at least [minVoteMs] of audio and real words (not music or sound tags), so those do not decide.
 * Until a language is locked, captions are decoded with Whisper's own detection; [Transcript.language]
 * is null then, so the pipeline shows the original text without translating it.
 *
 * A non-"auto" request is passed straight to [whisper] with that language.
 */
class AutoRecognizer(
    private val whisper: SpeechRecognizer,
    private val senseVoice: SpeechRecognizer?,
    private val translatorReady: suspend (source: String, target: String) -> Boolean,
    private val targetLang: () -> String,
    private val detector: LanguageDetector = LanguageDetector(),
    private val minVoteMs: Long = 1_000,
) : SpeechRecognizer {
    private enum class Route { WHISPER, SENSEVOICE }

    private var route: Route? = null
    private var currentOutputLanguage: String? = null

    /** Language the session settled on, or null while still listening. */
    val lockedLanguage: String? get() = detector.locked

    /** Name of the engine in use, for diagnostics. */
    val engine: String
        get() = when (route) {
            Route.SENSEVOICE -> "SENSEVOICE"
            Route.WHISPER -> "WHISPER"
            null -> "WHISPER (listening)"
        }

    override val outputLanguage: String? get() = currentOutputLanguage

    override suspend fun transcribe(segment: SpeechSegment, language: String): String = recognize(segment, language).text

    override suspend fun recognize(segment: SpeechSegment, language: String): Transcript {
        if (!isAutoLanguage(language)) {
            val t = whisper.recognize(segment, language)
            currentOutputLanguage = whisper.outputLanguage
            return t
        }
        val locked = detector.locked ?: return listen(segment)
        return speak(segment, locked)
    }

    private suspend fun listen(segment: SpeechSegment): Transcript {
        val t = whisper.recognize(segment, "")
        currentOutputLanguage = whisper.outputLanguage
        val longEnough = segment.endMs - segment.startMs >= minVoteMs
        // Only real words vote: Whisper names a language for music and sound tags too ("[Music]" came
        // back as Norwegian in a test), which must never decide the language of the film.
        val locked = if (!segment.partial && longEnough && RecognizedText.hasWords(t.text)) detector.vote(t.language) else detector.locked
        return Transcript(t.text, locked, t.event)
    }

    private suspend fun speak(segment: SpeechSegment, locked: String): Transcript {
        val chosen = route ?: decideRoute(locked).also { route = it }
        if (chosen == Route.SENSEVOICE && senseVoice != null) {
            // Japanese and Korean are told to SenseVoice: left to detect each short or noisy line itself
            // it sometimes decoded Korean as Japanese. Chinese stays on auto so it can tell Mandarin from Cantonese.
            val told = locked in TOLD_LANGUAGES
            val t = senseVoice.recognize(segment, if (told) locked else AUTO_LANGUAGE)
            currentOutputLanguage = null
            // SenseVoice names Cantonese "yue"; ML Kit knows it as Chinese.
            return Transcript(t.text, translationCode(if (told) locked else (t.language ?: locked)), t.event)
        }
        val t = whisper.recognize(segment, locked)
        currentOutputLanguage = whisper.outputLanguage
        return Transcript(t.text, locked, t.event)
    }

    private suspend fun decideRoute(locked: String): Route {
        if (senseVoice == null || locked !in SENSEVOICE_LANGUAGES) return Route.WHISPER
        return if (translatorReady(translationCode(locked), targetLang())) Route.SENSEVOICE else Route.WHISPER
    }

    override fun close() {
        whisper.close()
        senseVoice?.close()
    }

    private companion object {
        val SENSEVOICE_LANGUAGES = setOf("ja", "zh", "ko", "yue")
        val TOLD_LANGUAGES = setOf("ja", "ko")

        fun translationCode(language: String): String = if (language == "yue") "zh" else language
    }
}
