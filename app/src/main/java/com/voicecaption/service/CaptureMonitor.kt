package com.voicecaption.service

import com.voicecaption.audio.CaptureBlockedDetector
import com.voicecaption.core.PcmFrame

/**
 * Pure glue between the per-frame checks and [ServiceStateMachine]: it owns the current
 * [ServiceState], turns detector output into events and applies externally dispatched ones.
 *
 * [onFrame] checks, in order: the overlay permission (`overlayAllowed() == false` dispatches
 * [ServiceEvent.OverlayPermissionLost]), then the blocked detector. [ServiceEvent.CaptureBlocked]
 * / [ServiceEvent.CaptureAudible] are dispatched only on transitions of the detector's verdict,
 * never repeated. Frames after [ServiceState.STOPPED] are ignored.
 *
 * Thread-safe: frames arrive on the audio thread while [dispatch] may come from the projection
 * callback or the UI, so everything is synchronized.
 */
class CaptureMonitor(
    private val detector: CaptureBlockedDetector,
    private val overlayAllowed: () -> Boolean,
) {
    private val lock = Any()
    private var _state = ServiceState.IDLE
    private var blocked = false

    /** Current lifecycle state. */
    val state: ServiceState get() = synchronized(lock) { _state }

    /** Applies [event] to the state machine and returns the new state. */
    fun dispatch(event: ServiceEvent): ServiceState = synchronized(lock) { apply(event) }

    /** Feeds one frame and returns the resulting state. */
    fun onFrame(frame: PcmFrame): ServiceState = synchronized(lock) {
        if (_state == ServiceState.STOPPED) return _state
        if (!overlayAllowed()) return apply(ServiceEvent.OverlayPermissionLost)
        val nowBlocked = detector.onFrame(frame)
        if (nowBlocked != blocked) {
            blocked = nowBlocked
            apply(if (nowBlocked) ServiceEvent.CaptureBlocked else ServiceEvent.CaptureAudible)
        }
        _state
    }

    private fun apply(event: ServiceEvent): ServiceState {
        _state = ServiceStateMachine.next(_state, event)
        return _state
    }
}
