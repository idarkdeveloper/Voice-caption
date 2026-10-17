package com.voicecaption.audio

import com.voicecaption.core.PcmFrame
import kotlin.math.sqrt

/**
 * Detects that playback capture is blocked (an app opted out of capture, so we receive pure
 * silence while media is actually playing).
 *
 * RMS is computed over [PcmFrame.samples]; an empty array counts as silent. A frame is audible
 * when its RMS is at least [silenceRms]. The silent window is measured in frame timestamps
 * ([PcmFrame.timestampMs]), counted from the last audible frame, or from the first frame seen
 * if none has been audible yet.
 *
 * [onFrame] returns true on the first frame where `timestampMs - lastAudibleOrStartMs >= windowMs`
 * and [isMediaPlaying] is true, and keeps returning true while that holds. It returns false again
 * as soon as an audible frame arrives. Whenever [isMediaPlaying] is false the silence clock
 * restarts from that frame, so media starting after a long pause is not instantly "blocked".
 *
 * Not thread-safe.
 */
class CaptureBlockedDetector(
    private val isMediaPlaying: () -> Boolean,
    private val silenceRms: Float = 0.001f,
    private val windowMs: Long = 5000,
) {
    private var silentSinceMs: Long? = null

    fun onFrame(frame: PcmFrame): Boolean {
        val now = frame.timestampMs
        if (rms(frame.samples) >= silenceRms || !isMediaPlaying()) {
            silentSinceMs = now
            return false
        }
        val since = silentSinceMs ?: now.also { silentSinceMs = it }
        return now - since >= windowMs
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        return sqrt(sum / samples.size).toFloat()
    }
}
