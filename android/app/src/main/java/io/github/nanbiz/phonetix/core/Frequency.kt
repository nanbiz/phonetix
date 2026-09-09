package io.github.nanbiz.phonetix.core

import kotlin.math.roundToInt

/**
 * The reader's frequency bar, as the core decides it.
 *
 * Which words are annotated and what a position on the bar means are one rule, and it lives
 * in the core so that the same setting means the same thing on a phone and in a browser. What
 * is left here is the wording on the screen.
 */
object Frequency {
    const val DMIN = 1
    const val DMAX = 50

    /** pos 0 = sparse (1 in 50), 1 = dense (every word). */
    fun densityForPos(t: Float): Int = Lex.densityForPos(t)

    /** Where on the bar a density sits. */
    fun posForDensity(d: Int): Float = Lex.posForDensity(d)

    fun label(d: Int): String =
        if (d <= DMIN) "Every word" else "1 in $d  ·  ${(100.0 / d).roundToInt()}%"

    /** Whether this occurrence of a word is one of the annotated ones. */
    fun picks(word: String, occurrence: Int, density: Int): Boolean =
        Lex.picks(word, occurrence, density)
}
