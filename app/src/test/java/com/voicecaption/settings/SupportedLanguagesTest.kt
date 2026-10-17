package com.voicecaption.settings

import com.voicecaption.asr.WHISPER_LANGUAGES
import com.voicecaption.translate.TRANSLATE_LANGUAGES
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedLanguagesTest {
    @Test
    fun containsCommonLanguages() {
        for (c in listOf("en", "es", "fr", "de", "ja", "zh", "ar", "hi", "he", "no")) {
            assertTrue(c, c in SupportedLanguages.codes)
            assertTrue(c, SupportedLanguages.isSupported(c))
        }
    }

    @Test
    fun excludesAutoAndLanguagesMissingFromEitherSide() {
        for (c in listOf("auto", "yue", "jw", "eo", "ga", "haw", "su")) {
            assertFalse(c, c in SupportedLanguages.codes)
            assertFalse(c, SupportedLanguages.isSupported(c))
        }
        assertTrue(SupportedLanguages.codes.all { it in WHISPER_LANGUAGES && it in TRANSLATE_LANGUAGES })
        assertEquals((WHISPER_LANGUAGES intersect TRANSLATE_LANGUAGES), SupportedLanguages.codes.toSet())
    }

    @Test
    fun sortedByEnglishNameWithoutDuplicates() {
        val codes = SupportedLanguages.codes
        assertEquals(codes.size, codes.toSet().size)
        val names = codes.map { SupportedLanguages.displayName(it, Locale.ENGLISH) }
        assertEquals(names.sortedWith(String.CASE_INSENSITIVE_ORDER), names)
    }

    @Test
    fun displayNameIsCapitalized() {
        assertEquals("Spanish", SupportedLanguages.displayName("es", Locale.ENGLISH))
        assertEquals("Hebrew", SupportedLanguages.displayName("he", Locale.ENGLISH))
        assertTrue(SupportedLanguages.codes.all { SupportedLanguages.displayName(it, Locale.ENGLISH).first().isUpperCase() })
    }

    @Test
    fun coerceNormalizesOrFallsBack() {
        assertEquals("pt", SupportedLanguages.coerce("pt-BR", "en"))
        assertEquals("en", SupportedLanguages.coerce("xx", "en"))
        assertEquals("he", SupportedLanguages.coerce("iw", "en"))
        assertEquals("id", SupportedLanguages.coerce("in", "en"))
        assertEquals("fr", SupportedLanguages.coerce("auto", "fr"))
        assertEquals("fr", SupportedLanguages.coerce("", "fr"))
        assertEquals("fr", SupportedLanguages.coerce("jw", "fr"))
    }
}
