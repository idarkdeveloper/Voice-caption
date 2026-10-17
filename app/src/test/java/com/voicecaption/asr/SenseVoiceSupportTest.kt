package com.voicecaption.asr

import com.voicecaption.core.ModelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseVoiceSupportTest {
    @Test
    fun supportedLanguagesAreMappedByPrimarySubtag() {
        assertEquals("ja", normalizeSenseVoiceLanguage("ja"))
        assertEquals("zh", normalizeSenseVoiceLanguage("zh-CN"))
        assertEquals("en", normalizeSenseVoiceLanguage("EN_us"))
        assertEquals("ko", normalizeSenseVoiceLanguage(" ko "))
        assertEquals("yue", normalizeSenseVoiceLanguage("yue"))
    }

    @Test
    fun unsupportedOrBlankLanguageMeansAutoDetect() {
        assertEquals("auto", normalizeSenseVoiceLanguage("fr"))
        assertEquals("auto", normalizeSenseVoiceLanguage(""))
        assertEquals("auto", normalizeSenseVoiceLanguage("auto"))
    }

    @Test
    fun catalogHasTheTwoSenseVoiceFilesWithPinnedHashes() {
        val files = com.voicecaption.asr.ModelCatalog.DEFAULT.files(ModelSize.SENSEVOICE)
        assertEquals(listOf("model.int8.onnx", "tokens.txt"), files.map { it.name })
        assertTrue(files.all { it.sha256.length == 64 && it.url.contains("2365baeacb507f821a0c8120fcee3d484dba7a07") })
        assertEquals(239_549_735L, com.voicecaption.asr.ModelCatalog.DEFAULT.totalBytes(ModelSize.SENSEVOICE))
    }
}
