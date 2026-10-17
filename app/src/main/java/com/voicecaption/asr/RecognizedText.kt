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

    /** True when [text] holds real words (after cut-off notes are removed), not just tags or symbols. */
    fun hasWords(text: String): Boolean = !isNoise(stripSoundTags(text).trim())
}
