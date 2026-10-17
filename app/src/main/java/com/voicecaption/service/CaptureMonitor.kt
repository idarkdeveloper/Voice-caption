package com.voicecaption.service

import com.voicecaption.audio.CaptureBlockedDetector
import com.voicecaption.core.PcmFrame

/**
 * Pure glue between the per-frame checks and [ServiceStateMachine]: it owns the current
 * [ServiceState], turns detector output into events and applies externally dispatched ones.
 *
 * [onFrame] checks, in order: the overlay permission (`overlayAllowed() == false` dispatches
 * [ServiceEvent.OverlayPermissionLost]), then the blocked detector. The permission check is a
 * binder call, so it runs on the first frame and then at most once per [OVERLAY_CHECK_INTERVAL_MS]
 * of frame time (not wall clock); a revocation is therefore noticed within that interval. [ServiceEvent.CaptureBlocked]
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
    private var lastOverlayCheckMs: Long? = null

    /** Current lifecycle state. */
    val state: ServiceState get() = synchronized(lock) { _state }

    /** Applies [event] to the state machine and returns the new state. */
    fun dispatch(event: ServiceEvent): ServiceState = synchronized(lock) { apply(event) }

    /** Feeds one frame and returns the resulting state. */
    fun onFrame(frame: PcmFrame): ServiceState = synchronized(lock) {
        if (_state == ServiceState.STOPPED) return _state
        val last = lastOverlayCheckMs
        if (last == null || frame.timestampMs - last >= OVERLAY_CHECK_INTERVAL_MS) {
            lastOverlayCheckMs = frame.timestampMs
            if (!overlayAllowed()) return apply(ServiceEvent.OverlayPermissionLost)
        }
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

    companion object {
        /** Minimum frame-time gap between two overlay permission checks. */
        const val OVERLAY_CHECK_INTERVAL_MS = 500L
    }
}
