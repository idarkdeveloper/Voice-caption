package com.voicecaption.bench

/** Device facts written as the first record. */
internal data class BenchMeta(val device: String, val androidRelease: String, val sdkInt: Int, val runsPerClip: Int)

/** One (model, clip) result. [translateMs] is null when translation was skipped. */
internal data class ClipRecord(
    val model: String,
    val file: String,
    val lang: String,
    val transcript: String,
    val metric: String,
    val errorRate: Double,
    val transcribeMs: Double,
    val translateMs: Double?,
    val totalMs: Double,
)

/** Per-model aggregate over all timed runs. */
internal data class SummaryRecord(
    val model: String,
    val clipCount: Int,
    val medianMs: Double,
    val p95Ms: Long,
    val meanErrorRate: Double,
)

/**
 * Serializes benchmark results as JSON Lines: one self-contained JSON object per line (a `meta`
 * record, a `clip` record per model and clip, a `summary` record per model). Each line is shorter
 * than [MAX_LINE_CHARS], so it fits in one logcat entry and no chunk boundary can fall inside a
 * record; transcripts are cut at [MAX_TRANSCRIPT_CHARS] (flagged by `transcriptTruncated`) to
 * guarantee that. Pure Kotlin (own escaping) so it is JVM-testable.
 */
internal object ResultsJsonLines {
    /** Every emitted line is strictly shorter than this many characters. */
    const val MAX_LINE_CHARS = 3000
    /** Worst case every char escapes to 6 chars (\u00XX), so 400 -> 2400 plus field overhead. */
    const val MAX_TRANSCRIPT_CHARS = 400
    private const val MAX_DEVICE_CHARS = 200

    fun build(meta: BenchMeta, clips: List<ClipRecord>, summaries: List<SummaryRecord>): List<String> {
        val lines = buildList {
            add(
                """{"type":"meta","device":${str(meta.device.take(MAX_DEVICE_CHARS))},""" +
                    """"androidRelease":${str(meta.androidRelease.take(MAX_DEVICE_CHARS))},""" +
                    """"sdkInt":${meta.sdkInt},"runsPerClip":${meta.runsPerClip}}""",
            )
            clips.forEach { add(clipLine(it)) }
            summaries.forEach {
                add(
                    """{"type":"summary","model":${str(it.model)},"clipCount":${it.clipCount},""" +
                        """"medianMs":${num(it.medianMs)},"p95Ms":${it.p95Ms},"meanErrorRate":${num(it.meanErrorRate)}}""",
                )
            }
        }
        check(lines.all { it.length < MAX_LINE_CHARS }) { "record exceeds $MAX_LINE_CHARS chars" }
        return lines
    }

    private fun clipLine(c: ClipRecord): String {
        val cut = c.transcript.length > MAX_TRANSCRIPT_CHARS
        var end = minOf(c.transcript.length, MAX_TRANSCRIPT_CHARS)
        if (cut && Character.isHighSurrogate(c.transcript[end - 1])) end-- // keep surrogate pairs whole
        return """{"type":"clip","model":${str(c.model)},"file":${str(c.file)},"lang":${str(c.lang)},""" +
            """"transcript":${str(c.transcript.substring(0, end))},"transcriptTruncated":$cut,""" +
            """"metric":${str(c.metric)},"errorRate":${num(c.errorRate)},"transcribeMs":${num(c.transcribeMs)},""" +
            """"translation":${if (c.translateMs == null) "\"skipped\"" else "\"ok\""},""" +
            """"translateMs":${c.translateMs?.let { num(it) } ?: "null"},"totalMs":${num(c.totalMs)}}"""
    }

    private fun num(d: Double): String = if (d.isFinite()) d.toString() else "null"

    private fun str(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' || ch == ' ' || ch == ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }
}
