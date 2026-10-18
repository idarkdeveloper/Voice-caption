package com.voicecaption.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Writes the app's language list for the paid-tier backend's coverage check
 * (backend/tools/check-deepgram-languages.ts reads app/build/outputs/supported-languages.json).
 */
class SupportedLanguagesExportTest {
    @Test
    fun exportsTheSortedLanguageCodes() {
        val codes = SupportedLanguages.codes.sorted()
        val out = File("build/outputs/supported-languages.json")
        out.parentFile.mkdirs()
        out.writeText(codes.joinToString(prefix = "[", postfix = "]\n", separator = ",") { "\"$it\"" })

        assertEquals(codes.distinct().size, codes.size)
        assertEquals(57, codes.size)
    }
}
