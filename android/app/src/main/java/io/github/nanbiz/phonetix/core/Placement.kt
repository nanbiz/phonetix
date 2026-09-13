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
    /** The word before this one on its line, which decides a spelling that is several words.
     *  Empty for the first word of a line: a line is what the app drew, and borrowing the last
     *  word of the line above would be reading a sentence that is not there. */
    val before: String = "",
    /** What the screen this word came from was found to be in. */
    val language: String = "",
    val background: Int = 0,
    val ink: Int = 0,
    /** Which line of the page this word came from, where the page numbers its lines. Only a
     *  test fixture does, and only a debug build reads it: it is what lets a check made of
     *  pixels alone ask whether the line the overlay believes a word is on is the line the
     *  word is actually over, without believing anything the overlay says. */
    val line: Int = -1,
    /** Where this word starts in the text of the line it came from, and where it ends.
     *  A word swept into a phrase has to be found again in that line: the run a reader asks
     *  about is the line's own words between the first and the last, which includes the ones
     *  that carry no transcription of their own. Both are -1 for a word that came from
     *  somewhere without a line behind it. */
    val at: Int = -1,
    val to: Int = -1,
)

/** One token of a line of text: the word, and its transcription when it was picked. */
data class Token(val text: String, val ipa: String?)

/** A word chosen for transcription and where it sits in its node's text. */
data class Pick(
    val start: Int,
    val end: Int,
    val word: String,
    val ipa: String,
    /** The word before this one on its line, which decides a spelling that is several words. */
    val before: String = "",
)

/**
 * Where a chosen word sits on the screen.
 *
 * Which words are chosen is the core's answer; this is the half that cannot be shared,
 * because it is made of character rectangles the app being read has to be asked for. That
 * round trip is only paid for the lines the core found something in, which on an ordinary
 * screen is a small minority.
 */
object Placement {

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
                into.add(
                    WordBox(
                        RectF(l, t, r, b),
                        DisplayIpa.display(p.ipa),
                        p.ipa,
                        p.word,
                        // Carried from the pick, so a tap asks the same question the line
                        // already answered about which word this spelling is.
                        p.before,
                        at = p.start,
                        to = p.end,
                    ),
                )
            }
        }
    }
}
