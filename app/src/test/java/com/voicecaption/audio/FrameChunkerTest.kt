package com.voicecaption.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChunkerTest {
    private fun shorts(n: Int, value: Short = 0) = ShortArray(n) { value }

    @Test fun `1024 shorts yield two frames at 0 and 32 ms`() {
        val frames = FrameChunker().push(shorts(1024), 1024)
        assertEquals(2, frames.size)
        assertEquals(listOf(0L, 32L), frames.map { it.timestampMs })
        assertTrue(frames.all { it.samples.size == 512 })
    }

    @Test fun `leftover is carried across pushes`() {
        val c = FrameChunker()
        assertEquals(0, c.push(shorts(300), 300).size)
        val second = c.push(shorts(300), 300)
        assertEquals(listOf(0L), second.map { it.timestampMs }) // 600 total -> 1 frame, 88 left
        val third = c.push(shorts(424), 424) // 88 + 424 = 512 -> exactly 1 frame
        assertEquals(listOf(32L), third.map { it.timestampMs })
    }

    @Test fun `carried samples keep their order`() {
        val c = FrameChunker()
        val a = ShortArray(300) { it.toShort() }
        val b = ShortArray(300) { (300 + it).toShort() }
        c.push(a, 300)
        val frame = c.push(b, 300).single()
        for (i in 0 until 512) assertEquals(i / 32768f, frame.samples[i], 0f)
    }

    @Test fun `converts samples to floats`() {
        val pcm = shorts(512)
        pcm[0] = 32767
        pcm[1] = -32768
        pcm[2] = 0
        val s = FrameChunker().push(pcm, 512).single().samples
        assertEquals(1.0f, s[0], 1e-4f)
        assertEquals(-1.0f, s[1], 0f)
        assertEquals(0f, s[2], 0f)
    }

    @Test fun `length smaller than array consumes only length samples`() {
        val c = FrameChunker()
        val pcm = shorts(1024, 100)
        assertEquals(0, c.push(pcm, 500).size)
        // 500 pushed so far; 12 more complete the first frame, nothing beyond.
        assertEquals(1, c.push(pcm, 12).size)
        assertEquals(0, c.push(pcm, 0).size)
    }

    @Test fun `reset clears leftovers and restarts timestamps`() {
        val c = FrameChunker()
        c.push(shorts(1024 + 100), 1124)
        c.reset()
        assertEquals(0, c.push(shorts(500), 500).size) // leftover of 100 was dropped
        val f = c.push(shorts(12), 12)
        assertEquals(listOf(0L), f.map { it.timestampMs })
    }
}
