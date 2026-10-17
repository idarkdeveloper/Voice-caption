package com.voicecaption.vad

import com.voicecaption.core.PcmFrame
import com.voicecaption.core.SpeechSegment

data class VadConfig(
    val frameMs: Int = 32,
    val hangoverMs: Int = 400,
    val minSpeechMs: Int = 300,
    val maxSegmentMs: Int = 6000,
    val overlapMs: Int = 500,
)

/**
 * Turns a stream of 32 ms frames into speech segments (IDLE / IN_SPEECH state machine).
 *
 * Timestamps: [SpeechSegment.startMs] is the timestamp of the first frame included (for a
 * segment that follows a forced cut, the start of the carried overlap audio, i.e. the previous
 * segment's `endMs - overlapMs`). [SpeechSegment.endMs] is the end of the last included frame
 * (that frame's timestamp + [VadConfig.frameMs]).
 *
 * A segment closes when trailing silence reaches [VadConfig.hangoverMs]; the hangover frames
 * are included in the segment. Segments with less than [VadConfig.minSpeechMs] of speech frames
 * are dropped. If a segment's buffered audio reaches [VadConfig.maxSegmentMs] it is cut after
 * the frame that reaches it (so lengths overshoot by less than one frame), and the last
 * [VadConfig.overlapMs] of samples seed the next segment. Overlap is carried only across forced
 * cuts. A continuation segment is exempt from minSpeech, but is dropped (on close and on flush)
 * unless at least one speech frame arrived after the cut, since otherwise it holds only the
 * already-emitted overlap plus silence.
 *
 * Not thread-safe.
 */
class VadSegmenter(
    private val vad: VoiceActivityDetector,
    private val config: VadConfig = VadConfig(),
) {
    private var buffer = FloatArray(0)
    private var size = 0
    private var inSpeech = false
    private var startMs = 0L
    private var endMs = 0L
    private var speechFrames = 0
    private var silenceFrames = 0
    private var newFrames = 0 // frames added since segment start, excluding carried overlap
    private var continuation = false

    private val samplesPerMs = SAMPLE_RATE / 1000
    private val maxSamples = config.maxSegmentMs * samplesPerMs
    private val overlapSamples = config.overlapMs * samplesPerMs

    fun push(frame: PcmFrame): List<SpeechSegment> {
        val speech = vad.isSpeech(frame.samples)
        if (!inSpeech) {
            if (!speech) return emptyList()
            inSpeech = true
            startMs = frame.timestampMs
            continuation = false
            speechFrames = 0
            silenceFrames = 0
            newFrames = 0
            size = 0
        }
        append(frame.samples)
        endMs = frame.timestampMs + config.frameMs
        newFrames++
        if (speech) {
            speechFrames++
            silenceFrames = 0
        } else {
            silenceFrames++
        }

        val out = ArrayList<SpeechSegment>(1)
        if (silenceFrames * config.frameMs >= config.hangoverMs) {
            emitIfValid()?.let(out::add)
            reset()
        } else if (size >= maxSamples) {
            emitIfValid()?.let(out::add)
            carryOverlap()
        }
        return out
    }

    /** Emits any pending speech (end of stream) and returns to idle; null if nothing is pending. */
    fun flush(): SpeechSegment? {
        val seg = if (inSpeech) emitIfValid() else null
        reset()
        return seg
    }

    private fun emitIfValid(): SpeechSegment? {
        if (newFrames == 0) return null
        // A continuation needs speech seen after the cut; otherwise it is only already-emitted overlap.
        if (continuation) {
            if (speechFrames == 0) return null
        } else if (speechFrames * config.frameMs < config.minSpeechMs) {
            return null
        }
        return SpeechSegment(buffer.copyOf(size), startMs, endMs)
    }

    private fun carryOverlap() {
        val keep = minOf(overlapSamples, size)
        System.arraycopy(buffer, size - keep, buffer, 0, keep)
        size = keep
        startMs = endMs - keep / samplesPerMs
        continuation = true
        speechFrames = 0
        newFrames = 0
        // silenceFrames is kept: a cut during hangover must still close on time.
    }

    private fun reset() {
        inSpeech = false
        size = 0
        speechFrames = 0
        silenceFrames = 0
        newFrames = 0
        continuation = false
    }

    private fun append(samples: FloatArray) {
        if (size + samples.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + samples.size, maxSamples + samples.size))
        }
        System.arraycopy(samples, 0, buffer, size, samples.size)
        size += samples.size
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
