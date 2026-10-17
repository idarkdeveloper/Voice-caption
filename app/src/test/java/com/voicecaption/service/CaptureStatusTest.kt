package com.voicecaption.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatusTest {
    @After
    fun reset() = CaptureStatus.set(false)

    @Test
    fun startsNotRunning() = assertFalse(CaptureStatus.running.value)

    @Test
    fun setReflectedInRunning() {
        CaptureStatus.set(true)
        assertTrue(CaptureStatus.running.value)
        CaptureStatus.set(false)
        assertFalse(CaptureStatus.running.value)
    }
}
