package com.voicecaption.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateSupportTest {
    @Test fun tagIsLowercasedTrimmedAndReducedToPrimarySubtag() {
        assertEquals("en", normalizeTranslateLanguage("  EN "))
        assertEquals("pt", normalizeTranslateLanguage("pt-BR"))
        assertEquals("zh", normalizeTranslateLanguage("zh_CN"))
    }

    @Test fun legacyAndroidCodesAreMapped() {
        assertEquals("he", normalizeTranslateLanguage("iw"))
        assertEquals("id", normalizeTranslateLanguage("in"))
    }

    @Test fun unsupportedBlankAndAutoReturnNull() {
        assertNull(normalizeTranslateLanguage("jv"))
        assertNull(normalizeTranslateLanguage(""))
        assertNull(normalizeTranslateLanguage("auto"))
        assertNull(normalizeTranslateLanguage("xx"))
    }

    @Test fun supportedSetHasMlKit59Languages() {
        assertEquals(59, TRANSLATE_LANGUAGES.size)
        assertTrue("es" in TRANSLATE_LANGUAGES)
    }

    @Test fun pairNormalizesBothSides() {
        assertEquals(LanguagePair("en", "es"), languagePair("EN", "es-MX"))
    }

    @Test fun pairRejectsUnsupportedWithClearMessage() {
        val e = assertThrows(IllegalArgumentException::class.java) { languagePair("en", "jv") }
        assertTrue(e.message!!, e.message!!.contains("jv"))
        assertThrows(IllegalArgumentException::class.java) { languagePair("auto", "es") }
    }

    @Test fun sameLanguageDetected() {
        assertTrue(languagePair("en", "EN-us").isSameLanguage)
        assertEquals(false, languagePair("en", "es").isSameLanguage)
    }
}
