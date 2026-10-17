package com.voicecaption.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide "is captioning running" flag, published by [CaptureService] and observed by the UI.
 * True once the capture pipeline is actually running; false again as soon as teardown begins.
 */
object CaptureStatus {
    private val state = MutableStateFlow(false)

    /** True while the capture pipeline is running. */
    val running: StateFlow<Boolean> = state.asStateFlow()

    internal fun set(running: Boolean) {
        state.value = running
    }
}
