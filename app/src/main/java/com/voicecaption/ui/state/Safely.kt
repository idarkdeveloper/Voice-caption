package com.voicecaption.ui.state

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block]; a non-cancellation [Exception] is passed to [onError] and [default] is returned.
 * [CancellationException] is rethrown so cancellation keeps working.
 */
internal inline fun <T> safely(default: T, onError: (Throwable) -> Unit, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
        default
    }
