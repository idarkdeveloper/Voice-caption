package com.voicecaption.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupCheckTest {
    private fun eval(
        overlay: Boolean = true,
        record: Boolean = true,
        model: Boolean = true,
        lang: String = "ja",
    ) = StartupCheck.evaluate(overlay, record, model, lang)

    @Test fun `all ok`() = assertEquals(StartupResult.Ok, eval())

    @Test fun `overlay missing`() =
        assertEquals(StartupResult.Failed(StartupProblem.OVERLAY_PERMISSION), eval(overlay = false))

    @Test fun `record audio missing`() =
        assertEquals(StartupResult.Failed(StartupProblem.RECORD_AUDIO), eval(record = false))

    @Test fun `model missing`() =
        assertEquals(StartupResult.Failed(StartupProblem.MODEL_MISSING), eval(model = false))

    @Test fun `blank or auto source language`() {
        for (l in listOf("", "  ", "auto", "AUTO", " Auto ")) {
            assertEquals(l, StartupResult.Failed(StartupProblem.SOURCE_LANGUAGE), eval(lang = l))
        }
    }

    @Test fun `overlay is reported before other problems`() =
        assertEquals(
            StartupResult.Failed(StartupProblem.OVERLAY_PERMISSION),
            eval(overlay = false, record = false, model = false, lang = "auto"),
        )
}
