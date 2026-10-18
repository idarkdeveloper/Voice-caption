package com.voicecaption.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {
    @Test
    fun parsesSixDigitHexWithOrWithoutHashInAnyCase() {
        assertEquals(0xFFFFE45C.toInt(), ColorMath.parseHex("#FFE45C"))
        assertEquals(0xFFFFE45C.toInt(), ColorMath.parseHex("ffe45c"))
        assertEquals(0xFF000000.toInt(), ColorMath.parseHex(" #000000 "))
    }

    @Test
    fun rejectsAnythingThatIsNotExactlySixHexDigits() {
        for (bad in listOf("", "#FFF", "12345", "1234567", "GGGGGG", "#12 456", "#12345Z")) {
            assertNull(bad, ColorMath.parseHex(bad))
        }
    }

    @Test
    fun formatsUpperCaseHexIgnoringAlpha() {
        assertEquals("#FFE45C", ColorMath.formatHex(0xFFFFE45C.toInt()))
        assertEquals("#0B1A33", ColorMath.formatHex(0x800B1A33.toInt()))
        assertEquals("#000000", ColorMath.formatHex(ColorMath.BLACK))
    }

    @Test
    fun opaqueForcesFullAlpha() {
        assertEquals(0xFF123456.toInt(), ColorMath.opaque(0x00123456))
        assertEquals(0xFF123456.toInt(), ColorMath.opaque(0x80123456.toInt()))
    }

    @Test
    fun contrastMatchesWcagReferenceValues() {
        assertEquals(21.0, ColorMath.contrastRatio(ColorMath.WHITE, ColorMath.BLACK), 0.01)
        assertEquals(1.0, ColorMath.contrastRatio(ColorMath.WHITE, ColorMath.WHITE), 0.0001)
        // #777777 on white is the classic ~4.48:1 AA-borderline pair.
        assertEquals(4.48, ColorMath.contrastRatio(0xFF777777.toInt(), ColorMath.WHITE), 0.02)
    }

    @Test
    fun everyPresetTextColourReadsOnEveryPresetPlate() {
        val texts = listOf(0xFFFFFFFF, 0xFFFFE45C, 0xFF7FE3FF, 0xFF8DF28D, 0xFFD0D0D0).map { it.toInt() }
        val plates = listOf(0xFF000000, 0xFF1C1C1C, 0xFF0B1A33, 0xFF24113D, 0xFF2B1A10).map { it.toInt() }
        for (t in texts) for (p in plates) {
            assertTrue("${ColorMath.formatHex(t)} on ${ColorMath.formatHex(p)}", ColorMath.contrastRatio(t, p) >= ColorMath.MIN_READABLE_CONTRAST)
        }
    }

    @Test
    fun readableOnPicksWhiteForDarkAndBlackForLight() {
        assertEquals(ColorMath.WHITE, ColorMath.readableOn(ColorMath.BLACK))
        assertEquals(ColorMath.BLACK, ColorMath.readableOn(ColorMath.WHITE))
        assertEquals(ColorMath.BLACK, ColorMath.readableOn(0xFFFFE45C.toInt()))
    }
}
