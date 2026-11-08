package io.github.nanbiz.phonetix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A caption as YouTube draws it: a box the width of the player, sky in it, and two short rows
 * of white letters on dark strips centred in it. The lines have to come out as those two rows,
 * where they are, and the sky has to stay out of them.
 */
class InkRowsTest {

    private val width = 400
    private val height = 60

    /** A frame: sky brightening from left to right, with strips of letters painted on it. */
    private fun frame(vararg strips: IntArray): IntArray {
        val lum = IntArray(width * height) { 170 + (it % width) * 60 / width }
        for ((top, bottom, left, right) in strips.map { it.toList() }) {
            for (y in top..bottom) for (x in left..right) {
                // A dark strip with a three-pixel stroke every eight pixels, and a word space
                // of twenty-four pixels every sixty.
                val inWord = (x - left) % 60 < 36
                lum[y * width + x] = if (inWord && (x - left) % 8 < 3 && y > top + 1 && y < bottom - 1) 240 else 20
            }
        }
        return lum
    }

    @Test
    fun finds_two_centred_rows_and_leaves_the_sky_out() {
        val lum = frame(intArrayOf(8, 24, 120, 280), intArrayOf(32, 48, 150, 250))
        val bands = InkRows.bands(lum, width, height, gap = 30)
        assertEquals(2, bands.size)
        val (first, second) = bands
        assertTrue("first row starts near 120: $first", first.left in 118..126)
        assertTrue("first row ends near 280: $first", first.right in 270..282)
        assertTrue("second row starts near 150: $second", second.left in 148..156)
        assertTrue("second row ends near 250: $second", second.right in 240..252)
        assertTrue("rows in order: $bands", first.bottom < second.top)
    }

    @Test
    fun sky_alone_is_no_line() {
        assertEquals(emptyList<InkRows.Band>(), InkRows.bands(frame(), width, height, gap = 30))
    }

    @Test
    fun something_beside_a_line_stays_out_of_it() {
        // The row, and far off to its right a short bright mark: a button, a logo.
        val lum = frame(intArrayOf(10, 30, 40, 200))
        for (y in 12..28) for (x in 360..366) lum[y * width + x] = if (x % 6 < 2) 250 else 10
        val bands = InkRows.bands(lum, width, height, gap = 30)
        assertEquals(1, bands.size)
        assertTrue("the line ends at its own last letter: ${bands[0]}", bands[0].right < 210)
    }
}
