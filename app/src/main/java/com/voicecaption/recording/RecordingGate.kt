package com.voicecaption.recording

/**
 * Decides which recorded samples are kept so the video contains only what the user was watching,
 * not TransCap's own screen at the start (pressing Start) or at the end (pressing Stop). While the
 * TransCap app is on screen nothing is written; when it leaves the screen, writing starts at the
 * next video key frame. Times are shifted so the pieces join without a gap. Pure and thread-confined:
 * call it under the recorder's lock.
 */
class RecordingGate(private val frameUs: Long = 33_333L) {
    sealed interface VideoAction {
        /** Skip this frame; [requestKeyFrame] asks the encoder for a key frame so writing can begin. */
        data class Drop(val requestKeyFrame: Boolean) : VideoAction

        /** Write this frame at [outUs] microseconds in the file. */
        data class Write(val outUs: Long) : VideoAction
    }

    private enum class State { WAITING, WRITING, PAUSED }

    private var state = State.WAITING
    private var baseUs = 0L
    private var shiftUs = 0L
    private var lastVideoOutUs = 0L

    /** [rawUs] is the encoder's time; [hidden] is true while the TransCap app is NOT on screen. */
    fun onVideo(rawUs: Long, keyFrame: Boolean, hidden: Boolean): VideoAction {
        if (!hidden) {
            if (state == State.WRITING) state = State.PAUSED
            return VideoAction.Drop(requestKeyFrame = false)
        }
        if (state == State.WRITING) {
            val out = maxOf(rawUs - baseUs - shiftUs, lastVideoOutUs)
            lastVideoOutUs = out
            return VideoAction.Write(out)
        }
        if (!keyFrame) return VideoAction.Drop(requestKeyFrame = true)
        val out = if (state == State.WAITING) {
            baseUs = rawUs
            shiftUs = 0
            0L
        } else {
            // Resuming: carry on right after the last written frame, hiding the pause.
            val next = lastVideoOutUs + frameUs
            shiftUs = rawUs - baseUs - next
            next
        }
        state = State.WRITING
        lastVideoOutUs = out
        return VideoAction.Write(out)
    }

    /** Audio follows the video: kept only while writing, on the same shifted clock. Null = skip. */
    fun onAudio(rawUs: Long, hidden: Boolean): Long? {
        if (!hidden || state != State.WRITING) return null
        return (rawUs - baseUs - shiftUs).coerceAtLeast(0)
    }

    /** True once at least one frame was written. */
    val hasStarted: Boolean get() = state != State.WAITING
}
