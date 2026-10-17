package com.voicecaption.service

/** What to do when the audio capture loop reports a failure. */
enum class CaptureFailureAction {
    /** Show the error and tear down after the message linger ([CaptureService] `fail`). */
    FAIL,

    /** The run is ending normally (e.g. the user stopped sharing); tear down without an error. */
    TEARDOWN,

    /** Already torn down; nothing to do. */
    IGNORE,
}

/**
 * A read error on the audio thread can race with the normal "stop sharing" projection callback on
 * the main thread. Decided on the main thread from the current [state] and whether the service is
 * already [tornDown]: a failure after teardown is ignored, after a stop it is just a teardown, and
 * only while still running/blocked it is a real error.
 */
fun captureFailureAction(state: ServiceState, tornDown: Boolean): CaptureFailureAction = when {
    tornDown -> CaptureFailureAction.IGNORE
    state == ServiceState.STOPPED -> CaptureFailureAction.TEARDOWN
    else -> CaptureFailureAction.FAIL
}
