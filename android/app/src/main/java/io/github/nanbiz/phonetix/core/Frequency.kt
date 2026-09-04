package io.github.nanbiz.phonetix.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The frequency control and the word choice behind it, kept identical to the browser
 * extension so the same setting means the same thing on both.
 *
 * The bar controls one word in N, and frequency is felt in ratios rather than in N: the
 * step from 1-in-2 to 1-in-4 is a world apart, 1-in-40 to 1-in-42 is nothing. A straight
 * linear N spends most of its travel among sparse densities no one can tell apart, so the
 * curve is bent partway toward geometric — far enough to read as logarithmic, not so far
 * that it feels extreme.
 */
object Frequency {
    const val DMIN = 2
    const val DMAX = 50
    private const val LOG_MIX = 0.6

    /** pos 0 = sparse (1 in 50), 1 = dense (1 in 2). */
    fun densityForPos(t: Float): Int {
        val tt = t.coerceIn(0f, 1f).toDouble()
        val lin = DMAX - tt * (DMAX - DMIN)
        val geo = DMAX * (DMIN.toDouble() / DMAX).pow(tt)
        return ((1 - LOG_MIX) * lin + LOG_MIX * geo).roundToInt().coerceIn(DMIN, DMAX)
    }

    /** Inverse, by scan, so it stays exactly consistent with the curve above. */
    fun posForDensity(d: Int): Float {
        var best = 0
        var bestErr = Int.MAX_VALUE
        for (i in 0..100) {
            val err = kotlin.math.abs(densityForPos(i / 100f) - d)
            if (err < bestErr) { bestErr = err; best = i }
        }
        return best / 100f
    }

    fun label(d: Int): String = "1 in $d  ·  ${(100.0 / d).roundToInt()}%"

    /**
     * FNV-1a, so a word is picked the same way on every pass and nothing flickers.
     *
     * Returned as an unsigned value in a Long. The extension ends its hash with `>>> 0`,
     * so it takes the remainder of a number in 0..2^32-1; an Int here would carry the same
     * bits but a negative sign, and the remainder of a negative number is a different word
     * chosen. SprinkleParityTest is what caught that.
     */
    private fun hash(s: String): Long {
        var h = -2128831035 // 2166136261 as a signed Int; the bits are what matter
        for (c in s) {
            h = h xor c.code
            h *= 16777619
        }
        return h.toLong() and 0xFFFFFFFFL
    }

    /**
     * Whether this occurrence of a word is one of the transcribed ones.
     *
     * Two things shape it, both carried over from the extension. Rarer words are the ones
     * worth stopping on, so the gate favours them, with length standing in for rarity
     * (function words are short, the words a reader wants to learn run longer). And the
     * gate keys on the word's occurrence index, so repeats of one word are decided
     * independently and land as a mix down the screen rather than all-or-nothing.
     */
    fun picks(word: String, occurrence: Int, density: Int): Boolean {
        val boost = min(2.4, max(0.6, word.length / 5.0))
        val n = max(1, (density / boost).roundToInt())
        return hash("$word#$occurrence") % n == 0L
    }
}
