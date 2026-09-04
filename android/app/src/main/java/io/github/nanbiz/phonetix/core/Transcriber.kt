package io.github.nanbiz.phonetix.core

import android.graphics.RectF

/**
 * A transcription, the word it replaces, the screen rectangle of that word, and the colours
 * that word is drawn in. The colours are 0 when they could not be read from the screen, and
 * the overlay then falls back to its own palette rather than inventing one.
 */
data class WordBox(
    val rect: RectF,
    val ipa: String,
    val word: String,
    val background: Int = 0,
    val ink: Int = 0,
)

/** One token of a line of text: the word, and its transcription when it was picked. */
data class Token(val text: String, val ipa: String?)

/** A word chosen for transcription and where it sits in its node's text. */
data class Pick(val start: Int, val end: Int, val word: String, val ipa: String)

/**
 * Turns text into the words worth transcribing. The occurrence counter is shared across a
 * whole pass so a word repeated down the screen is decided independently each time, the
 * way it is in the extension.
 *
 * Choosing and placing are deliberately separate. Choosing needs only the text and is
 * nearly free; placing needs the per-character bounds, which cost a round trip to the app
 * being read. Splitting them means that round trip is only paid for the nodes that turned
 * out to hold a word worth showing, which on an ordinary screen is a small minority.
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
    fun tokens(text: String): List<Token> {
        val out = ArrayList<Token>()
        val n = text.length
        var i = 0
        while (i < n) {
            if (!Character.isLetter(text[i])) { i++; continue }
            var j = i + 1
            while (j < n) {
                val c = text[j]
                if (Character.isLetter(c) || c == '\'' || c == '\u2019') j++ else break
            }
            val raw = text.substring(i, j)
            out.add(Token(raw, pick(raw)))
            i = j
        }
        return out
    }

    /**
     * Which words of this text are transcribed. No layout, no round trip.
     *
     * Hand-scanned rather than matched with a regex: this runs over every piece of text on
     * screen on every pass, and a regex allocates a match object per word where a loop over
     * the characters allocates only for the words that are actually looked up.
     */
    fun plan(text: CharSequence): List<Pick> {
        var out: ArrayList<Pick>? = null
        val n = text.length
        var i = 0
        while (i < n) {
            if (!Character.isLetter(text[i])) { i++; continue }
            var j = i + 1
            while (j < n) {
                val c = text[j]
                if (Character.isLetter(c) || c == '\'' || c == '\u2019') j++ else break
            }
            // Single-letter runs can never be in the dictionary under the length rule, so
            // they are dropped before anything is allocated for them.
            if (j - i >= 2) {
                val raw = text.subSequence(i, j).toString()
                val ipa = pick(raw)
                if (ipa != null) {
                    (out ?: ArrayList<Pick>(4).also { out = it }).add(Pick(i, j - 1, raw, ipa))
                }
            }
            i = j
        }
        return out ?: emptyList()
    }

    companion object {
        /**
         * Places already-chosen words, given the per-character screen rectangles the
         * accessibility API returned. A word's box is the union of its characters, so it
         * lands exactly on the word however the app laid the line out; characters scrolled
         * out of view come back empty and those words are dropped rather than half-placed.
         */
        fun boxes(
            picks: List<Pick>,
            charRects: Array<RectF?>,
            offset: Int,
            into: MutableList<WordBox>,
        ) {
            for (p in picks) {
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
                var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                var any = false
                for (i in p.start..p.end) {
                    val cr = charRects.getOrNull(i - offset) ?: continue
                    if (cr.width() <= 0f || cr.height() <= 0f) continue
                    if (cr.left < l) l = cr.left
                    if (cr.top < t) t = cr.top
                    if (cr.right > r) r = cr.right
                    if (cr.bottom > b) b = cr.bottom
                    any = true
                }
                if (any && r > l && b > t) into.add(WordBox(RectF(l, t, r, b), p.ipa, p.word))
            }
        }
    }
}
