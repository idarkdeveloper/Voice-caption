package com.voicecaption.caption

import com.voicecaption.core.Caption

/**
 * Keeps the small set of captions currently on screen.
 *
 * Every method returns the visible captions ordered oldest to newest. Rules:
 * - Blank text is ignored.
 * - A caption's `updatedAtMs` is set from `nowMs` on every update, so expiry counts from the last update.
 * - An update to an existing id keeps its position; a new id is appended and the oldest captions
 *   beyond [maxLines] are dropped.
 * - `isFinal` becomes true only via [onTranslated]. A translation for an unknown id creates a
 *   final caption with an empty `sourceText`.
 *
 * Not thread-safe; call from a single thread.
 */
class CaptionStabilizer(
    private val maxLines: Int = 2,
    private val displayMs: Long = 4000,
    private val msPerChar: Long = 80,
    private val maxDisplayMs: Long = 9000,
) {
    private val captions = LinkedHashMap<Long, Caption>()

    /** Shows (or refreshes) the recognized source text for [id], not yet translated. */
    fun onSourceText(id: Long, text: String, nowMs: Long): List<Caption> {
        if (text.isBlank()) return visible()
        val old = captions[id]
        captions[id] = old?.copy(sourceText = text, updatedAtMs = nowMs)
            ?: Caption(id, text, null, false, nowMs)
        trim()
        return visible()
    }

    /** Marks [id] final with its translation, replacing the source line in non-bilingual display. */
    fun onTranslated(id: Long, text: String, nowMs: Long): List<Caption> {
        if (text.isBlank()) return visible()
        val old = captions[id]
        captions[id] = old?.copy(translatedText = text, isFinal = true, updatedAtMs = nowMs)
            ?: Caption(id, "", text, true, nowMs)
        trim()
        return visible()
    }

    /**
     * Drops captions that have been on screen long enough. A caption stays at least [displayMs] after
     * its last update, longer for long text (about [msPerChar] per character, up to [maxDisplayMs]),
     * so a long sentence is not removed before it can be read.
     */
    fun expire(nowMs: Long): List<Caption> {
        captions.values.removeAll { nowMs - it.updatedAtMs > displayTimeMs(it) }
        return visible()
    }

    private fun displayTimeMs(c: Caption): Long {
        val chars = (c.translatedText ?: c.sourceText).length
        return (1500 + chars * msPerChar).coerceIn(displayMs, maxOf(displayMs, maxDisplayMs))
    }

    private fun trim() {
        while (captions.size > maxLines) {
            captions.remove(captions.keys.first())
        }
    }

    private fun visible(): List<Caption> = captions.values.toList()
}
