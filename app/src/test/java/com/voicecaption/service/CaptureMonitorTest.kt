package com.voicecaption.service

import com.voicecaption.audio.CaptureBlockedDetector
import com.voicecaption.core.PcmFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureMonitorTest {
    private var playing = true
    private var overlay = true

    private fun monitor(): CaptureMonitor {
        val m = CaptureMonitor(CaptureBlockedDetector({ playing }), { overlay })
        m.dispatch(ServiceEvent.Start)
        m.dispatch(ServiceEvent.Ready)
        return m
    }

    private fun frame(ts: Long, level: Float = 0f) = PcmFrame(FloatArray(512) { level }, ts)

    @Test fun `starts running after Start and Ready`() {
        assertEquals(ServiceState.RUNNING, monitor().state)
    }

    @Test fun `five seconds of silence while playing blocks exactly once`() {
        val m = monitor()
        val seen = mutableListOf<ServiceState>()
        var ts = 0L
        while (ts <= 6000) {
            val s = m.onFrame(frame(ts))
            if (seen.lastOrNull() != s) seen += s
            ts += 32
        }
        assertEquals(listOf(ServiceState.RUNNING, ServiceState.BLOCKED), seen)
    }

    @Test fun `audible frame after block returns to running`() {
        val m = monitor()
        var ts = 0L
        while (ts <= 5100) { m.onFrame(frame(ts)); ts += 32 }
        assertEquals(ServiceState.BLOCKED, m.state)
        assertEquals(ServiceState.RUNNING, m.onFrame(frame(ts, 0.3f)))
    }

    @Test fun `overlay permission lost stops`() {
        val m = monitor()
        assertEquals(ServiceState.RUNNING, m.onFrame(frame(0, 0.3f)))
        overlay = false
        assertEquals(ServiceState.STOPPED, m.onFrame(frame(32, 0.3f)))
    }

    @Test fun `stopped is terminal`() {
        val m = monitor()
        m.dispatch(ServiceEvent.UserStop)
        assertEquals(ServiceState.STOPPED, m.dispatch(ServiceEvent.Ready))
        assertEquals(ServiceState.STOPPED, m.onFrame(frame(0, 0.3f)))
    }

    @Test fun `projection stopped stops`() {
        assertEquals(ServiceState.STOPPED, monitor().dispatch(ServiceEvent.ProjectionStopped))
    }

    @Test fun `silence is not blocked while nothing is playing`() {
        playing = false
        val m = monitor()
        var ts = 0L
        while (ts <= 8000) { m.onFrame(frame(ts)); ts += 32 }
        assertEquals(ServiceState.RUNNING, m.state)
    }
}
