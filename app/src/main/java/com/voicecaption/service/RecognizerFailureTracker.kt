package com.voicecaption.service

import com.voicecaption.pipeline.PipelineError

/**
 * Decides when repeated speech-recognition failures mean the run is hopeless (a corrupt model,
 * persistent JNI errors) and should be ended with a visible message instead of silently showing
 * nothing forever.
 *
 * Counts CONSECUTIVE [PipelineError.Recognizer] events. The pipeline's success signal is the
 * `segmentsRecognizedBefore` count carried by each error: when it differs from the count seen at
 * the previous error, at least one segment was recognized in between and the streak restarts.
 * Other errors are ignored. [onError] returns true exactly once, when the streak reaches
 * [threshold]. Main-thread use only (not thread-safe).
 */
class RecognizerFailureTracker(private val threshold: Int = DEFAULT_THRESHOLD) {
    private var consecutive = 0
    private var lastSuccesses = 0L
    private var gaveUp = false

    /** Feeds one pipeline error; true means "give up now" (reported only once). */
    fun onError(error: PipelineError): Boolean {
        if (error !is PipelineError.Recognizer) return false
        if (error.segmentsRecognizedBefore != lastSuccesses) {
            lastSuccesses = error.segmentsRecognizedBefore
            consecutive = 0
        }
        consecutive++
        if (gaveUp || consecutive < threshold) return false
        gaveUp = true
        return true
    }

    companion object {
        /** Consecutive failed segments after which the run is ended. */
        const val DEFAULT_THRESHOLD = 3
    }
}
