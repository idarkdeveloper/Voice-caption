package com.voicecaption.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RecordingMathTest {
    @Test
    fun largeScreensScaleDownKeepingAspectAndEvenSides() {
        val (w, h) = RecordingMath.videoSize(1440, 3120) // a tall phone screen
        assertEquals(1920, h)
        assertTrue(w % 2 == 0 && h % 2 == 0)
        assertEquals(1440.0 / 3120, w.toDouble() / h, 0.01)
    }

    @Test
    fun smallScreensAreNotScaledUpAndOddSidesAreRoundedDown() {
        assertEquals(720 to 1280, RecordingMath.videoSize(720, 1280))
        assertEquals(720 to 1280, RecordingMath.videoSize(721, 1281))
    }

    @Test
    fun bitrateIsClamped() {
        assertEquals(2_000_000, RecordingMath.videoBitrate(100, 100))
        assertEquals(12_000_000, RecordingMath.videoBitrate(1920, 1080 * 3))
        val mid = RecordingMath.videoBitrate(1080, 1920)
        assertTrue(mid in 2_000_000..12_000_000)
    }

    @Test
    fun fileNameHasTheDateAndTime() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("TransCap-20261018-103700.mp4", RecordingMath.fileName(1_792_319_820_000L))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
