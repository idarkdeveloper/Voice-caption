package com.voicecaption.audio

import com.voicecaption.core.PcmFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBlockedDetectorTest {
    private fun silent(t: Long) = PcmFrame(FloatArray(512), t)
    private fun loud(t: Long) = PcmFrame(FloatArray(512) { 0.1f }, t)

    @Test
    fun silenceWhileMediaPlayingForWindowIsBlocked() {
        val d = CaptureBlockedDetector({ true })
        var t = 0L
        while (t < 5000) {
            assertFalse("t=$t", d.onFrame(silent(t)))
            t += 32
        }
        // First frame at or past 5000 ms of silence (clock started at t=0).
        assertTrue(d.onFrame(silent(5024)))
        assertTrue(d.onFrame(silent(5056)))
    }

    @Test
    fun blockedExactlyAtWindowBoundary() {
        val d = CaptureBlockedDetector({ true })
        assertFalse(d.onFrame(silent(0)))
        assertFalse(d.onFrame(silent(4999)))
        assertTrue(d.onFrame(silent(5000)))
    }

    @Test
    fun silenceWhileNothingPlayingIsNotBlocked() {
        val d = CaptureBlockedDetector({ false })
        var t = 0L
        while (t <= 10000) {
            assertFalse(d.onFrame(silent(t)))
            t += 32
        }
    }

    @Test
    fun anySoundResetsTheWindow() {
        val d = CaptureBlockedDetector({ true })
        var t = 0L
        while (t < 4000) { assertFalse(d.onFrame(silent(t))); t += 32 }
        assertFalse(d.onFrame(loud(t))); t += 32
        val end = t + 4000
        while (t < end) { assertFalse("t=$t", d.onFrame(silent(t))); t += 32 }
    }

    @Test
    fun recoversWhenSoundResumes() {
        val d = CaptureBlockedDetector({ true })
        assertFalse(d.onFrame(silent(0)))
        assertTrue(d.onFrame(silent(5000)))
        assertFalse(d.onFrame(loud(5032)))
        assertFalse(d.onFrame(silent(5064)))
    }

    @Test
    fun silenceClockRestartsWhileNothingPlaying() {
        var playing = false
        val d = CaptureBlockedDetector({ playing })
        assertFalse(d.onFrame(silent(0)))
        assertFalse(d.onFrame(silent(8000)))
        playing = true
        assertFalse(d.onFrame(silent(8032)))
        assertFalse(d.onFrame(silent(12000)))
        assertTrue(d.onFrame(silent(13032)))
    }

    @Test
    fun rmsThresholdIsRespectedAndEmptyFrameIsSilent() {
        val d = CaptureBlockedDetector({ true }, silenceRms = 0.05f, windowMs = 100)
        assertFalse(d.onFrame(PcmFrame(FloatArray(512) { 0.01f }, 0)))
        assertTrue(d.onFrame(PcmFrame(FloatArray(0), 100)))
        assertEquals(false, d.onFrame(PcmFrame(FloatArray(512) { 0.06f }, 132)))
    }
}
