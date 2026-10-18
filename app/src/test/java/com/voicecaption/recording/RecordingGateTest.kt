package com.voicecaption.recording

import com.voicecaption.recording.RecordingGate.VideoAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingGateTest {
    private val ms = 1_000L

    @Test
    fun nothingIsWrittenWhileTransCapIsOnScreen() {
        val g = RecordingGate()
        assertEquals(VideoAction.Drop(false), g.onVideo(0, keyFrame = true, hidden = false))
        assertNull(g.onAudio(0, hidden = false))
        assertTrue(!g.hasStarted)
    }

    @Test
    fun writingStartsAtTheFirstKeyFrameAfterTheAppLeavesAndTimeStartsAtZero() {
        val g = RecordingGate()
        assertEquals(VideoAction.Drop(true), g.onVideo(5_000 * ms, keyFrame = false, hidden = true)) // wait for a key frame
        assertNull(g.onAudio(5_000 * ms, hidden = true)) // no audio before the first video frame
        assertEquals(VideoAction.Write(0), g.onVideo(5_100 * ms, keyFrame = true, hidden = true))
        assertEquals(VideoAction.Write(33 * ms), g.onVideo(5_133 * ms, keyFrame = false, hidden = true))
        assertEquals(50 * ms, g.onAudio(5_150 * ms, hidden = true))
    }

    @Test
    fun comingBackToTransCapPausesAndResumingJoinsWithoutAGap() {
        val g = RecordingGate(frameUs = 33_333)
        g.onVideo(1_000 * ms, true, hidden = true) // written at 0
        g.onVideo(1_500 * ms, false, hidden = true) // written at 500 ms
        assertEquals(VideoAction.Drop(false), g.onVideo(2_000 * ms, false, hidden = false)) // paused
        assertNull(g.onAudio(2_000 * ms, hidden = false))
        assertEquals(VideoAction.Drop(true), g.onVideo(9_000 * ms, false, hidden = true)) // away again, waiting for a key frame
        val resumed = g.onVideo(9_100 * ms, true, hidden = true) as VideoAction.Write
        assertEquals(500 * ms + 33_333, resumed.outUs) // right after the last written frame
        // Audio shares the shifted clock.
        assertEquals(resumed.outUs + 20 * ms, g.onAudio(9_120 * ms, hidden = true))
    }

    @Test
    fun anAppThatNeverLeavesWritesNothing() {
        val g = RecordingGate()
        repeat(10) { assertEquals(VideoAction.Drop(false), g.onVideo(it * 33 * ms, it % 3 == 0, hidden = false)) }
        assertTrue(!g.hasStarted)
    }
}
