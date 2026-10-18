package com.voicecaption.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SizeStepperTest {
    @Test
    fun movesOneStepEachWay() {
        assertEquals(19, steppedFontSize(18, 1, 14, 32))
        assertEquals(17, steppedFontSize(18, -1, 14, 32))
    }

    @Test
    fun staysInsideTheLimits() {
        assertEquals(32, steppedFontSize(32, 1, 14, 32))
        assertEquals(14, steppedFontSize(14, -1, 14, 32))
        assertEquals(32, steppedFontSize(40, 1, 14, 32)) // an out-of-range value is pulled back
        assertEquals(14, steppedFontSize(3, -1, 14, 32))
    }
}
