package com.voicecaption.asr

/**
 * Rules about what a recognizer's text means, shared by the caption pipeline and the language
 * detector so they always agree on what counts as speech.
 */
object RecognizedText {
    /** One or more whole-string `[...]` / `(...)` tags, e.g. "[Music]", "(applause)". */
    private val TAGS_ONLY = Regex("""(\s*(\[[^\]]*]|\([^)]*\))\s*)+""")

    private val MUSIC_WORDS = Regex("(?i)music|musik|música|musique|musica|音楽|音乐|음악|bgm|[♪♫♬🎵🎶]")

    /**
     * Removes a cut-off sound-effect note: an unfinished "(The sound of" or "[Music" and everything
     * after it. Complete tags inside real speech are left as they are.
     */
    fun stripSoundTags(text: String): String {
        val open = text.indexOfFirst { it == '(' || it == '[' }
        if (open < 0) return text
        val closer = if (text[open] == '(') ')' else ']'
        return if (text.indexOf(closer, open) >= 0) text else text.substring(0, open).trim()
    }

    /**
     * Collapses the endless loops Whisper falls into on unclear audio ("The amount of the amount of
     * the amount of ..."): a group of 2 to 8 words repeated 3 or more times in a row is kept once,
     * and a single word repeated 5 or more times is kept twice. Normal repetition ("No, no, no") is left alone.
     */
    fun collapseRepeats(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 4) return text
        fun key(w: String) = w.lowercase().trim { !it.isLetterOrDigit() }
        val keys = words.map(::key)
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            var collapsed = false
            for (n in 1..8) {
                val minRuns = if (n == 1) 5 else 3
                if (i + n * minRuns > words.size) continue
                var runs = 1
                while (i + (runs + 1) * n <= words.size &&
                    (0 until n).all { k -> keys[i + k] == keys[i + runs * n + k] }
                ) runs++
                if (runs >= minRuns) {
                    val keep = if (n == 1) 2 else 1
                    out.addAll(words.subList(i, i + n * keep))
                    i += n * runs
                    collapsed = true
                    break
                }
            }
            if (!collapsed) { out.add(words[i]); i++ }
        }
        return out.joinToString(" ")
    }

    /** Non-speech output: blank, only bracketed tags, or no letter/digit at all ("♪", "..."). */
    fun isNoise(text: String): Boolean =
        text.isBlank() || TAGS_ONLY.matches(text) || text.none { it.isLetterOrDigit() }

    /**
     * True when all that was heard is music: SenseVoice's background-music event with no words, or
     * Whisper's music tag / note symbols with no words ("[Music]", "(upbeat music)", "♪").
     * Sung lyrics (real words) are not music here; they are shown as text.
     */
    fun isMusic(event: String?, text: String): Boolean {
        val t = text.trim()
        if (event == "bgm" && isNoise(t)) return true
        return t.isNotEmpty() && isNoise(t) && MUSIC_WORDS.containsMatchIn(t)
    }

    private val HALLUCINATIONS = Regex(
        "(?i)terima kasih kerana menonton|thank(s| you) for watching|salamat sa panonood|subtitles? by|amara\\.org|" +
            "don't forget to subscribe|y continuamos|gracias por ver",
    )

    /** Text Whisper invents over music or silence (outro phrases from its training subtitles). */
    fun isHallucination(text: String): Boolean = HALLUCINATIONS.containsMatchIn(text)

    /** True when [text] holds real words (after cut-off notes are removed), not just tags or symbols. */
    fun hasWords(text: String): Boolean = !isNoise(stripSoundTags(text).trim())
}
