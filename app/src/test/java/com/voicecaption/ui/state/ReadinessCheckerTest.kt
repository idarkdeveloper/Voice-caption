package com.voicecaption.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessCheckerTest {
    private val all = ReadinessInputs(
        overlayGranted = true,
        notificationsGranted = true,
        recordAudioGranted = true,
        modelInstalled = true,
        translationReady = true,
    )

    @Test
    fun allPresentIsReady() = assertEquals(SetupStep.READY, ReadinessChecker.nextStep(all))

    @Test
    fun overlayComesFirst() {
        val none = ReadinessInputs(false, false, false, false, false)
        assertEquals(SetupStep.OVERLAY_PERMISSION, ReadinessChecker.nextStep(none))
        assertEquals(SetupStep.OVERLAY_PERMISSION, ReadinessChecker.nextStep(all.copy(overlayGranted = false)))
    }

    @Test
    fun notificationsAfterOverlay() {
        val s = all.copy(notificationsGranted = false, recordAudioGranted = false, modelInstalled = false)
        assertEquals(SetupStep.NOTIFICATION_PERMISSION, ReadinessChecker.nextStep(s))
    }

    @Test
    fun recordAudioAfterNotifications() {
        val s = all.copy(recordAudioGranted = false, modelInstalled = false, translationReady = false)
        assertEquals(SetupStep.RECORD_AUDIO_PERMISSION, ReadinessChecker.nextStep(s))
    }

    @Test
    fun missingModelOrTranslationMeansDownloads() {
        assertEquals(SetupStep.DOWNLOADS, ReadinessChecker.nextStep(all.copy(modelInstalled = false)))
        assertEquals(SetupStep.DOWNLOADS, ReadinessChecker.nextStep(all.copy(translationReady = false)))
        assertEquals(
            SetupStep.DOWNLOADS,
            ReadinessChecker.nextStep(all.copy(modelInstalled = false, translationReady = false)),
        )
    }

    @Test
    fun canStartWhenEverythingPresentAndIdleOrDone() {
        assertTrue(ReadinessChecker.canStart(all, DownloadState.Idle))
        assertTrue(ReadinessChecker.canStart(all, DownloadState.Done))
    }

    @Test
    fun canStartDoesNotRequireNotifications() {
        assertTrue(ReadinessChecker.canStart(all.copy(notificationsGranted = false), DownloadState.Idle))
    }

    @Test
    fun canStartFalseWhenAnyRequirementMissing() {
        assertFalse(ReadinessChecker.canStart(all.copy(overlayGranted = false), DownloadState.Idle))
        assertFalse(ReadinessChecker.canStart(all.copy(recordAudioGranted = false), DownloadState.Idle))
        assertFalse(ReadinessChecker.canStart(all.copy(modelInstalled = false), DownloadState.Idle))
        assertFalse(ReadinessChecker.canStart(all.copy(translationReady = false), DownloadState.Idle))
    }

    @Test
    fun canStartFalseWhileDownloadingOrFailed() {
        assertFalse(ReadinessChecker.canStart(all, DownloadState.Downloading(0.5f, "x")))
        assertFalse(ReadinessChecker.canStart(all, DownloadState.Failed("No internet connection")))
    }
}
