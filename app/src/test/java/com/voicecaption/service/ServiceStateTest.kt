package com.voicecaption.service

import com.voicecaption.service.ServiceEvent.CaptureAudible
import com.voicecaption.service.ServiceEvent.CaptureBlocked
import com.voicecaption.service.ServiceEvent.OverlayPermissionLost
import com.voicecaption.service.ServiceEvent.ProjectionStopped
import com.voicecaption.service.ServiceEvent.Ready
import com.voicecaption.service.ServiceEvent.Start
import com.voicecaption.service.ServiceEvent.UserStop
import com.voicecaption.service.ServiceState.BLOCKED
import com.voicecaption.service.ServiceState.IDLE
import com.voicecaption.service.ServiceState.RUNNING
import com.voicecaption.service.ServiceState.STARTING
import com.voicecaption.service.ServiceState.STOPPED
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceStateTest {
    /** Only the transitions that change state; every other (state, event) pair must be a no-op. */
    private val changing: Map<Pair<ServiceState, ServiceEvent>, ServiceState> = buildMap {
        put(IDLE to Start, STARTING)
        put(STARTING to Ready, RUNNING)
        put(RUNNING to CaptureBlocked, BLOCKED)
        put(BLOCKED to CaptureAudible, RUNNING)
        for (s in ServiceState.entries) {
            for (e in listOf(ProjectionStopped, OverlayPermissionLost, UserStop)) put(s to e, STOPPED)
        }
    }

    @Test
    fun fullStateEventTable() {
        assertEquals(5 * 7, ServiceState.entries.size * ServiceEvent.entries.size)
        for (s in ServiceState.entries) {
            for (e in ServiceEvent.entries) {
                val expected = if (s == STOPPED) STOPPED else changing[s to e] ?: s
                assertEquals("$s + $e", expected, ServiceStateMachine.next(s, e))
            }
        }
    }

    @Test
    fun happyPath() {
        var s = IDLE
        for (e in listOf(Start, Ready, CaptureBlocked, CaptureAudible)) s = ServiceStateMachine.next(s, e)
        assertEquals(RUNNING, s)
    }

    @Test
    fun stoppedIsTerminal() {
        for (e in ServiceEvent.entries) assertEquals(STOPPED, ServiceStateMachine.next(STOPPED, e))
    }

    @Test
    fun stopEventsWinFromEveryState() {
        for (s in ServiceState.entries) {
            assertEquals(STOPPED, ServiceStateMachine.next(s, ProjectionStopped))
            assertEquals(STOPPED, ServiceStateMachine.next(s, OverlayPermissionLost))
            assertEquals(STOPPED, ServiceStateMachine.next(s, UserStop))
        }
    }
}
