package com.voicecaption.core

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeLoadTest {
    @Test fun `returns the block result`() = assertEquals(7, wrapNativeLoad("X") { 7 })

    @Test fun `link errors become IllegalStateException with cause`() {
        val cause = UnsatisfiedLinkError("no libsherpa")
        try {
            wrapNativeLoad("Silero VAD") { throw cause }
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("Failed to initialise Silero VAD", e.message)
            assertSame(cause, e.cause)
        }
    }

    @Test fun `exceptions become IllegalStateException`() {
        try {
            wrapNativeLoad("X") { throw RuntimeException("bad model") }
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.cause is RuntimeException)
        }
    }

    @Test fun `cancellation passes through`() {
        try {
            wrapNativeLoad("X") { throw CancellationException("c") }
            fail()
        } catch (e: CancellationException) {
            assertEquals("c", e.message)
        }
    }
}
