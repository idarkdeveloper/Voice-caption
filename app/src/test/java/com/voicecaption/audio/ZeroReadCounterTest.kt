package com.voicecaption.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroReadCounterTest {
    @Test fun `limit consecutive zero reads trip once the limit is reached`() {
        val c = ZeroReadCounter(limit = 3)
        assertFalse(c.record(0))
        assertFalse(c.record(0))
        assertTrue(c.record(0))
    }

    @Test fun `a non-empty read resets the streak`() {
        val c = ZeroReadCounter(limit = 3)
        c.record(0); c.record(0)
        assertFalse(c.record(512))
        assertFalse(c.record(0))
        assertFalse(c.record(0))
        assertTrue(c.record(0))
    }
}
