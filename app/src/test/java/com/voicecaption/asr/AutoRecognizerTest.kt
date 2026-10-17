package com.voicecaption.asr

import com.voicecaption.core.SpeechSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoRecognizerTest {
    private class Fake(
        private val textOf: (String) -> String,
        private val detected: String? = null,
        override val outputLanguage: String? = null,
        private val event: String? = null,
    ) : SpeechRecognizer {
        val calls = mutableListOf<String>() // languages asked for
        var closed = false
        override suspend fun transcribe(segment: SpeechSegment, language: String) = recognize(segment, language).text
        override suspend fun recognize(segment: SpeechSegment, language: String): Transcript {
            calls += language
            return Transcript(textOf(language), detected, event)
        }
        override fun close() { closed = true }
    }

    private fun segment(ms: Long = 2_000, partial: Boolean = false) =
        SpeechSegment(FloatArray(16), 0, ms, utterance = 0, partial = partial)

    private fun auto(
        whisper: SpeechRecognizer,
        sense: SpeechRecognizer? = null,
        ready: Boolean = true,
        target: String = "en",
    ) = AutoRecognizer(whisper, sense, translatorReady = { _, _ -> ready }, targetLang = { target })

    @Test
    fun listensWithWhisperAutoDetectUntilTheLanguageIsLocked() = runTest {
        val whisper = Fake({ "hello" }, detected = "ja")
        val r = auto(whisper)
        val first = r.recognize(segment(), "auto")
        assertNull(first.language)               // not decided yet
        assertEquals(listOf(""), whisper.calls)  // Whisper's own detection
        val second = r.recognize(segment(), "auto")
        assertEquals("ja", second.language)      // two agreeing sentences lock it
        assertEquals("ja", r.lockedLanguage)
    }

    @Test
    fun partialsAndShortOrEmptySegmentsDoNotVote() = runTest {
        val whisper = Fake({ if (it == "") "x" else "x" }, detected = "ja")
        val r = auto(whisper)
        repeat(5) { r.recognize(segment(partial = true), "auto") }
        repeat(5) { r.recognize(segment(ms = 300), "auto") }
        assertNull(r.lockedLanguage)
        val silent = Fake({ "" }, detected = "ja")
        val r2 = auto(silent)
        repeat(5) { r2.recognize(segment(), "auto") }
        assertNull(r2.lockedLanguage)
    }

    @Test
    fun japaneseMovesToSenseVoiceWhenInstalledAndTheCaptionLanguageIsReachable() = runTest {
        val whisper = Fake({ "w" }, detected = "ja")
        val sense = Fake({ "s" }, detected = "ja")
        val r = auto(whisper, sense)
        r.recognize(segment(), "auto"); r.recognize(segment(), "auto") // lock
        val t = r.recognize(segment(), "auto")
        assertEquals("s", t.text)
        assertEquals("ja", t.language)
        assertEquals(listOf("auto"), sense.calls)
        assertNull(r.outputLanguage)
        assertEquals("SENSEVOICE", r.engine)
    }

    @Test
    fun cantoneseIsTranslatedAsChinese() = runTest {
        val whisper = Fake({ "w" }, detected = "zh")
        val sense = Fake({ "s" }, detected = "yue")
        val r = auto(whisper, sense)
        r.recognize(segment(), "auto"); r.recognize(segment(), "auto")
        assertEquals("zh", r.recognize(segment(), "auto").language)
    }

    @Test
    fun staysOnWhisperWithTheDetectedLanguageWhenSenseVoiceIsMissingOrNotUsable() = runTest {
        val whisper = Fake({ "w" }, detected = "ja")
        val noSense = auto(whisper, null)
        noSense.recognize(segment(), "auto"); noSense.recognize(segment(), "auto")
        val t = noSense.recognize(segment(), "auto")
        assertEquals("w", t.text)
        assertEquals(listOf("", "", "ja"), whisper.calls)

        val whisper2 = Fake({ "w" }, detected = "ja")
        val sense = Fake({ "s" }, detected = "ja")
        val notReady = auto(whisper2, sense, ready = false)
        notReady.recognize(segment(), "auto"); notReady.recognize(segment(), "auto")
        assertEquals("w", notReady.recognize(segment(), "auto").text) // pack missing: Whisper handles it
        assertEquals(emptyList<String>(), sense.calls)
    }

    @Test
    fun otherLanguagesStayOnWhisper() = runTest {
        val whisper = Fake({ "w" }, detected = "fr")
        val sense = Fake({ "s" }, detected = "fr")
        val r = auto(whisper, sense)
        r.recognize(segment(), "auto"); r.recognize(segment(), "auto")
        assertEquals("w", r.recognize(segment(), "auto").text)
        assertEquals("fr", r.lockedLanguage)
        assertEquals(emptyList<String>(), sense.calls)
    }

    @Test
    fun whisperTranslateOutputLanguageIsPassedThrough() = runTest {
        val whisper = Fake({ "w" }, detected = "ja", outputLanguage = "en")
        val r = auto(whisper)
        r.recognize(segment(), "auto")
        assertEquals("en", r.outputLanguage)
    }

    @Test
    fun anExplicitLanguageGoesStraightToWhisper() = runTest {
        val whisper = Fake({ "w" })
        val r = auto(whisper)
        r.recognize(segment(), "es")
        assertEquals(listOf("es"), whisper.calls)
        assertNull(r.lockedLanguage)
    }

    @Test
    fun closeClosesBothEngines() {
        val whisper = Fake({ "w" }); val sense = Fake({ "s" })
        auto(whisper, sense).close()
        assertEquals(true, whisper.closed && sense.closed)
    }

    @Test
    fun theHeardSoundEventIsPassedThrough() = runTest {
        val whisper = Fake({ "w" }, detected = "ja")
        val sense = Fake({ "" }, detected = "ja", event = "bgm")
        val r = auto(whisper, sense)
        r.recognize(segment(), "auto"); r.recognize(segment(), "auto") // lock
        assertEquals("bgm", r.recognize(segment(), "auto").event)
    }

    @Test
    fun musicAndSoundTagsNeverVoteForALanguage() = runTest {
        // Whisper named a language ("nn") for a music clip in a real check.
        for (tag in listOf("[Music]", "(upbeat music)", "♪", "(applause)", "(The sound of")) {
            val r = auto(Fake({ tag }, detected = "nn"))
            repeat(6) { r.recognize(segment(), "auto") }
            assertNull(tag, r.lockedLanguage)
        }
    }

    @Test
    fun wordsAfterMusicStillDecideTheLanguage() = runTest {
        var n = 0
        val r = auto(Fake({ if (n++ < 3) "[Music]" else "konnichiwa" }, detected = "ja"))
        repeat(3) { r.recognize(segment(), "auto") }
        assertNull(r.lockedLanguage)
        repeat(2) { r.recognize(segment(), "auto") }
        assertEquals("ja", r.lockedLanguage)
    }
}
