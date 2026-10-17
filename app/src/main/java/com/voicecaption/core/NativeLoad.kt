package com.voicecaption.core

import kotlinx.coroutines.CancellationException

/**
 * Runs a native-library entry point ([block]) so every failure surfaces as an [Exception].
 * `UnsatisfiedLinkError` and other [LinkageError]s are `Error`s that ordinary `catch (e: Exception)`
 * handlers miss (the app would crash); both they and ordinary exceptions are rethrown as
 * `IllegalStateException("Failed to initialise <what>", cause)`. [CancellationException] passes through.
 */
inline fun <T> wrapNativeLoad(what: String, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw IllegalStateException("Failed to initialise $what", e)
} catch (e: LinkageError) {
    throw IllegalStateException("Failed to initialise $what", e)
}
