package com.voicecaption.service

/** Lifecycle of the caption foreground service. */
enum class ServiceState {
    /** Created, nothing started yet. */
    IDLE,

    /** Start requested; projection, models and overlay are being set up. */
    STARTING,

    /** Capturing and captioning. */
    RUNNING,

    /** Running, but the captured app is silent because it opted out of playback capture. */
    BLOCKED,

    /** Terminal: the service is shutting down or gone. */
    STOPPED,
}

/** Inputs that drive [ServiceStateMachine]. */
enum class ServiceEvent {
    Start,
    Ready,
    CaptureBlocked,
    CaptureAudible,
    ProjectionStopped,
    OverlayPermissionLost,
    UserStop,
}

/** Pure transition function for the service lifecycle; no Android dependencies. */
object ServiceStateMachine {
    /**
     * Returns the state after [event] in [current]. [ServiceEvent.ProjectionStopped],
     * [ServiceEvent.OverlayPermissionLost] and [ServiceEvent.UserStop] stop the service from any
     * state; [ServiceState.STOPPED] is terminal; events that make no sense in a state are ignored.
     */
    fun next(current: ServiceState, event: ServiceEvent): ServiceState {
        if (current == ServiceState.STOPPED) return current
        return when (event) {
            ServiceEvent.ProjectionStopped,
            ServiceEvent.OverlayPermissionLost,
            ServiceEvent.UserStop -> ServiceState.STOPPED
            ServiceEvent.Start -> if (current == ServiceState.IDLE) ServiceState.STARTING else current
            ServiceEvent.Ready -> if (current == ServiceState.STARTING) ServiceState.RUNNING else current
            ServiceEvent.CaptureBlocked -> if (current == ServiceState.RUNNING) ServiceState.BLOCKED else current
            ServiceEvent.CaptureAudible -> if (current == ServiceState.BLOCKED) ServiceState.RUNNING else current
        }
    }
}
