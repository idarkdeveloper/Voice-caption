package com.voicecaption.asr

import com.voicecaption.core.SpeechSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRecognizerTest {
    private class Fake(val name: String, override val outputLanguage: String? = null) : SpeechRecognizer {
        var calls = 0
        var closed = false
        override suspend fun transcribe(segment: SpeechSegment, language: String) = recognize(segment, language).text
        override suspend fun recognize(segment: SpeechSegment, language: String): Transcript {
            calls++
            return Transcript(name, "ko")
        }
        override fun close() { closed = true }
    }

    private fun seg(ms: Long, partial: Boolean = false) = SpeechSegment(FloatArray(16), 0, ms, partial = partial)

    @Test
    fun shortLinesAndPartialsStayOnTheFastRecognizer() = runTest {
        val fast = Fake("fast"); val long = Fake("long", outputLanguage = "en")
        val h = HybridRecognizer(fast, long)
        assertEquals("fast", h.recognize(seg(2_000), "ko").text)
        assertNull(h.outputLanguage)
        assertEquals("fast", h.recognize(seg(6_000, partial = true), "ko").text)
        assertEquals(0, long.calls)
    }

    @Test
    fun longFinishedKoreanSentencesGoToTheLongRecognizerAndSkipTranslation() = runTest {
        val fast = Fake("fast"); val long = Fake("long", outputLanguage = "en")
        val h = HybridRecognizer(fast, long)
        assertEquals("long", h.recognize(seg(4_000), "ko").text)
        assertEquals("en", h.outputLanguage)
        assertEquals("fast", h.recognize(seg(1_000), "ko").text) // the next short line is back on fast
        assertNull(h.outputLanguage)
    }

    @Test
    fun otherLanguagesNeverUseTheLongRecognizerAndBothAreClosed() = runTest {
        val fast = Fake("fast"); val long = Fake("long", outputLanguage = "en")
        val h = HybridRecognizer(fast, long)
        assertEquals("fast", h.recognize(seg(6_000), "ja").text)
        h.close()
        assertTrue(fast.closed && long.closed)
    }
}
