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
    private fun row(s: ServiceState, e: ServiceEvent, expected: ServiceState) = Triple(s to e, expected, Unit)

    /** Hand-written, literal 5 x 7 table: every (state, event) pair spelled out, nothing derived. */
    private val table: Map<Pair<ServiceState, ServiceEvent>, ServiceState> = listOf(
        row(IDLE, Start, STARTING),
        row(IDLE, Ready, IDLE),
        row(IDLE, CaptureBlocked, IDLE),
        row(IDLE, CaptureAudible, IDLE),
        row(IDLE, ProjectionStopped, STOPPED),
        row(IDLE, OverlayPermissionLost, STOPPED),
        row(IDLE, UserStop, STOPPED),
        row(STARTING, Start, STARTING),
        row(STARTING, Ready, RUNNING),
        row(STARTING, CaptureBlocked, STARTING),
        row(STARTING, CaptureAudible, STARTING),
        row(STARTING, ProjectionStopped, STOPPED),
        row(STARTING, OverlayPermissionLost, STOPPED),
        row(STARTING, UserStop, STOPPED),
        row(RUNNING, Start, RUNNING),
        row(RUNNING, Ready, RUNNING),
        row(RUNNING, CaptureBlocked, BLOCKED),
        row(RUNNING, CaptureAudible, RUNNING),
        row(RUNNING, ProjectionStopped, STOPPED),
        row(RUNNING, OverlayPermissionLost, STOPPED),
        row(RUNNING, UserStop, STOPPED),
        row(BLOCKED, Start, BLOCKED),
        row(BLOCKED, Ready, BLOCKED),
        row(BLOCKED, CaptureBlocked, BLOCKED),
        row(BLOCKED, CaptureAudible, RUNNING),
        row(BLOCKED, ProjectionStopped, STOPPED),
        row(BLOCKED, OverlayPermissionLost, STOPPED),
        row(BLOCKED, UserStop, STOPPED),
        row(STOPPED, Start, STOPPED),
        row(STOPPED, Ready, STOPPED),
        row(STOPPED, CaptureBlocked, STOPPED),
        row(STOPPED, CaptureAudible, STOPPED),
        row(STOPPED, ProjectionStopped, STOPPED),
        row(STOPPED, OverlayPermissionLost, STOPPED),
        row(STOPPED, UserStop, STOPPED),
    ).associate { it.first to it.second }

    @Test
    fun fullStateEventTable() {
        assertEquals(35, ServiceState.entries.size * ServiceEvent.entries.size)
        assertEquals("table must have exactly 35 rows", 35, table.size)
        for (s in ServiceState.entries) {
            for (e in ServiceEvent.entries) {
                val expected = table[s to e] ?: throw AssertionError("table is missing $s + $e")
                assertEquals("$s + $e", expected, ServiceStateMachine.next(s, e))
            }
        }
    }

    @Test
    fun nonsensicalEventsLeaveStateUnchanged() {
        assertEquals(IDLE, ServiceStateMachine.next(IDLE, Ready))
        assertEquals(RUNNING, ServiceStateMachine.next(RUNNING, Start))
        assertEquals(RUNNING, ServiceStateMachine.next(RUNNING, CaptureAudible))
        assertEquals(STARTING, ServiceStateMachine.next(STARTING, CaptureBlocked))
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
