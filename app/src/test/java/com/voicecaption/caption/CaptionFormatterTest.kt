package com.voicecaption.caption

import com.voicecaption.core.Caption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionFormatterTest {
    private val final = Caption(1, "hola", "hello", true, 0)
    private val pending = Caption(2, "adios", null, false, 0)

    @Test
    fun nonBilingualUsesTheTranslationPlateOnly() {
        val p = CaptionFormatter.plates(listOf(final), bilingual = false)
        assertTrue(p.source.isEmpty())
        assertEquals(listOf("hello"), p.translation)
    }

    @Test
    fun nonBilingualShowsUntranslatedSourceTextInTheTranslationPlate() {
        val p = CaptionFormatter.plates(listOf(pending), bilingual = false)
        assertEquals(listOf("adios"), p.translation)
        assertTrue(p.source.isEmpty())
    }

    @Test
    fun bilingualPutsOriginalAndTranslationInSeparatePlates() {
        val p = CaptionFormatter.plates(listOf(final), bilingual = true)
        assertEquals(listOf("hola"), p.source)
        assertEquals(listOf("hello"), p.translation)
    }

    @Test
    fun bilingualPendingCaptionAppearsInTheSourcePlateOnly() {
        val p = CaptionFormatter.plates(listOf(pending), bilingual = true)
        assertEquals(listOf("adios"), p.source)
        assertTrue(p.translation.isEmpty())
    }

    @Test
    fun multipleCaptionsKeepOrderInEachPlate() {
        val p = CaptionFormatter.plates(listOf(final, pending), bilingual = true)
        assertEquals(listOf("hola", "adios"), p.source)
        assertEquals(listOf("hello"), p.translation)
    }

    @Test
    fun bilingualShowsIdenticalSourceAndTranslationOnceInTheTranslationPlate() {
        val same = Caption(3, "Hello there.", "hello there.", true, 0)
        val p = CaptionFormatter.plates(listOf(same), bilingual = true)
        assertTrue(p.source.isEmpty())
        assertEquals(listOf("hello there."), p.translation)
    }

    @Test
    fun noCaptionsMeansNoLines() {
        assertTrue(CaptionFormatter.plates(emptyList(), bilingual = true).isEmpty)
    }
}
