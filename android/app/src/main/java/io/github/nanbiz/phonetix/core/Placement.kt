package io.github.nanbiz.phonetix.core

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ln
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
    /** The whole line the word is on, which is what says which of its meanings it has. */
    val sentence: String = "",
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
     * Where the characters of a block probably are, given the lines the screen shows it in.
     *
     * The lines come from the pixels ([InkRows]), so where each starts and ends and how many
     * there are is known; what is not is which word each line breaks after. The text is
     * shared out in the proportion of the lines' widths, broken at the space nearest to where
     * each line's share runs out, and each line's characters are spread across it by their
     * widths. A line breaking one word away from where the app broke it moves that word to
     * the next line and nothing else.
     */
    fun inLines(text: CharSequence, lines: List<RectF>): Array<RectF?> {
        val n = text.length
        val out = arrayOfNulls<RectF>(n)
        if (n == 0 || lines.isEmpty()) return out
        val widths = FloatArray(n) { advance(text[it]) }
        val total = widths.sum()
        val room = lines.sumOf { it.width().toDouble() }.toFloat()
        if (total <= 0f || room <= 0f) return out
        // Where each line's share of the text ends, in units of the widths above.
        var start = 0
        var spent = 0f
        var allowed = 0f
        for ((at, line) in lines.withIndex()) {
            allowed += total * line.width() / room
            var end = n
            if (at < lines.size - 1) {
                // The space whose position is nearest to where this line's share runs out.
                var x = spent
                var best = -1
                var bestOff = Float.MAX_VALUE
                for (i in start until n) {
                    if (text[i].isWhitespace() && i > start) {
                        val off = kotlin.math.abs(x - allowed)
                        if (off < bestOff) { bestOff = off; best = i }
                        if (x > allowed) break
                    }
                    x += widths[i]
                }
                end = if (best > 0) best else n
            }
            // The line's own characters, without the spaces it breaks at, fill it.
            var from = start
            while (from < end && text[from].isWhitespace()) from++
            var to = end
            while (to > from && text[to - 1].isWhitespace()) to--
            var wide = 0f
            for (i in from until to) wide += widths[i]
            if (wide > 0f) {
                val unit = line.width() / wide
                var x = line.left
                for (i in from until to) {
                    out[i] = RectF(x, line.top, x + widths[i] * unit, line.bottom)
                    x += widths[i] * unit
                }
            }
            for (i in start until end) spent += widths[i]
            start = end
            if (start >= n) break
        }
        return out
    }

    /** A line of text found on the screen, and each word's place on it. */
    data class Inked(val rect: RectF, val words: List<RectF>)

    /** One way of having read a text's words onto the words seen on the screen, best so far. */
    private class Read(val cost: Float, val placed: Int, val from: Read?, val token: Int, val col: Int, val cols: Int, val tokens: Int)

    /**
     * A text laid over the words the screen shows, word for word, or null where they cannot be
     * made to agree.
     *
     * What an app says a block holds and what it draws are not the same: a title is drawn up to
     * where it fades out and its text runs on, and the box it says it fills holds the line
     * under it as well. Sharing the text out over the lines' widths then stretches or squeezes
     * every word by however much those differ, and the words are in other words' places. Words
     * are read onto the runs of ink in order instead, each at the width its letters should
     * have at the size the whole line agrees on; a text running out of screen or a screen
     * running out of text leaves the rest alone.
     */
    fun inWords(text: CharSequence, lines: List<Inked>): Array<RectF?>? {
        val n = text.length
        val tokens = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < n) {
            if (text[i].isWhitespace()) { i++; continue }
            val start = i
            while (i < n && !text[i].isWhitespace()) i++
            tokens.add(start to i)
        }
        val cols = lines.flatMapIndexed { at, line -> line.words.map { at to it } }
        if (tokens.size < MIN_READ_WORDS || cols.size < MIN_READ_WORDS) return null
        val wide = FloatArray(tokens.size) { t ->
            var sum = 0f
            for (c in tokens[t].first until tokens[t].second) sum += advance(text[c])
            sum
        }
        val tall = lines.map { it.rect.height() }.sorted().let { it[it.size / 2] }
        var best: Read? = null
        var bestScore = Float.MAX_VALUE
        var scale = tall * SMALLEST_LETTER
        while (scale <= tall * LARGEST_LETTER) {
            val read = readOn(tokens.size, cols, wide, scale)
            if (read != null && read.placed >= MIN_READ_WORDS) {
                val score = read.cost / read.placed
                if (score < bestScore) { bestScore = score; best = read }
            }
            scale *= SCALE_STEP
        }
        val found = best ?: return null
        if (bestScore > WORST_READ) return null
        val out = arrayOfNulls<RectF>(n)
        var at: Read? = found
        while (at != null && at.from != null) {
            if (at.token >= 0) {
                val first = cols[at.col].second
                val last = cols[at.col + at.cols - 1].second
                val row = lines[cols[at.col].first].rect
                val from = tokens[at.token].first
                val to = tokens[at.token + at.tokens - 1].second
                var units = 0f
                for (c in from until to) units += advance(text[c])
                val unit = (last.right - first.left) / units
                var x = first.left
                for (c in from until to) {
                    val w = advance(text[c]) * unit
                    if (!text[c].isWhitespace()) out[c] = RectF(x, row.top, x + w, row.bottom)
                    x += w
                }
            }
            at = at.from
        }
        return out
    }

    /** The cheapest way of reading [count] words onto [cols] at [scale] pixels a unit of width. */
    private fun readOn(
        count: Int,
        cols: List<Pair<Int, RectF>>,
        wide: FloatArray,
        scale: Float,
    ): Read? {
        val m = cols.size
        val table = Array(count + 1) { arrayOfNulls<Read>(m + 1) }
        table[0][0] = Read(0f, 0, null, -1, -1, 0, 0)
        fun put(i: Int, j: Int, read: Read) {
            val was = table[i][j]
            if (was == null || read.cost < was.cost) table[i][j] = read
        }
        fun off(span: Float, units: Float): Float = abs(ln(span / (units * scale)))
        for (i in 0..count) {
            for (j in 0..m) {
                val here = table[i][j] ?: continue
                if (j < m) put(i, j + 1, Read(here.cost + SKIP_COLUMN, here.placed, here, -1, j, 1, 0))
                if (i < count) put(i + 1, j, Read(here.cost + SKIP_WORD, here.placed, here, -1, j, 0, 0))
                if (i == count || j == m) continue
                // A word on a run of ink, or on runs a wide gap between letters broke up.
                for (k in 1..MAX_RUNS_A_WORD) {
                    if (j + k > m || cols[j + k - 1].first != cols[j].first) break
                    val span = cols[j + k - 1].second.right - cols[j].second.left
                    val cost = off(span, wide[i]) + BROKEN_WORD * (k - 1)
                    put(i + 1, j + k, Read(here.cost + cost, here.placed + 1, here, i, j, k, 1))
                }
                // Two words a narrow space left as one run.
                if (i + 1 < count) {
                    val units = wide[i] + wide[i + 1] + advance(' ')
                    val cost = off(cols[j].second.width(), units) + JOINED_WORDS
                    put(i + 2, j + 1, Read(here.cost + cost, here.placed + 2, here, i, j, 1, 2))
                }
            }
        }
        var best: Read? = null
        for (i in 0..count) table[i][m]?.let { if (best == null || it.cost < best!!.cost) best = it }
        for (j in 0..m) table[count][j]?.let { if (best == null || it.cost < best!!.cost) best = it }
        return best
    }

    private const val MIN_READ_WORDS = 2
    private const val MAX_RUNS_A_WORD = 3
    private const val SMALLEST_LETTER = 0.2f
    private const val LARGEST_LETTER = 0.8f
    private const val SCALE_STEP = 1.06f
    private const val SKIP_COLUMN = 0.7f
    private const val SKIP_WORD = 0.9f
    private const val BROKEN_WORD = 0.25f
    private const val JOINED_WORDS = 0.4f
    private const val WORST_READ = 0.45f

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
        sentence: String = "",
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
                        sentence = sentence,
                    ),
                )
            }
        }
    }
}
