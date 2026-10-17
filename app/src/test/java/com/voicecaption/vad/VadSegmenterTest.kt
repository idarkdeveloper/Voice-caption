package com.voicecaption.vad

import com.voicecaption.core.PcmFrame
import com.voicecaption.core.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSegmenterTest {
    private val frameSamples = 512
    private val frameMs = 32L

    /** Reports speech according to a script, one entry per frame; frames carry their index in sample 0. */
    private class FakeVad(private val script: BooleanArray) : VoiceActivityDetector {
        override fun isSpeech(frame: FloatArray): Boolean = script[frame[0].toInt()]
    }

    private fun script(vararg parts: Pair<Boolean, Int>): BooleanArray =
        parts.flatMap { (speech, n) -> List(n) { speech } }.toBooleanArray()

    private fun run(script: BooleanArray): Pair<VadSegmenter, List<SpeechSegment>> {
        val segmenter = VadSegmenter(FakeVad(script))
        val out = mutableListOf<SpeechSegment>()
        script.indices.forEach { i ->
            val samples = FloatArray(frameSamples).also { it[0] = i.toFloat() }
            out += segmenter.push(PcmFrame(samples, i * frameMs))
        }
        return segmenter to out
    }

    @Test
    fun silenceProducesNoSegments() {
        val (segmenter, out) = run(script(false to 100))
        assertTrue(out.isEmpty())
        assertNull(segmenter.flush())
    }

    @Test
    fun speechFollowedByHangoverClosesOneSegment() {
        val (_, out) = run(script(true to 50, false to 13))
        assertEquals(1, out.size)
        val seg = out[0]
        // startMs = first frame timestamp; endMs = last included frame timestamp + 32.
        // The 13 silent frames (416 ms >= 400 ms hangover) are included in the segment.
        assertEquals(0L, seg.startMs)
        assertEquals(63 * frameMs, seg.endMs)
        assertEquals(63 * frameSamples, seg.samples.size)
        assertTrue(kotlin.math.abs((seg.endMs - seg.startMs) - 2000L) <= frameMs)
    }

    @Test
    fun segmentStartsAtFirstSpeechFrame() {
        val (_, out) = run(script(false to 10, true to 20, false to 13))
        assertEquals(1, out.size)
        assertEquals(10 * frameMs, out[0].startMs)
        assertEquals(43 * frameMs, out[0].endMs)
    }

    @Test
    fun blipShorterThanMinSpeechIsDropped() {
        val (segmenter, out) = run(script(true to 5, false to 20))
        assertTrue(out.isEmpty())
        assertNull(segmenter.flush())
    }

    @Test
    fun continuousSpeechIsForceCutAt6sWithOverlap() {
        val (segmenter, emitted) = run(script(true to 250))
        // Only the forced cut has fired so far; the remainder (overlap + 62 frames) is pending.
        assertEquals(1, emitted.size)
        val out = emitted + segmenter.flush()!!
        assertEquals(2, out.size)
        val first = out[0]
        // Cut happens on the first frame that brings the buffer to >= 6000 ms: 188 frames = 6016 ms.
        assertEquals(0L, first.startMs)
        assertEquals(188 * frameMs, first.endMs)
        assertTrue(first.endMs - first.startMs - 6000L < frameMs)
        assertEquals(first.endMs - 500, out[1].startMs)
        // Overlap audio (500 ms = 8000 samples) is the tail of the first segment.
        assertTrue(first.samples.takeLast(8000) == out[1].samples.take(8000))
        assertEquals(250 * frameMs, out[1].endMs)
    }

    @Test
    fun flushEmitsPendingSpeech() {
        val (segmenter, out) = run(script(true to 30))
        assertTrue(out.isEmpty())
        val seg = segmenter.flush()!!
        assertEquals(0L, seg.startMs)
        assertEquals(30 * frameMs, seg.endMs)
        assertNull(segmenter.flush())
    }

    @Test
    fun flushAfterForcedCutDoesNotReEmitOnlyOverlap() {
        val (segmenter, out) = run(script(true to 188))
        assertEquals(1, out.size)
        assertNull(segmenter.flush())
    }

    @Test
    fun segmentsAfterCloseStartFreshWithoutOverlap() {
        val (_, out) = run(script(true to 20, false to 13, true to 20, false to 13))
        assertEquals(2, out.size)
        assertEquals(33 * frameMs, out[1].startMs)
    }

    @Test
    fun speechlessContinuationAfterForcedCutIsDropped() {
        val (segmenter, out) = run(script(true to 188, false to 13))
        assertEquals(1, out.size)
        assertEquals(188 * frameMs, out[0].endMs)
        assertNull(segmenter.flush())
    }

    @Test
    fun continuationWithSpeechAfterCutStillEmits() {
        val (segmenter, out) = run(script(true to 250, false to 13))
        assertEquals(2, out.size)
        assertEquals(188 * frameMs - 500, out[1].startMs)
        assertEquals(263 * frameMs, out[1].endMs)
        assertNull(segmenter.flush())
    }

    @Test
    fun flushDropsBlipShorterThanMinSpeech() {
        val (segmenter, out) = run(script(true to 5))
        assertTrue(out.isEmpty())
        assertNull(segmenter.flush())
    }
}
