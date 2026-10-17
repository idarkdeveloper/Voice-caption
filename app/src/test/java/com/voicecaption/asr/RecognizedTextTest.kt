package com.voicecaption.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizedTextTest {
    @Test
    fun musicIsRecognizedFromTagsSymbolsAndTheEvent() {
        for (t in listOf("[Music]", "(upbeat music)", "[MUSIC PLAYING]", "♪", "♪♪", "(música)", "（音楽）".replace('（', '(').replace('）', ')'))) {
            assertTrue(t, RecognizedText.isMusic(null, t))
        }
        assertTrue(RecognizedText.isMusic("bgm", "."))
        assertTrue(RecognizedText.isMusic("bgm", ""))
    }

    @Test
    fun wordsAreNeverMusic() {
        assertFalse(RecognizedText.isMusic("bgm", "Let's go"))
        assertFalse(RecognizedText.isMusic(null, "♪ la la la ♪"))
        assertFalse(RecognizedText.isMusic(null, "Hello"))
        assertFalse(RecognizedText.isMusic(null, "(applause)"))
        assertFalse(RecognizedText.isMusic("speech", ""))
    }

    @Test
    fun cutOffNotesAreRemovedAndCompleteTagsInSpeechStay() {
        assertEquals("I'm sorry", RecognizedText.stripSoundTags("I'm sorry (The sound of"))
        assertEquals("", RecognizedText.stripSoundTags("[Music"))
        assertEquals("[Music] hola (risas) amigo", RecognizedText.stripSoundTags("[Music] hola (risas) amigo"))
    }

    @Test
    fun hasWordsIgnoresTagsAndSymbols() {
        assertFalse(RecognizedText.hasWords("[Music]"))
        assertFalse(RecognizedText.hasWords("♪"))
        assertFalse(RecognizedText.hasWords("(The sound of"))
        assertFalse(RecognizedText.hasWords("  "))
        assertTrue(RecognizedText.hasWords("hello"))
        assertTrue(RecognizedText.hasWords("[Music] hello"))
    }
}
