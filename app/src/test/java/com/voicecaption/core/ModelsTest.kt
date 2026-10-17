package com.voicecaption.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelsTest {
    @Test
    fun defaultSettingsAreReadable() {
        val s = CaptionSettings()
        assertEquals(18, s.fontSizeSp)
        assertEquals(0.8f, s.opacity, 0f)
        assertFalse(s.bilingual)
        assertEquals(ModelSize.BASE, s.modelSize)
    }

    @Test
    fun autoModelPicksSenseVoiceForCjkAndWhisperOtherwise() {
        for (lang in listOf("ja", "zh", "ko", "yue", "zh-CN", "JA")) {
            assertEquals(ModelSize.SENSEVOICE, CaptionSettings(sourceLang = lang).activeModel)
        }
        assertEquals(ModelSize.BASE, CaptionSettings(sourceLang = "es").activeModel)
        assertEquals(ModelSize.SMALL, CaptionSettings(sourceLang = "es", modelSize = ModelSize.SMALL).activeModel)
        // A stored SenseVoice choice must not leak into languages it handles poorly.
        assertEquals(ModelSize.BASE, CaptionSettings(sourceLang = "fr", modelSize = ModelSize.SENSEVOICE).activeModel)
    }

    @Test
    fun explicitModelChoiceAlwaysWinsOverAuto() {
        assertEquals(ModelSize.BASE, CaptionSettings(sourceLang = "ja", modelSize = ModelSize.BASE, autoModel = false).activeModel)
        assertEquals(ModelSize.SENSEVOICE, CaptionSettings(sourceLang = "en", modelSize = ModelSize.SENSEVOICE, autoModel = false).activeModel)
    }
}
