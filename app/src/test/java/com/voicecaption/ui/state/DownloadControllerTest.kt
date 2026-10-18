package com.voicecaption.ui.state

import com.voicecaption.core.ModelSize
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadControllerTest {
    private val installer = FakeInstaller()
    private val translator = FakeTranslator()

    private fun TestScope.controller() = DownloadController(installer, translator, backgroundScope)

    private fun TestScope.record(c: DownloadController): List<DownloadState> {
        val seen = mutableListOf<DownloadState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.state.collect { seen += it } }
        return seen
    }

    @Test
    fun happyPathHasMonotonicProgressThenDone() = runTest {
        val c = controller()
        val seen = record(c)
        c.start(ModelSize.BASE, "en", "es")
        runCurrent()

        assertEquals(DownloadState.Done, c.state.value)
        val progress = seen.filterIsInstance<DownloadState.Downloading>().map { it.progress }
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.all { it in 0f..1f })
        val labels = seen.filterIsInstance<DownloadState.Downloading>().map { it.label }
        assertTrue(labels.any { it.contains("speech model", ignoreCase = true) })
        assertTrue(labels.any { it.contains("language pack", ignoreCase = true) })
        assertEquals(1, installer.installCalls)
        assertEquals(1, translator.prepareCalls)
    }

    @Test
    fun installedModelIsNotDownloadedAgain() = runTest {
        installer.installed += ModelSize.BASE
        val c = controller()
        c.start(ModelSize.BASE, "en", "es")
        runCurrent()
        assertEquals(DownloadState.Done, c.state.value)
        assertEquals(0, installer.installCalls)
        assertEquals(1, translator.prepareCalls)
    }

    @Test
    fun sameLanguagePairNeedsNoPack() = runTest {
        val c = controller()
        c.start(ModelSize.BASE, "en", "en")
        runCurrent()
        assertEquals(DownloadState.Done, c.state.value)
        assertEquals(1, installer.installCalls)
        assertEquals(0, translator.prepareCalls)
    }

    @Test
    fun nothingToDownloadIsImmediatelyDone() = runTest {
        installer.installed += ModelSize.BASE
        val c = controller()
        c.start(ModelSize.BASE, "en", "en")
        runCurrent()
        assertEquals(DownloadState.Done, c.state.value)
        assertEquals(0, installer.installCalls + translator.prepareCalls)
    }

    @Test
    fun modelFailureFailsAndSkipsTranslator() = runTest {
        installer.behavior = { Result.failure(IOException("boom")) }
        val c = controller()
        c.start(ModelSize.BASE, "en", "es")
        runCurrent()
        val s = c.state.value
        assertTrue(s is DownloadState.Failed)
        val msg = (s as DownloadState.Failed).message
        assertTrue(msg.isNotBlank())
        assertFalse(msg.contains("Exception"))
        assertEquals(0, translator.prepareCalls)
    }

    @Test
    fun checksumMismatchHasFriendlyMessage() = runTest {
        installer.behavior = { Result.failure(IOException("Checksum mismatch for enc.onnx: expected a, got b")) }
        val c = controller()
        c.start(ModelSize.BASE, "en", "es")
        runCurrent()
        val msg = (c.state.value as DownloadState.Failed).message
        assertFalse(msg.contains("enc.onnx"))
        assertTrue(msg.contains("corrupt", ignoreCase = true))
    }

    @Test
    fun translatorFailureFails() = runTest {
        translator.behavior = { Result.failure(IllegalStateException("mlkit")) }
        val c = controller()
        c.start(ModelSize.BASE, "en", "es")
        runCurrent()
        assertTrue(c.state.value is DownloadState.Failed)
        assertFalse(translator.isReady("en", "es"))
    }

    @Test
    fun offlineGivesNoInternetMessage() = runTest {
        installer.behavior = { Result.failure(UnknownHostException("huggingface.co")) }
        val c = controller()
        c.start(ModelSize.BASE, "en", "es")
        runCurrent()
        assertEquals(DownloadState.Failed("No internet connection"), c.state.value)
    }

    @Test
    fun offlineDetectedThroughCauseChain() = runTest {
        translator.behavior = { Result.failure(IOException("wrapped", UnknownHostException("x"))) }
        installer.installed += ModelSize.BASE
        val c = controller()
        c.start(ModelSize.BASE, "en", "es")
        runCurrent()
        assertEquals(DownloadState.Failed("No internet connection"), c.state.value)
    }

    @Test
    fun retryAfterFailureSucceeds() = runTest {
        installer.behavior = { Result.failure(UnknownHostException()) }
        val c = controller()
        c.start(ModelSize.SMALL, "fr", "es")
        runCurrent()
        assertTrue(c.state.value is DownloadState.Failed)

        installer.behavior = { Result.success(File("m")) }
        c.retry()
        runCurrent()
        assertEquals(DownloadState.Done, c.state.value)
        assertTrue(ModelSize.SMALL in installer.installed)
        assertTrue(translator.isReady("fr", "es"))
    }

    @Test
    fun retryWithoutFailureIsIgnored() = runTest {
        val c = controller()
        c.retry()
        runCurrent()
        assertEquals(DownloadState.Idle, c.state.value)
        assertEquals(0, installer.installCalls)
    }

    @Test
    fun startWhileDownloadingIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        installer.behavior = { gate.await(); Result.success(File("m")) }
        val c = controller()
        c.start(ModelSize.BASE, "en", "es")
        runCurrent()
        assertTrue(c.state.value is DownloadState.Downloading)
        c.start(ModelSize.BASE, "en", "es")
        c.start(ModelSize.SMALL, "en", "fr")
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, installer.installCalls)
        assertEquals(DownloadState.Done, c.state.value)
    }

    @Test
    fun cancelMidDownloadReturnsToIdleWithoutFailure() = runTest {
        var cancelled = false
        installer.behavior = {
            try {
                CompletableDeferred<Unit>().await()
                Result.success(File("m"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
        val c = controller()
        val seen = record(c)
        c.start(ModelSize.BASE, "en", "es")
        runCurrent()
        c.cancel()
        runCurrent()

        assertTrue(cancelled)
        assertEquals(DownloadState.Idle, c.state.value)
        assertTrue(seen.none { it is DownloadState.Failed })
        assertEquals(0, translator.prepareCalls)
    }

    @Test
    fun restartAfterCancelNeverOverlapsInstalls() = runTest {
        var active = 0
        var maxActive = 0
        val gate = CompletableDeferred<Unit>()
        installer.behavior = {
            active++
            maxActive = maxOf(maxActive, active)
            try {
                gate.await()
                Result.success(File("m"))
            } finally {
                active--
            }
        }
        val c = controller()
        c.start(ModelSize.BASE, "en", "en")
        runCurrent()
        c.cancel()
        c.start(ModelSize.BASE, "en", "en")
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, maxActive)
        assertEquals(DownloadState.Done, c.state.value)
    }

    @Test
    fun startAfterFailureBeginsNewRun() = runTest {
        installer.behavior = { Result.failure(IOException("x")) }
        val c = controller()
        c.start(ModelSize.BASE, "en", "en")
        runCurrent()
        installer.behavior = { Result.success(File("m")) }
        c.start(ModelSize.BASE, "en", "en")
        runCurrent()
        assertEquals(DownloadState.Done, c.state.value)
    }

    @Test
    fun allLanguagesDownloadsEveryMissingPackExceptInstalledOnes() = runTest {
        translator.ready += "es" to "en"
        translator.ready += "fr" to "en"
        val c = controller()
        val seen = record(c)
        c.startAllLanguages()
        runCurrent()

        assertEquals(DownloadState.Done, c.state.value)
        val total = com.voicecaption.settings.SupportedLanguages.codes.size - 1 // all but English
        assertEquals(total - 2, translator.prepareCalls)
        assertEquals(0, installer.installCalls)
        val progress = seen.filterIsInstance<DownloadState.Downloading>().map { it.progress }
        assertEquals(progress.sorted(), progress)
        assertTrue(seen.filterIsInstance<DownloadState.Downloading>().any { it.label.contains("language packs") })
    }

    @Test
    fun allLanguagesStopsOnFailureAndRetryContinues() = runTest {
        var calls = 0
        translator.behavior = { progress ->
            calls++
            if (calls == 3) Result.failure(UnknownHostException("offline")) else { progress(true); progress(false); Result.success(Unit) }
        }
        val c = controller()
        c.startAllLanguages()
        runCurrent()
        assertTrue(c.state.value is DownloadState.Failed)
        assertEquals(3, translator.prepareCalls)

        c.retry()
        runCurrent()
        assertEquals(DownloadState.Done, c.state.value)
        // The two packs that succeeded before the failure are not fetched again.
        val total = com.voicecaption.settings.SupportedLanguages.codes.size - 1
        assertEquals(total + 1, translator.prepareCalls)
    }
}
