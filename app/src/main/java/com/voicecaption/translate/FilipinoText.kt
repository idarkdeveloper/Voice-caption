package com.voicecaption.translate

/**
 * Cleans a Filipino sentence before machine translation. Spoken Filipino is full of politeness and
 * filler words ("po", "ho", "eh", "ha") that have no English counterpart; left in, they get
 * translated into stray words. "opo" is a polite "yes".
 */
object FilipinoText {
    private val PARTICLES = Regex("""(?i)(?<![\p{L}\p{N}'])(po|ho|eh|ha)(?![\p{L}\p{N}'])""")
    private val OPO = Regex("""(?i)(?<![\p{L}\p{N}'])opo(?![\p{L}\p{N}'])""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.?!])""")
    private val DOUBLE_COMMA = Regex(""",\s*,""")

    fun prepare(text: String): String {
        var t = OPO.replace(text, "oo")
        t = PARTICLES.replace(t, "")
        t = DOUBLE_COMMA.replace(t, ",")
        t = SPACE_BEFORE_PUNCT.replace(t, "$1")
        t = t.replace(Regex("""\s{2,}"""), " ").trim().trim(',').trim()
        t = t.replace(Regex("""^[,.]\s*"""), "")
        return t.ifBlank { text.trim() } // never translate nothing
    }
}
