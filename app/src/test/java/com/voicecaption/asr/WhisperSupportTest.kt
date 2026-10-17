package com.voicecaption.asr

import java.io.File
import java.io.FileNotFoundException
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WhisperSupportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun languageIsLowercasedAndTrimmed() {
        assertEquals("en", normalizeWhisperLanguage("  EN "))
    }

    @Test fun regionTagsReduceToPrimaryLanguage() {
        assertEquals("pt", normalizeWhisperLanguage("pt-BR"))
        assertEquals("zh", normalizeWhisperLanguage("zh_CN"))
    }

    @Test fun blankAndAutoMeanDetectLanguage() {
        assertEquals("", normalizeWhisperLanguage(""))
        assertEquals("", normalizeWhisperLanguage("   "))
        assertEquals("", normalizeWhisperLanguage("auto"))
        assertEquals("", normalizeWhisperLanguage("AUTO"))
    }

    @Test fun threadCountScalesWithCoresWithinTwoToFour() {
        assertEquals(2, whisperThreads(1))
        assertEquals(2, whisperThreads(4))
        assertEquals(3, whisperThreads(6))
        assertEquals(4, whisperThreads(8))
        assertEquals(4, whisperThreads(64))
    }

    @Test fun missingModelFileGivesClearError() {
        val dir = tmp.newFolder("base")
        File(dir, "encoder.int8.onnx").writeText("x")
        File(dir, "tokens.txt").writeText("x")
        val e = assertThrows(FileNotFoundException::class.java) { WhisperFiles.resolve(dir) }
        assertTrue(e.message!!.contains("decoder.int8.onnx"))
    }

    @Test fun resolvesAllThreeFilesWhenPresent() {
        val dir = tmp.newFolder("small")
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt").forEach { File(dir, it).writeText("x") }
        val files = WhisperFiles.resolve(dir)
        assertEquals(File(dir, "encoder.int8.onnx").path, files.encoder)
        assertEquals(File(dir, "decoder.int8.onnx").path, files.decoder)
        assertEquals(File(dir, "tokens.txt").path, files.tokens)
    }
}
