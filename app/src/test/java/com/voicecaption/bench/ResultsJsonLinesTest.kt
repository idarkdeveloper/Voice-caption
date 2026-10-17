package com.voicecaption.bench

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsJsonLinesTest {
    private val meta = BenchMeta("Acme Phone \"X\"", "14", 34, 3)

    private fun clip(model: String = "BASE", file: String = "a.wav", transcript: String = "hello", translateMs: Double? = 120.0) =
        ClipRecord(model, file, "en", transcript, "WER", 0.25, 800.0, translateMs, 920.0)

    private fun summary(model: String = "BASE") = SummaryRecord(model, 2, 900.0, 1200L, 0.2)

    private fun build(clips: List<ClipRecord>, summaries: List<SummaryRecord>) =
        ResultsJsonLines.build(meta, clips, summaries)

    @Test fun oneMetaThenClipsThenSummariesAllValidJson() {
        val lines = build(
            listOf(clip(file = "a.wav"), clip(file = "b.wav"), clip(model = "SMALL")),
            listOf(summary("BASE"), summary("SMALL")),
        )
        assertEquals(1 + 3 + 2, lines.size)
        val types = lines.map { JSONObject(it).getString("type") }
        assertEquals(listOf("meta", "clip", "clip", "clip", "summary", "summary"), types)
        assertEquals(34, JSONObject(lines[0]).getInt("sdkInt"))
        assertEquals("Acme Phone \"X\"", JSONObject(lines[0]).getString("device"))
    }

    @Test fun unicodeSurvives() {
        val ja = "こんにちは、お元気ですか。"
        val hi = "नमस्ते, आप कैसे हैं?"
        val lines = build(listOf(clip(transcript = ja), clip(transcript = hi)), emptyList())
        assertEquals(ja, JSONObject(lines[1]).getString("transcript"))
        assertEquals(hi, JSONObject(lines[2]).getString("transcript"))
    }

    @Test fun quotesBackslashesAndNewlinesAreEscaped() {
        val nasty = "say \"hi\"\\ path\nnext\r\ttab \u0001 end"
        val lines = build(listOf(clip(transcript = nasty)), emptyList())
        assertEquals(2, lines.size)
        lines.forEach { assertFalse(it.contains('\n') || it.contains('\r')) }
        assertEquals(nasty, JSONObject(lines[1]).getString("transcript"))
    }

    @Test fun skippedTranslationIsNullAndFlagged() {
        val o = JSONObject(build(listOf(clip(translateMs = null)), emptyList())[1])
        assertEquals("skipped", o.getString("translation"))
        assertTrue(o.isNull("translateMs"))
    }

    @Test fun longTranscriptIsTruncatedAndEveryLineStaysUnderLimit() {
        val worst = "\u0001".repeat(5000) // each char escapes to 6 chars
        val lines = build(listOf(clip(transcript = worst), clip(transcript = "é".repeat(5000))), listOf(summary()))
        lines.forEach {
            assertTrue("line too long: ${it.length}", it.length < ResultsJsonLines.MAX_LINE_CHARS)
            JSONObject(it)
        }
        val o = JSONObject(lines[1])
        assertTrue(o.getBoolean("transcriptTruncated"))
        assertTrue(worst.startsWith(o.getString("transcript")))
    }

    @Test fun shortTranscriptIsNotFlaggedTruncated() {
        assertFalse(JSONObject(build(listOf(clip()), emptyList())[1]).getBoolean("transcriptTruncated"))
    }
}
