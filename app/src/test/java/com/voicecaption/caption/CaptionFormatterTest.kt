package com.voicecaption.caption

import com.voicecaption.core.Caption
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionFormatterTest {
    private val final = Caption(1, "hola", "hello", true, 0)
    private val pending = Caption(2, "adios", null, false, 0)

    @Test
    fun finalCaptionShowsTranslation() {
        assertEquals(listOf("hello"), CaptionFormatter.lines(listOf(final), bilingual = false))
    }

    @Test
    fun bilingualShowsSourceThenTranslation() {
        assertEquals(listOf("hola", "hello"), CaptionFormatter.lines(listOf(final), bilingual = true))
    }

    @Test
    fun untranslatedCaptionShowsSourceOnlyInBothModes() {
        assertEquals(listOf("adios"), CaptionFormatter.lines(listOf(pending), bilingual = false))
        assertEquals(listOf("adios"), CaptionFormatter.lines(listOf(pending), bilingual = true))
    }

    @Test
    fun multipleCaptionsKeepOrder() {
        assertEquals(
            listOf("hello", "adios"),
            CaptionFormatter.lines(listOf(final, pending), bilingual = false),
        )
    }
}
