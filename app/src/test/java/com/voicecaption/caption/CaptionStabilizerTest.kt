package com.voicecaption.caption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionStabilizerTest {
    @Test
    fun translatedReplacesSourceForSameId() {
        val s = CaptionStabilizer()
        s.onSourceText(1, "hola", 0)
        val out = s.onTranslated(1, "hello", 100)
        assertEquals(1, out.size)
        assertTrue(out[0].isFinal)
        assertEquals("hello", out[0].translatedText)
        assertEquals("hola", out[0].sourceText)
    }

    @Test
    fun sourceTextIsNotFinalAndHasNoTranslation() {
        val out = CaptionStabilizer().onSourceText(1, "hola", 0)
        assertFalse(out[0].isFinal)
        assertEquals(null, out[0].translatedText)
    }

    @Test
    fun blankTextIsIgnored() {
        val s = CaptionStabilizer()
        assertTrue(s.onSourceText(1, "  ", 0).isEmpty())
        assertTrue(s.onTranslated(1, "", 0).isEmpty())
    }

    @Test
    fun onlyLastTwoCaptionsKept() {
        val s = CaptionStabilizer()
        s.onSourceText(1, "a", 0)
        s.onSourceText(2, "b", 1)
        val out = s.onSourceText(3, "c", 2)
        assertEquals(listOf(2L, 3L), out.map { it.id })
    }

    @Test
    fun listIsOrderedOldestToNewestAndUpdateKeepsPosition() {
        val s = CaptionStabilizer()
        s.onSourceText(1, "a", 0)
        s.onSourceText(2, "b", 1)
        val out = s.onSourceText(1, "a2", 2)
        assertEquals(listOf(1L, 2L), out.map { it.id })
        assertEquals("a2", out[0].sourceText)
    }

    @Test
    fun updateRefreshesUpdatedAtMs() {
        val s = CaptionStabilizer()
        s.onSourceText(1, "a", 0)
        val out = s.onTranslated(1, "b", 3000)
        assertEquals(3000L, out[0].updatedAtMs)
        assertTrue(s.expire(6999).isNotEmpty())
        assertTrue(s.expire(7001).isEmpty())
    }

    @Test
    fun translationForUnknownIdCreatesFinalCaptionWithEmptySource() {
        val out = CaptionStabilizer().onTranslated(7, "hello", 5)
        assertEquals(1, out.size)
        assertEquals("", out[0].sourceText)
        assertTrue(out[0].isFinal)
    }

    @Test
    fun expireRemovesCaptionsOlderThanDisplayMs() {
        val s = CaptionStabilizer()
        s.onSourceText(1, "a", 0)
        assertEquals(1, s.expire(3999).size)
        assertTrue(s.expire(4001).isEmpty())
    }

    @Test
    fun longTextStaysLongerThanShortText() {
        val s = CaptionStabilizer()
        val long = "x".repeat(60) // 1500 + 60 * 80 = 6300 ms
        s.onTranslated(1, long, 0)
        assertEquals(1, s.expire(6000).size)
        assertTrue(s.expire(6301).isEmpty())
    }

    @Test
    fun displayTimeIsCapped() {
        val s = CaptionStabilizer()
        s.onTranslated(1, "x".repeat(500), 0)
        assertEquals(1, s.expire(9000).size)
        assertTrue(s.expire(9001).isEmpty())
    }
}
