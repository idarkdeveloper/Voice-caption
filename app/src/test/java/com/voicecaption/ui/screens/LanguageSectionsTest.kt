package com.voicecaption.ui.screens

import com.voicecaption.settings.SupportedLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageSectionsTest {
    @Test
    fun downloadedLanguagesComeFirstAndAutoOnlyWhenAllowed() {
        val s = languageSections("", allowAuto = true, downloaded = setOf("en", "es"), autoName = "Auto-detect")
        assertEquals(listOf(SupportedLanguages.AUTO, "en", "es"), s.onPhone.sorted().let { listOf(SupportedLanguages.AUTO) + it.filter { c -> c != SupportedLanguages.AUTO } })
        assertTrue(s.others.none { it in setOf("en", "es") })
        assertEquals(SupportedLanguages.codes.size + 1, s.onPhone.size + s.others.size)

        val noAuto = languageSections("", allowAuto = false, downloaded = setOf("en"), autoName = "Auto-detect")
        assertFalse(SupportedLanguages.AUTO in noAuto.onPhone + noAuto.others)
    }

    @Test
    fun searchFiltersByDisplayNameIgnoringCase() {
        val s = languageSections("span", allowAuto = true, downloaded = setOf("en"), autoName = "Auto-detect")
        assertEquals(listOf("es"), s.onPhone + s.others)
    }

    @Test
    fun filipinoFindsTagalog() {
        val s = languageSections("filipino", allowAuto = false, downloaded = emptySet(), autoName = "Auto")
        assertEquals(listOf("tl"), s.onPhone + s.others)
        val t = languageSections("tagalog", allowAuto = false, downloaded = emptySet(), autoName = "Auto")
        assertEquals(listOf("tl"), t.onPhone + t.others)
    }

    @Test
    fun searchCanFindAutoDetectAndReportsNoMatch() {
        val auto = languageSections("auto", allowAuto = true, downloaded = emptySet(), autoName = "Auto-detect")
        assertEquals(listOf(SupportedLanguages.AUTO), auto.onPhone)
        val none = languageSections("zzzz", allowAuto = true, downloaded = emptySet(), autoName = "Auto-detect")
        assertTrue(none.onPhone.isEmpty() && none.others.isEmpty())
    }
}
