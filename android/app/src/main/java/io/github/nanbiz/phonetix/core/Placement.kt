package io.github.nanbiz.phonetix.core

import android.graphics.RectF
import kotlin.math.sqrt

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
    /** What was drawn over the word where the core had decided which word it is, and empty
     *  where it had not: the card opens on the reading the line showed. */
    val decided: String = "",
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
    /** What was drawn, where the core decided which word it is. See [WordBox.decided]. */
    val decided: String = "",
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

    /** How tall a row of Latin text is against how wide its average character is. */
    private const val ROW_TO_CHARACTER = 2.7f

    /** How much of a text block's own box the text inside it covers. */
    private const val FILLED = 0.83f

    private const val NARROW = "iljItf.,;:'!|()[]"
    private const val WIDE = "mwMW"

    /**
     * How wide one character is against the average one, in a proportional Latin face.
     *
     * Every character the same width breaks a row at the wrong word: a row of "whistle
     * yoghurt acorn bamboo cinnamon" is thirty-seven characters and nearly a row wide, while
     * thirty-seven narrow ones are two thirds of one. The numbers are the advances of a
     * grotesque sans - Roboto, what a phone draws with - rounded into four kinds.
     */
    private fun advance(c: Char): Float = when {
        c.isWhitespace() -> 0.47f
        NARROW.indexOf(c) >= 0 -> 0.53f
        WIDE.indexOf(c) >= 0 -> 1.53f
        c.isUpperCase() -> 1.16f
        else -> 1f
    }

    /**
     * How tall one row of a block of text probably is, for a caller that wants the number
     * itself: a block the screen cuts off reports only the part of its box that is showing,
     * so the shape of that part says nothing about its rows, and the rows of the whole blocks
     * around it have to stand in.
     */
    fun rowTall(chars: Int, where: RectF): Float =
        if (chars <= 0 || where.width() <= 0f || where.height() <= 0f) {
            0f
        } else {
            sqrt(ROW_TO_CHARACTER * FILLED * where.width() * where.height() / chars)
        }

    /**
     * Where the characters of a block of text probably are, when the app holding it will not
     * say.
     *
     * The shape comes from the block's own box and the number of characters in it: a row of
     * Latin text is between two and three of its average characters tall, and the text covers
     * about four fifths of the box, the rest being the padding around it and the ragged ends
     * of the rows. That fixes the row height, the row height fixes how wide the average
     * character is, and the widths above spread the rest.
     *
     * Every position is a guess: a row that breaks one word early moves every word after it,
     * and nothing here knows the widths of the glyphs the app actually drew. Measured against
     * the same page answering the request for character positions, nine words in ten land
     * within half a word of where they are, which is what deciding between one word and its
     * neighbour comes down to.
     *
     * @param knownRowTall how tall a row is, where the caller knows better than this block's
     *   own box does - a short block is mostly the padding around its text, and a block the
     *   screen cuts off reports only the part of its box that is showing.
     */
    fun evenly(text: CharSequence, where: RectF, knownRowTall: Float = 0f): Array<RectF?> {
        val n = text.length
        if (n == 0 || where.width() <= 0f || where.height() <= 0f) return arrayOfNulls(0)
        var total = 0f
        val widths = FloatArray(n) { advance(text[it]).also { w -> total += w } }
        val rowTall = if (knownRowTall > 0f) knownRowTall else rowTall(n, where)
        // Pixels per unit of the widths above, set so that the average character of this text
        // comes out as wide as the row height says it should be.
        val unit = rowTall / ROW_TO_CHARACTER / (total / n)
        val rowOf = IntArray(n)
        val startOf = FloatArray(n)
        // Broken where the text breaks: at the spaces between words, greedily, the way any
        // layout wraps. Cut at a fixed count instead, every row after the first is out by
        // however far the previous row's last word ran over - which is why the guess held at
        // the top of a message and drifted towards the bottom.
        var row = 0
        var x = 0f
        var i = 0
        while (i < n) {
            var j = i
            var wordWide = 0f
            while (j < n && !text[j].isWhitespace()) {
                wordWide += widths[j] * unit
                j++
            }
            if (x > 0f && x + wordWide > where.width()) {
                row++
                x = 0f
            }
            var k = i
            while (k < j) {
                rowOf[k] = row
                startOf[k] = x
                x += widths[k] * unit
                k++
            }
            // The spaces that follow, which sit on the row the word ended on.
            while (k < n && text[k].isWhitespace()) {
                rowOf[k] = row
                startOf[k] = x
                x += widths[k] * unit
                k++
            }
            i = k
        }
        // The rows this tall leave the rest of the box as padding, half above and half below.
        // Spread over the whole box instead, every row after the first sits below the one it
        // belongs to, by more with every row.
        val padding = ((where.height() - (row + 1) * rowTall) / 2f).coerceAtLeast(0f)
        val out = arrayOfNulls<RectF>(n)
        for (at in 0 until n) {
            val left = where.left + startOf[at]
            val top = where.top + padding + rowOf[at] * rowTall
            out[at] = RectF(left, top, left + widths[at] * unit, top + rowTall)
        }
        return out
    }

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
        language: String = "",
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
                        // What the screen this word came from was read as. Without it the
                        // card looked every word up in English: a German page answered a
                        // German word with nothing at all, under a chip saying EN.
                        language,
                        at = p.start,
                        to = p.end,
                        decided = p.decided,
                    ),
                )
            }
        }
    }
}
