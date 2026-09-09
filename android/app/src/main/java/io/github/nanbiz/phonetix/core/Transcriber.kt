package io.github.nanbiz.phonetix.core

import android.graphics.RectF

/**
 * A transcription, the word it replaces, the screen rectangle of that word, and the colours
 * that word is drawn in. The colours are 0 when they could not be read from the screen, and
 * the overlay then falls back to its own palette rather than inventing one.
 */
data class WordBox(
    val rect: RectF,
    /** What is drawn over the word: stripped for running text. */
    val ipa: String,
    /** The whole transcription, marks and all, which is what the tooltip shows. */
    val full: String,
    val word: String,
    val background: Int = 0,
    val ink: Int = 0,
    /** Which line of the page this word came from, where the page numbers its lines. Only a
     *  test fixture does, and only a debug build reads it: it is what lets a check made of
     *  pixels alone ask whether the line the overlay believes a word is on is the line the
     *  word is actually over, without believing anything the overlay says. */
    val line: Int = -1,
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
        scanWords(text) { i, j ->
            val raw = text.substring(i, j)
            out.add(Token(raw, pick(raw)))
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
        scanWords(text) { i, j ->
            // Single-letter runs can never be in the dictionary under the length rule, so
            // they are dropped before anything is allocated for them.
            if (j - i >= 2) {
                val raw = text.subSequence(i, j).toString()
                val ipa = pick(raw)
                if (ipa != null) {
                    (out ?: ArrayList<Pick>(4).also { out = it }).add(Pick(i, j - 1, raw, ipa))
                }
            }
        }
        return out ?: emptyList()
    }

    companion object {
        /**
         * The word spans of a text: a letter, then any run of letters and the apostrophes
         * inside them. Digits are left alone, so "2024" is never a word.
         *
         * Hand-scanned rather than matched with a regex, because this runs over every piece
         * of text on screen on every pass and a regex allocates a match object per word.
         * WordScanTest holds it to exactly what the regex it replaced matched.
         */
        inline fun scanWords(text: CharSequence, emit: (start: Int, endExclusive: Int) -> Unit) {
            val n = text.length
            var i = 0
            while (i < n) {
                // Stepped by code point, not by char: a letter outside the basic plane is
                // stored as two chars, and testing either half alone says "not a letter" and
                // drops the word. WordScanTest caught exactly that.
                val cp = Character.codePointAt(text, i)
                val size = Character.charCount(cp)
                if (!Character.isLetter(cp)) { i += size; continue }
                var j = i + size
                while (j < n) {
                    val c = Character.codePointAt(text, j)
                    if (Character.isLetter(c) || c == APOSTROPHE || c == RIGHT_QUOTE) {
                        j += Character.charCount(c)
                    } else break
                }
                emit(i, j)
                i = j
            }
        }

        const val APOSTROPHE = '\''.code
        const val RIGHT_QUOTE = '\u2019'.code

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
                if (any && r > l && b > t) {
                    into.add(WordBox(RectF(l, t, r, b), DisplayIpa.display(p.ipa), p.ipa, p.word))
                }
            }
        }
    }
}
