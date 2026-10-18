package com.voicecaption.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechRecognizerSupportTest {
    @Test
    fun eventTagsAreNormalized() {
        assertEquals("bgm", normalizeEvent("<|BGM|>"))
        assertEquals("speech", normalizeEvent(" <|Speech|> "))
        assertNull(normalizeEvent(""))
        assertNull(normalizeEvent(null))
    }

    @Test
    fun languageTagsAreNormalized() {
        assertEquals("ja", normalizeDetectedLanguage("<|ja|>"))
        assertEquals("ja", normalizeDetectedLanguage(" JA "))
        assertNull(normalizeDetectedLanguage("auto"))
        assertNull(normalizeDetectedLanguage(""))
    }
}
