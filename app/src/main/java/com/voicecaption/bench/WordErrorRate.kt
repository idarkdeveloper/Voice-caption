package com.voicecaption.bench

import java.util.Locale

/**
 * Word error rate: word-level Levenshtein distance divided by the reference word count.
 * Comparison is case-insensitive, punctuation is stripped and tokenization is Unicode-aware
 * (letters, digits and combining marks are kept). An empty reference gives 0.0 against an empty
 * hypothesis and 1.0 against anything else.
 */
internal object WordErrorRate {
    /** Returns the WER of [hypothesis] against [reference]; may exceed 1.0 with many insertions. */
    fun compute(reference: String, hypothesis: String): Double =
        rate(tokenizeWords(reference), tokenizeWords(hypothesis))

    internal fun tokenizeWords(text: String): List<String> =
        normalize(text).split(WHITESPACE).filter { it.isNotEmpty() }

    internal fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(NON_WORD, "")

    internal fun <T> rate(reference: List<T>, hypothesis: List<T>): Double {
        if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0
        return editDistance(reference, hypothesis).toDouble() / reference.size
    }

    private fun <T> editDistance(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1)
            cur[0] = i
            for (j in 1..b.size) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[b.size]
    }

    // Keep letters, digits, combining marks and whitespace; everything else is punctuation.
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\p{M}\\s]")
    private val WHITESPACE = Regex("\\s+")
}
