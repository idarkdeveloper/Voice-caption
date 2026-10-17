package com.voicecaption.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFailureActionTest {
    @Test fun `stopped state means a normal stop`() =
        assertEquals(CaptureFailureAction.TEARDOWN, captureFailureAction(ServiceState.STOPPED, tornDown = false))

    @Test fun `already torn down is ignored`() {
        for (s in ServiceState.values()) {
            assertEquals(CaptureFailureAction.IGNORE, captureFailureAction(s, tornDown = true))
        }
    }

    @Test fun `running and blocked are real failures`() {
        assertEquals(CaptureFailureAction.FAIL, captureFailureAction(ServiceState.RUNNING, tornDown = false))
        assertEquals(CaptureFailureAction.FAIL, captureFailureAction(ServiceState.BLOCKED, tornDown = false))
    }
}
