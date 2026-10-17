package com.voicecaption.service

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryLevelTest {
    @Test fun `unknown values map to full`() {
        assertEquals(100, normalizeBatteryPercent(Int.MIN_VALUE))
        assertEquals(100, normalizeBatteryPercent(-1))
    }

    @Test fun `valid values pass through and are capped`() {
        assertEquals(0, normalizeBatteryPercent(0))
        assertEquals(55, normalizeBatteryPercent(55))
        assertEquals(100, normalizeBatteryPercent(150))
    }
}
