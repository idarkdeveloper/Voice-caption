package com.voicecaption.service

/** Why the service cannot start. */
enum class StartupProblem { OVERLAY_PERMISSION, RECORD_AUDIO, SOURCE_LANGUAGE, MODEL_MISSING }

/** Outcome of [StartupCheck.evaluate]. */
sealed interface StartupResult {
    data object Ok : StartupResult
    data class Failed(val problem: StartupProblem) : StartupResult
}

/** Pure start-up preconditions of the caption service. */
object StartupCheck {
    /**
     * Returns the first failing precondition, in the order overlay permission, RECORD_AUDIO,
     * source language (blank or "auto" is not a concrete language and must never reach the
     * translator), speech model installed; [StartupResult.Ok] when all hold.
     */
    fun evaluate(
        overlayAllowed: Boolean,
        recordAudioGranted: Boolean,
        modelInstalled: Boolean,
        sourceLang: String,
    ): StartupResult {
        val problem = when {
            !overlayAllowed -> StartupProblem.OVERLAY_PERMISSION
            !recordAudioGranted -> StartupProblem.RECORD_AUDIO
            sourceLang.isBlank() || sourceLang.trim().equals("auto", ignoreCase = true) -> StartupProblem.SOURCE_LANGUAGE
            !modelInstalled -> StartupProblem.MODEL_MISSING
            else -> return StartupResult.Ok
        }
        return StartupResult.Failed(problem)
    }
}
