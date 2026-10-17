package com.voicecaption.ui.state

import com.voicecaption.core.CaptionSettings
import com.voicecaption.core.ModelSize
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSettingsStore(initial: CaptionSettings = CaptionSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<CaptionSettings> = state
    override suspend fun update(transform: (CaptionSettings) -> CaptionSettings) {
        state.value = transform(state.value)
    }
}

private class FakeReadiness(private val installer: FakeInstaller, private val translator: FakeTranslator) :
    ReadinessSource {
    val deleted = mutableListOf<ModelSize>()
    override suspend fun isModelInstalled(size: ModelSize) = installer.isInstalled(size)
    override suspend fun isTranslationReady(source: String, target: String) = translator.isReady(source, target)
    override suspend fun deleteModel(size: ModelSize): Boolean {
        deleted += size
        return installer.installed.remove(size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val installer = FakeInstaller(mutableSetOf(ModelSize.BASE))
    private val translator = FakeTranslator()
    private val readiness = FakeReadiness(installer, translator)
    private val store = FakeSettingsStore()
    private val running = MutableStateFlow(false)
    private val granted = PermissionState(overlay = true, notifications = true, recordAudio = true)

    private fun TestScope.vm(): MainViewModel {
        val downloads = DownloadController(installer, translator, backgroundScope)
        return MainViewModel(store, readiness, downloads, running, backgroundScope).also {
            it.refreshPermissions(granted)
        }
    }

    @Test
    fun readyWhenEverythingPresent() = runTest {
        val vm = vm()
        runCurrent()
        val s = vm.uiState.value
        assertEquals(SetupStep.READY, s.nextStep)
        assertTrue(s.canStart)
        assertEquals(setOf(ModelSize.BASE), s.installedModels)
    }

    @Test
    fun notReadyBeforeFirstReadinessCheckOrPermissions() = runTest {
        val vm = vm()
        vm.refreshPermissions(PermissionState())
        runCurrent()
        assertFalse(vm.uiState.value.canStart)
        assertEquals(SetupStep.OVERLAY_PERMISSION, vm.uiState.value.nextStep)
    }

    @Test
    fun selectingUninstalledModelNeedsDownloads() = runTest {
        val vm = vm()
        runCurrent()
        vm.onModelSize(ModelSize.SMALL)
        runCurrent()
        val s = vm.uiState.value
        assertEquals(ModelSize.SMALL, s.settings.modelSize)
        assertFalse(s.modelInstalled)
        assertEquals(SetupStep.DOWNLOADS, s.nextStep)
        assertFalse(s.canStart)
    }

    @Test
    fun newLanguagePairWithoutPackBlocksStartUntilDownloaded() = runTest {
        val vm = vm()
        runCurrent()
        vm.onTargetLanguage("es")
        runCurrent()
        assertEquals("es", vm.uiState.value.settings.targetLang)
        assertFalse(vm.uiState.value.translationReady)
        assertFalse(vm.uiState.value.canStart)

        vm.startDownloads()
        runCurrent()
        assertEquals(DownloadState.Done, vm.uiState.value.download)
        assertTrue(vm.uiState.value.translationReady)
        assertTrue(vm.uiState.value.canStart)
    }

    @Test
    fun failedDownloadKeepsStartDisabledAndRetryRecovers() = runTest {
        installer.installed.clear()
        installer.behavior = { Result.failure(IOException("x")) }
        val vm = vm()
        runCurrent()
        vm.startDownloads()
        runCurrent()
        assertTrue(vm.uiState.value.download is DownloadState.Failed)
        assertFalse(vm.uiState.value.canStart)

        installer.behavior = { Result.success(java.io.File("m")) }
        vm.retryDownloads()
        runCurrent()
        assertTrue(vm.uiState.value.canStart)
    }

    @Test
    fun changingPairCancelsStaleDownloadState() = runTest {
        installer.installed.clear()
        installer.behavior = { Result.failure(IOException("x")) }
        val vm = vm()
        runCurrent()
        vm.startDownloads()
        runCurrent()
        assertTrue(vm.uiState.value.download is DownloadState.Failed)
        vm.onSourceLanguage("fr")
        runCurrent()
        assertEquals(DownloadState.Idle, vm.uiState.value.download)
    }

    @Test
    fun deleteModelRefusedWhileRunning() = runTest {
        val vm = vm()
        running.value = true
        runCurrent()
        vm.deleteModel(ModelSize.BASE)
        runCurrent()
        assertTrue(readiness.deleted.isEmpty())
        assertTrue(vm.uiState.value.modelInstalled)
        assertTrue(vm.uiState.value.running)
    }

    @Test
    fun deleteModelWhenStoppedRefreshesReadiness() = runTest {
        val vm = vm()
        runCurrent()
        vm.deleteModel(ModelSize.BASE)
        runCurrent()
        assertEquals(listOf(ModelSize.BASE), readiness.deleted)
        assertFalse(vm.uiState.value.modelInstalled)
        assertFalse(vm.uiState.value.canStart)
    }

    @Test
    fun unsupportedSourceLanguageIsIgnored() = runTest {
        val vm = vm()
        vm.onSourceLanguage("auto")
        vm.onSourceLanguage("zz")
        runCurrent()
        assertEquals("en", vm.uiState.value.settings.sourceLang)
    }

    @Test
    fun displaySettingsArePersisted() = runTest {
        val vm = vm()
        vm.onFontSize(24)
        vm.onOpacity(0.6f)
        vm.onBilingual(true)
        runCurrent()
        val s = vm.uiState.value.settings
        assertEquals(24, s.fontSizeSp)
        assertEquals(0.6f, s.opacity)
        assertTrue(s.bilingual)
    }
}

class ReduceUiStateTest {
    private val granted = PermissionState(true, true, true)

    @Test
    fun staleReadinessForOtherSettingsCountsAsNotReady() {
        val settings = CaptionSettings(targetLang = "es")
        val stale = ReadinessSnapshot(ModelSize.BASE, "en", "en", setOf(ModelSize.BASE), translationReady = true)
        val s = reduceUiState(settings, granted, stale, DownloadState.Idle, running = false)
        assertFalse(s.modelInstalled)
        assertFalse(s.translationReady)
        assertFalse(s.canStart)
    }

    @Test
    fun matchingReadinessIsUsed() {
        val settings = CaptionSettings()
        val fresh = ReadinessSnapshot(ModelSize.BASE, "en", "en", setOf(ModelSize.BASE), translationReady = true)
        val s = reduceUiState(settings, granted, fresh, DownloadState.Done, running = false)
        assertTrue(s.canStart)
        assertEquals(SetupStep.READY, s.nextStep)
    }
}
