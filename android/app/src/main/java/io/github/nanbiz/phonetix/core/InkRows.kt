package io.github.nanbiz.phonetix.core

/**
 * The rows of text a piece of the screen really shows, found in its pixels.
 *
 * An app that will not say where the characters of a block are leaves only the block's box,
 * and the box says little about where its text is drawn: YouTube's captions are a box the
 * width of the player with two short rows centred in it, each on a strip of its own. Laid out
 * from the box alone they came out as one long row from the left edge, and a finger on the
 * first word of a caption was on the third or on nothing.
 *
 * Text is where the picture changes sharply and often: the edge of every stroke of every
 * letter is a step between ink and the ground under it, however either is coloured. Sky, a
 * gradient, a photo out of focus has few such steps. So a row of pixels with many steps is a
 * row of text, rows of them together are a line, and across a line the columns holding steps
 * say where it starts and where it ends.
 */
object InkRows {

    /** One row of text: its top and bottom, its first and last column, all inclusive, and the
     *  first and last column of each run of ink on it, which is a word. */
    data class Band(
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
        val words: List<Pair<Int, Int>> = emptyList(),
    )

    /** How far apart the two pixels compared for a step are. A stroke is two to four pixels
     *  wide at any reading size, so a step is always crossed in this. */
    private const val SPAN = 2

    /** How much lighter or darker the one is than the other for a step: a third of the way
     *  from black to white. Captions, white on a dark strip, are most of the way. */
    private const val STEP = 96

    /** How many steps a row of pixels has, per hundred pixels of it, before it is text. */
    private const val STEPS_PER_HUNDRED = 2

    /** How short a run of rows can be and still be a line: an underline or a rule is one or
     *  two pixels, letters are never fewer than six. */
    private const val SHORTEST_LINE = 6

    /** How much brightness a column spans to have ink in it, and the narrowest stretch without
     *  any that can be the space between two words, in pixels. */
    private const val INK_RANGE = 64
    private const val MIN_SPACE = 4

    /** How far up a line's gaps, sorted by width, the width of a space is read; and the share
     *  of that width a gap is a space from. */
    private const val WIDE_GAP = 0.9f
    private const val SPACE_TO_WIDE = 0.7f

    /**
     * The lines in a grid of luminance, top to bottom.
     *
     * @param lum luminance from 0 to 255, row after row, [width] to a row.
     * @param gap the widest stretch without a step inside one line, in pixels: the space
     *   between two words is well under a row's height, and anything apart by more is something
     *   else beside the line - a button, the picture behind a caption.
     */
    fun bands(lum: IntArray, width: Int, height: Int, gap: Int): List<Band> {
        if (width <= SPAN || height <= 0 || lum.size < width * height) return emptyList()
        // Which pixels start a step, row by row.
        val stepAt = BooleanArray(width * height)
        val perRow = IntArray(height)
        for (y in 0 until height) {
            val row = y * width
            var count = 0
            for (x in 0 until width - SPAN) {
                if (kotlin.math.abs(lum[row + x + SPAN] - lum[row + x]) >= STEP) {
                    stepAt[row + x] = true
                    count++
                }
            }
            perRow[y] = count
        }
        val enough = maxOf(4, width * STEPS_PER_HUNDRED / 100)
        val out = ArrayList<Band>()
        var y = 0
        while (y < height) {
            if (perRow[y] < enough) { y++; continue }
            val top = y
            while (y < height && perRow[y] >= enough) y++
            val bottom = y - 1
            if (bottom - top + 1 < SHORTEST_LINE) continue
            span(stepAt, width, top, bottom, gap)?.let { (left, right) ->
                out.add(Band(top, bottom, left, right, words(lum, width, top, bottom, left, right)))
            }
        }
        return out
    }

    /**
     * The words of a line: runs of columns holding ink, apart by more than letters are.
     *
     * Ink rather than steps, which the inside of a wide letter has none of. A column of nothing
     * but the ground under the text has one brightness from top to bottom; any stroke through
     * it makes two.
     *
     * How far apart letters and words are depends on the font, and in a bold one the letters
     * of a word can be nearly as far apart as the words: so each line is judged by its own
     * widest gaps, which are the spaces between its words.
     */
    private fun words(lum: IntArray, width: Int, top: Int, bottom: Int, left: Int, right: Int): List<Pair<Int, Int>> {
        val runs = ArrayList<Pair<Int, Int>>()
        var start = -1
        var last = -1
        for (x in left..right) {
            var low = 255
            var high = 0
            for (y in top..bottom) {
                val v = lum[y * width + x]
                if (v < low) low = v
                if (v > high) high = v
            }
            if (high - low < INK_RANGE) continue
            if (start >= 0 && x - last > 1) {
                runs.add(start to last)
                start = x
            }
            if (start < 0) start = x
            last = x
        }
        if (start >= 0) runs.add(start to last)
        if (runs.size < 2) return runs
        val gaps = runs.zipWithNext { a, b -> b.first - a.second - 1 }.sorted()
        val wide = gaps[((gaps.size - 1) * WIDE_GAP).toInt()]
        val space = maxOf(MIN_SPACE, kotlin.math.ceil(wide * SPACE_TO_WIDE).toInt() + 1)
        val out = ArrayList<Pair<Int, Int>>()
        var word = runs[0]
        for (run in runs.drop(1)) {
            if (run.first - word.second - 1 >= space) {
                out.add(word)
                word = run
            } else {
                word = word.first to run.second
            }
        }
        out.add(word)
        return out
    }

    /** The first and last column of the line between [top] and [bottom]: the run of columns
     *  with steps in them, no gap in it wider than [gap], that holds the most steps. */
    private fun span(stepAt: BooleanArray, width: Int, top: Int, bottom: Int, gap: Int): Pair<Int, Int>? {
        val perColumn = IntArray(width)
        for (y in top..bottom) {
            val row = y * width
            for (x in 0 until width) if (stepAt[row + x]) perColumn[x]++
        }
        var best: Pair<Int, Int>? = null
        var bestSteps = 0
        var x = 0
        while (x < width) {
            if (perColumn[x] == 0) { x++; continue }
            val left = x
            var right = x
            var steps = 0
            var empty = 0
            while (x < width && empty <= gap) {
                if (perColumn[x] > 0) {
                    right = x
                    steps += perColumn[x]
                    empty = 0
                } else {
                    empty++
                }
                x++
            }
            if (steps > bestSteps) {
                bestSteps = steps
                // The step is counted where it starts; the stroke it is the edge of reaches
                // the pixel compared with.
                best = left to minOf(width - 1, right + SPAN)
            }
        }
        return best
    }
}
