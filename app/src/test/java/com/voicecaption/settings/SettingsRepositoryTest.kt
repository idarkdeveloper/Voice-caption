package com.voicecaption.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voicecaption.core.CaptionSettings
import com.voicecaption.core.ModelSize
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(scope: CoroutineScope, file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    @Test
    fun defaultsEqualCaptionSettings() = runTest {
        val repo = SettingsRepository(store(backgroundScope, tmp.newFile("a.preferences_pb")))
        assertEquals(CaptionSettings(), repo.current())
    }

    @Test
    fun updateIsEmittedAndSurvivesNewRepository() = runTest {
        val file = tmp.newFile("b.preferences_pb")
        val scope = CoroutineScope(Job())
        val repo = SettingsRepository(store(scope, file))
        repo.update { it.copy(targetLang = "es", bilingual = true, modelSize = ModelSize.SMALL, fontSizeSp = 24, opacity = 0.9f, autoModel = false) }
        val expected = CaptionSettings(targetLang = "es", bilingual = true, modelSize = ModelSize.SMALL, fontSizeSp = 24, opacity = 0.9f, autoModel = false)
        assertEquals(expected, repo.settings.first())

        scope.cancel()
        scope.coroutineContext[Job]!!.join()

        val reopened = SettingsRepository(store(backgroundScope, file))
        assertEquals(expected, reopened.current())
    }

    @Test
    fun outOfRangeValuesAreValidatedOnRead() = runTest {
        val s = store(backgroundScope, tmp.newFile("c.preferences_pb"))
        s.edit {
            it[intPreferencesKey("font_size_sp")] = 99
            it[floatPreferencesKey("opacity")] = 0.1f
            it[stringPreferencesKey("source_lang")] = "xx"
            it[stringPreferencesKey("target_lang")] = "pt-BR"
            it[stringPreferencesKey("model_size")] = "HUGE"
        }
        val got = SettingsRepository(s).current()
        assertEquals(CaptionSettings(sourceLang = "en", targetLang = "pt", fontSizeSp = 32, opacity = 0.5f, modelSize = ModelSize.BASE), got)
    }

    @Test
    fun concurrentUpdatesBothApply() = runTest {
        val repo = SettingsRepository(store(backgroundScope, tmp.newFile("d.preferences_pb")))
        listOf(
            async { repeat(10) { repo.update { s -> s.copy(fontSizeSp = s.fontSizeSp + 1) } } },
            async { repeat(10) { repo.update { s -> s.copy(opacity = s.opacity + 0.01f) } } },
        ).awaitAll()
        val got = repo.current()
        assertEquals(28, got.fontSizeSp)
        assertEquals(0.9f, got.opacity, 0.001f)
    }

    @Test
    fun ioExceptionEmitsDefaults() = runTest {
        val failing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("disk")
        }
        assertEquals(CaptionSettings(), SettingsRepository(failing).current())
    }

    @Test
    fun coloursArePersistedAndAlwaysOpaque() = runTest {
        val file = tmp.newFile("colours.preferences_pb")
        val scope = CoroutineScope(Job())
        val repo = SettingsRepository(store(scope, file))
        repo.update { it.copy(textColor = 0xFFFFE45C.toInt(), plateColor = 0x000B1A33) } // alpha dropped on save
        scope.cancel()
        scope.coroutineContext[Job]!!.join()

        val got = SettingsRepository(store(backgroundScope, file)).current()
        assertEquals(0xFFFFE45C.toInt(), got.textColor)
        assertEquals(0xFF0B1A33.toInt(), got.plateColor)
    }
}
