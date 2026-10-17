package com.voicecaption.service

import com.voicecaption.pipeline.PipelineError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerFailureTrackerTest {
    private val boom = RuntimeException("boom")
    private fun fail(successesBefore: Long = 0) = PipelineError.Recognizer(boom, successesBefore)

    @Test fun `two failures are tolerated`() {
        val t = RecognizerFailureTracker()
        assertFalse(t.onError(fail()))
        assertFalse(t.onError(fail()))
    }

    @Test fun `three consecutive failures give up exactly once`() {
        val t = RecognizerFailureTracker()
        assertFalse(t.onError(fail()))
        assertFalse(t.onError(fail()))
        assertTrue(t.onError(fail()))
        assertFalse(t.onError(fail()))
    }

    @Test fun `a success between failures resets the count`() {
        val t = RecognizerFailureTracker()
        assertFalse(t.onError(fail(0)))
        assertFalse(t.onError(fail(0)))
        assertFalse(t.onError(fail(1))) // one segment succeeded in between
        assertFalse(t.onError(fail(1)))
        assertTrue(t.onError(fail(1)))
    }

    @Test fun `other errors are ignored`() {
        val t = RecognizerFailureTracker()
        repeat(10) {
            assertFalse(t.onError(PipelineError.Translator(boom)))
            assertFalse(t.onError(PipelineError.TranslatorNotReady))
        }
        assertFalse(t.onError(fail()))
        assertFalse(t.onError(fail()))
        assertTrue(t.onError(fail()))
    }
}
