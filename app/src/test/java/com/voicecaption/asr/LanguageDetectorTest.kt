package com.voicecaption.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageDetectorTest {
    @Test
    fun locksAfterTwoAgreeingVotes() {
        val d = LanguageDetector()
        assertNull(d.vote("ja"))
        assertEquals("ja", d.vote("ja"))
        assertEquals("ja", d.locked)
    }

    @Test
    fun ignoresBlankGuesses() {
        val d = LanguageDetector()
        assertNull(d.vote(null))
        assertNull(d.vote(""))
        assertNull(d.vote("  "))
        assertNull(d.locked)
    }

    @Test
    fun aSingleStrayGuessDoesNotDecide() {
        val d = LanguageDetector()
        d.vote("ja"); d.vote("en")
        assertNull(d.locked)
        d.vote("ja")
        assertEquals("ja", d.locked)
    }

    @Test
    fun aTieKeepsListeningUntilTheVoteLimitThenTheLeaderWins() {
        val d = LanguageDetector(lockAfterVotes = 2, maxVotes = 4)
        d.vote("ja"); d.vote("zh"); d.vote("ja"); // ja leads 2:1 -> already locked
        assertEquals("ja", d.locked)
        val e = LanguageDetector(lockAfterVotes = 3, maxVotes = 4)
        e.vote("ko"); e.vote("zh"); e.vote("ko"); assertNull(e.locked)
        e.vote("zh"); assertEquals("ko", e.locked) // 2:2 at the limit, first seen wins
    }

    @Test
    fun onceLockedItNeverChanges() {
        val d = LanguageDetector()
        d.vote("fr"); d.vote("fr")
        d.vote("ja"); d.vote("ja"); d.vote("ja")
        assertEquals("fr", d.locked)
    }

    @Test
    fun votesAreCaseInsensitive() {
        val d = LanguageDetector()
        d.vote("JA"); d.vote("ja")
        assertEquals("ja", d.locked)
    }
}
