package com.voicecaption.ui.state

/** Everything the app needs before captions can start. */
data class ReadinessInputs(
    val overlayGranted: Boolean,
    val notificationsGranted: Boolean,
    val recordAudioGranted: Boolean,
    val modelInstalled: Boolean,
    val translationReady: Boolean,
)

/** The next thing onboarding should ask for, in order. */
enum class SetupStep { OVERLAY_PERMISSION, NOTIFICATION_PERMISSION, RECORD_AUDIO_PERMISSION, DOWNLOADS, READY }

/** Pure setup-progress rules. */
object ReadinessChecker {
    /** The first missing step: overlay, notifications, record audio, downloads, else [SetupStep.READY]. */
    fun nextStep(inputs: ReadinessInputs): SetupStep = when {
        !inputs.overlayGranted -> SetupStep.OVERLAY_PERMISSION
        !inputs.notificationsGranted -> SetupStep.NOTIFICATION_PERMISSION
        !inputs.recordAudioGranted -> SetupStep.RECORD_AUDIO_PERMISSION
        !inputs.modelInstalled || !inputs.translationReady -> SetupStep.DOWNLOADS
        else -> SetupStep.READY
    }

    /**
     * Whether "Start captions" may be enabled. Notifications are NOT required (the service runs
     * without them); a download in progress or failed blocks starting.
     */
    fun canStart(inputs: ReadinessInputs, download: DownloadState): Boolean =
        inputs.overlayGranted &&
            inputs.recordAudioGranted &&
            inputs.modelInstalled &&
            inputs.translationReady &&
            (download is DownloadState.Idle || download is DownloadState.Done)
}
