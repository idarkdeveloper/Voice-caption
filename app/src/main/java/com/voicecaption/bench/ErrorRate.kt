package com.voicecaption.bench

/**
 * Picks the right transcription error metric for a reference: word error rate for languages
 * written with spaces, character error rate (CER) when the reference contains no spaces
 * (Japanese, Chinese).
 */
internal object ErrorRate {
    /** Which metric produced a [Result]. */
    enum class Metric { WER, CER }

    /** An error rate [value] (0.0 = perfect) and the [metric] it was computed with. */
    data class Result(val value: Double, val metric: Metric)

    /** Compares [hypothesis] with [reference], using CER when the reference has no spaces. */
    fun compute(reference: String, hypothesis: String): Result {
        if (reference.trim().any { it.isWhitespace() }) {
            return Result(WordErrorRate.compute(reference, hypothesis), Metric.WER)
        }
        val ref = WordErrorRate.normalize(reference).filterNot { it.isWhitespace() }.codePointList()
        val hyp = WordErrorRate.normalize(hypothesis).filterNot { it.isWhitespace() }.codePointList()
        return Result(WordErrorRate.rate(ref, hyp), Metric.CER)
    }

    private fun String.codePointList(): List<Int> = codePoints().toArray().toList()
}
