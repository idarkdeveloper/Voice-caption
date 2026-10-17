package com.voicecaption.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStatusTest {
    private val ready = MainUiState(
        overlayGranted = true,
        notificationsGranted = true,
        recordAudioGranted = true,
        modelInstalled = true,
        translationReady = true,
        nextStep = SetupStep.READY,
        canStart = true,
    )

    private fun downloads(download: DownloadState) = ready.copy(
        modelInstalled = false,
        nextStep = SetupStep.DOWNLOADS,
        canStart = false,
        download = download,
    )

    @Test
    fun runningWinsEvenWhenEverythingElseIsFalse() {
        assertEquals(HomeStatus.LISTENING, homeStatus(MainUiState(running = true)))
        assertEquals(HomeStatus.LISTENING, homeStatus(ready.copy(running = true)))
        assertEquals(HomeStatus.LISTENING, homeStatus(downloads(DownloadState.Failed("x")).copy(running = true)))
    }

    @Test
    fun permissionStepsNeedSetup() {
        listOf(SetupStep.OVERLAY_PERMISSION, SetupStep.NOTIFICATION_PERMISSION, SetupStep.RECORD_AUDIO_PERMISSION)
            .forEach { assertEquals(HomeStatus.NEEDS_SETUP, homeStatus(MainUiState(nextStep = it))) }
    }

    @Test
    fun downloadStatesMapOneToOne() {
        assertEquals(HomeStatus.DOWNLOAD_NEEDED, homeStatus(downloads(DownloadState.Idle)))
        assertEquals(HomeStatus.DOWNLOADING, homeStatus(downloads(DownloadState.Downloading(0.3f, "Downloading speech model"))))
        assertEquals(HomeStatus.DOWNLOAD_FAILED, homeStatus(downloads(DownloadState.Failed("No internet connection"))))
    }

    @Test
    fun doneWhileReadinessIsStillRecomputedCountsAsDownloading() {
        assertEquals(HomeStatus.DOWNLOADING, homeStatus(downloads(DownloadState.Done)))
    }

    @Test
    fun readyWhenAllSetAndCanStart() = assertEquals(HomeStatus.READY, homeStatus(ready))

    @Test
    fun readyStepWithoutCanStartIsNotReady() {
        assertEquals(HomeStatus.NEEDS_SETUP, homeStatus(ready.copy(canStart = false)))
    }

    @Test
    fun skippedNotificationsDoNotBlockHome() {
        val skipped = ready.copy(notificationsGranted = false, nextStep = SetupStep.NOTIFICATION_PERMISSION)
        assertEquals(HomeStatus.READY, homeStatus(skipped))
        val needsModel = skipped.copy(modelInstalled = false, canStart = false)
        assertEquals(HomeStatus.DOWNLOAD_NEEDED, homeStatus(needsModel))
    }

    @Test
    fun notificationStepStaysSetupWhileOverlayOrAudioMissing() {
        val s = MainUiState(overlayGranted = true, nextStep = SetupStep.NOTIFICATION_PERMISSION)
        assertEquals(HomeStatus.NEEDS_SETUP, homeStatus(s))
    }

    @Test
    fun effectiveStepHonorsTheSkipFlag() {
        val s = MainUiState(overlayGranted = true, nextStep = SetupStep.NOTIFICATION_PERMISSION)
        assertEquals(SetupStep.NOTIFICATION_PERMISSION, effectiveSetupStep(s, notificationsSkipped = false))
        assertEquals(SetupStep.RECORD_AUDIO_PERMISSION, effectiveSetupStep(s, notificationsSkipped = true))
        val withAudio = s.copy(recordAudioGranted = true)
        assertEquals(SetupStep.DOWNLOADS, effectiveSetupStep(withAudio, notificationsSkipped = true))
        assertEquals(SetupStep.READY, effectiveSetupStep(withAudio.copy(modelInstalled = true, translationReady = true), true))
    }

    @Test
    fun onboardingFollowsStepsIntroFirst() {
        val fresh = MainUiState()
        assertEquals(OnboardingStep.INTRO, onboardingStep(fresh, introDone = false, notificationsSkipped = false))
        assertEquals(OnboardingStep.OVERLAY, onboardingStep(fresh, introDone = true, notificationsSkipped = false))
        val notif = MainUiState(overlayGranted = true, nextStep = SetupStep.NOTIFICATION_PERMISSION)
        assertEquals(OnboardingStep.NOTIFICATIONS, onboardingStep(notif, introDone = false, notificationsSkipped = false))
        assertEquals(OnboardingStep.MICROPHONE, onboardingStep(notif, introDone = true, notificationsSkipped = true))
        val mic = notif.copy(notificationsGranted = true, nextStep = SetupStep.RECORD_AUDIO_PERMISSION)
        assertEquals(OnboardingStep.MICROPHONE, onboardingStep(mic, introDone = false, notificationsSkipped = false))
        assertEquals(null, onboardingStep(ready, introDone = false, notificationsSkipped = false))
        assertEquals(null, onboardingStep(downloads(DownloadState.Idle), introDone = false, notificationsSkipped = false))
    }
}
