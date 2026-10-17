package com.voicecaption.translate

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Needs a device/emulator; translatesEnglishToSpanish also needs network (downloads the es pack). */
@RunWith(AndroidJUnit4::class)
class MlKitTranslatorTest {
    private lateinit var context: Context
    private lateinit var translator: MlKitTranslator

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        translator = MlKitTranslator(context)
    }

    @After fun tearDown() = translator.close()

    @Test fun translatesEnglishToSpanish() = runBlocking {
        translator.prepare("en", "es") {}.getOrThrow()
        assertTrue(translator.isReady("en", "es"))
        val out = translator.translate("good morning", "en", "es")
        assertTrue("Blank translation", out.isNotBlank())
        assertNotEquals("good morning", out)
    }

    /** Review Focus 4: a missing pack must fail promptly, never download silently. */
    @Test fun translateFailsCleanlyWhenPackMissingOffline() = runBlocking {
        // Maltese is unlikely to be downloaded; delete it to guarantee the pack is missing.
        RemoteModelManager.getInstance()
            .deleteDownloadedModel(TranslateRemoteModel.Builder(TranslateLanguage.MALTESE).build()).await()
        assertFalse(translator.isReady("en", "mt"))
        try {
            translator.translate("good morning", "en", "mt")
            org.junit.Assert.fail("expected TranslatorNotReadyException")
        } catch (_: TranslatorNotReadyException) {
        }
    }

    @Test fun sameLanguageNeedsNoPack() = runBlocking {
        assertTrue(translator.isReady("en", "EN"))
        assertEquals("hello", translator.translate("hello", "en", "en-US"))
    }

    @Test fun unsupportedLanguageIsNotReadyAndPrepareFails() = runBlocking {
        assertFalse(translator.isReady("en", "jv"))
        assertTrue(translator.prepare("en", "jv") {}.exceptionOrNull() is IllegalArgumentException)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { translator.translate("x", "en", "jv") }
        }
    }

    @Test fun supportedLanguageSetMatchesMlKit() {
        assertEquals(TranslateLanguage.getAllLanguages().toSet(), TRANSLATE_LANGUAGES)
    }
}
