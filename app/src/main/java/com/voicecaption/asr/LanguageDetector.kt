package com.voicecaption.asr

/**
 * Decides which language a film is in from per-sentence guesses. It locks once one language has at
 * least [lockAfterVotes] votes and more than any other, or after [maxVotes] votes in total (the
 * leader wins). Blank guesses are ignored. Once locked it never changes within a session.
 */
class LanguageDetector(
    private val lockAfterVotes: Int = 2,
    private val maxVotes: Int = 5,
) {
    private val counts = LinkedHashMap<String, Int>()
    private var total = 0

    /** The decided language, or null while still listening. */
    var locked: String? = null
        private set

    /** Adds one guess; returns the locked language if this vote (or an earlier one) decided it. */
    fun vote(language: String?): String? {
        locked?.let { return it }
        val lang = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        counts[lang] = (counts[lang] ?: 0) + 1
        total++
        val ranked = counts.entries.sortedByDescending { it.value }
        val top = ranked.first()
        val second = ranked.getOrNull(1)?.value ?: 0
        if ((top.value >= lockAfterVotes && top.value > second) || total >= maxVotes) locked = top.key
        return locked
    }
}
