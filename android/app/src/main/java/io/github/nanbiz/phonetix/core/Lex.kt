package io.github.nanbiz.phonetix.core

/**
 * The overlay's way into the reading core.
 *
 * The core is one Rust crate compiled twice, once to WebAssembly for the browser extension and
 * once to a native library per Android ABI. Everything about what a word means and how it is
 * said lives there, so neither platform can answer differently from the other, and neither can
 * reach a pack any other way.
 *
 * The boundary is crossed per batch and never per word: reading the accessibility tree and
 * placing boxes stays on this side and runs many times a second, while the core is asked only
 * when the set of text on the screen has changed.
 */
object Lex {

    /** Whether the native library is present, since a build without it must degrade rather
     *  than crash the service that is painting somebody else's screen. */
    val ready: Boolean = runCatching { System.loadLibrary("lexcore_android") }.isSuccess

    /** How many terms two English glosses share, which is how a word in one language is
     *  matched to a word in another. */
    external fun overlap(source: String, target: String): Long

    /** The candidate sharing most, or -1 when the best does not clearly beat the rest, because
     *  an ambiguous match yields no dictionary answer rather than a confident wrong one. */
    external fun bestOf(source: String, candidates: Array<String>, margin: Int): Int
}
