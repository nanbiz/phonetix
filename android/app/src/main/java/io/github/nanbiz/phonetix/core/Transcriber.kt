package io.github.nanbiz.phonetix.core

import android.graphics.RectF

/** A transcription and the screen rectangle of the word it belongs over. */
data class WordBox(val rect: RectF, val ipa: String)

/** One token of a line of text: the word, and its transcription when it was picked. */
data class Token(val text: String, val ipa: String?)

/**
 * Turns text into the words worth transcribing. The occurrence counter is shared across a
 * whole pass so a word repeated down the screen is decided independently each time, the
 * way it is in the extension.
 */
class Transcriber(private val density: Int) {
    private val seen = HashMap<String, Int>()

    private fun pick(raw: String): String? {
        val word = raw.lowercase()
        if (word.length < 2) return null
        if (Dictionary.isCommon(word)) return null
        val ipa = Dictionary.lookup(word) ?: return null
        val occ = seen.getOrDefault(word, 0)
        seen[word] = occ + 1
        return if (Frequency.picks(word, occ, density)) ipa else null
    }

    /** Words of a text, each with its transcription when it was picked. For the preview. */
    fun tokens(text: String): List<Token> =
        WORD.findAll(text).map { Token(it.value, pick(it.value)) }.toList()

    /**
     * Word boxes for one on-screen text node, given the per-character screen rectangles
     * the accessibility API returned. A word's box is the union of its characters, so it
     * lands exactly on the word however the app laid the line out; characters scrolled out
     * of view come back null and those words are skipped.
     */
    fun boxes(text: String, charRects: Array<RectF?>, into: MutableList<WordBox>) {
        for (m in WORD.findAll(text)) {
            val ipa = pick(m.value) ?: continue
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            var any = false
            for (i in m.range) {
                val cr = charRects.getOrNull(i) ?: continue
                // A character the app has not laid out comes back as an empty rect.
                if (cr.width() <= 0f || cr.height() <= 0f) continue
                if (cr.left < l) l = cr.left
                if (cr.top < t) t = cr.top
                if (cr.right > r) r = cr.right
                if (cr.bottom > b) b = cr.bottom
                any = true
            }
            // Only paint over a word whose characters were all measured: a partial box
            // would sit half over the word and half over its neighbour.
            if (any && r > l && b > t) into.add(WordBox(RectF(l, t, r, b), ipa))
        }
    }

    private companion object {
        // Letters and the apostrophes inside them; no digits, so "2024" is left alone.
        val WORD = Regex("[\\p{L}][\\p{L}'’]*")
    }
}
