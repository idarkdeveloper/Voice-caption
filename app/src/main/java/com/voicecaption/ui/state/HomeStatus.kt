package com.voicecaption.ui.state

/** What the Home status line says. The Android layer maps each value to a string, icon and text. */
enum class HomeStatus { NEEDS_SETUP, DOWNLOAD_NEEDED, DOWNLOADING, DOWNLOAD_FAILED, READY, LISTENING }

/** The screens shown while the first-run permission steps are incomplete. */
enum class OnboardingStep { INTRO, OVERLAY, NOTIFICATIONS, MICROPHONE }

/**
 * [MainUiState.nextStep] with the optional notification step taken out of the way.
 *
 * Notifications are optional (`canStart` ignores them), so `nextStep` stays NOTIFICATION_PERMISSION
 * for a user who declined them. That step is treated as done when [notificationsSkipped] is set or
 * when overlay and microphone are already granted; the next missing step is then reported instead.
 */
fun effectiveSetupStep(state: MainUiState, notificationsSkipped: Boolean = false): SetupStep {
    if (state.nextStep != SetupStep.NOTIFICATION_PERMISSION) return state.nextStep
    val optionalStepDone = notificationsSkipped || (state.overlayGranted && state.recordAudioGranted)
    return when {
        !optionalStepDone -> SetupStep.NOTIFICATION_PERMISSION
        !state.recordAudioGranted -> SetupStep.RECORD_AUDIO_PERMISSION
        !state.modelInstalled || !state.translationReady -> SetupStep.DOWNLOADS
        else -> SetupStep.READY
    }
}

/**
 * The onboarding screen to show, or null when onboarding is over (Home is shown instead).
 * [introDone] only matters while the overlay step is next: the intro comes first.
 */
fun onboardingStep(state: MainUiState, introDone: Boolean, notificationsSkipped: Boolean): OnboardingStep? =
    when (effectiveSetupStep(state, notificationsSkipped)) {
        SetupStep.OVERLAY_PERMISSION -> if (introDone) OnboardingStep.OVERLAY else OnboardingStep.INTRO
        SetupStep.NOTIFICATION_PERMISSION -> OnboardingStep.NOTIFICATIONS
        SetupStep.RECORD_AUDIO_PERMISSION -> OnboardingStep.MICROPHONE
        SetupStep.DOWNLOADS, SetupStep.READY -> null
    }

/**
 * Status line for Home. Precedence, first match wins:
 * 1. `running` -> LISTENING (even if other flags are false; the service owns the truth).
 * 2. A permission step is still open (see [effectiveSetupStep]) -> NEEDS_SETUP.
 * 3. A download is in flight -> DOWNLOADING; one failed -> DOWNLOAD_FAILED. `Done` while the
 *    readiness recomputation is still pending also counts as DOWNLOADING (it is about to finish).
 * 4. Models or the language pack are missing -> DOWNLOAD_NEEDED.
 * 5. Everything present and `canStart` -> READY, otherwise NEEDS_SETUP.
 */
fun homeStatus(state: MainUiState): HomeStatus {
    if (state.running) return HomeStatus.LISTENING
    val step = effectiveSetupStep(state)
    if (step != SetupStep.DOWNLOADS && step != SetupStep.READY) return HomeStatus.NEEDS_SETUP
    return when (state.download) {
        is DownloadState.Downloading -> HomeStatus.DOWNLOADING
        is DownloadState.Failed -> HomeStatus.DOWNLOAD_FAILED
        DownloadState.Done -> if (step == SetupStep.DOWNLOADS) HomeStatus.DOWNLOADING else readyOrSetup(state)
        DownloadState.Idle -> if (step == SetupStep.DOWNLOADS) HomeStatus.DOWNLOAD_NEEDED else readyOrSetup(state)
    }
}

private fun readyOrSetup(state: MainUiState) = if (state.canStart) HomeStatus.READY else HomeStatus.NEEDS_SETUP
