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

    init {
        // The core is not optional: what a word means, how it is said and which words are
        // annotated are all decided in it. A build that cannot load it is a broken build,
        // and saying so here is better than a service that quietly shows nothing.
        // The synthesiser first: the core links against it, so it has to be in the process
        // before the core is loaded rather than when the first word needs saying.
        runCatching { System.loadLibrary("espeak-ng") }
        System.loadLibrary("lexcore_android")
        // The translation engine is its own library: the calls below reach it directly rather
        // than through the core, because what it needs is a C++ interface and the core is
        // Rust. A phone without it reads exactly as it did before there was one.
        runCatching { System.loadLibrary("phonetix_translate") }
    }

    /** How many terms two English glosses share, which is how a word in one language is
     *  matched to a word in another. */
    external fun overlap(source: String, target: String): Long

    /** The candidate sharing most, or -1 when the best does not clearly beat the rest, because
     *  an ambiguous match yields no dictionary answer rather than a confident wrong one. */
    external fun bestOf(source: String, candidates: Array<String>, margin: Int): Int

    /**
     * Make a core. What comes back is a pointer the calls below are handed, and it belongs to
     * this side: nothing frees it but [close].
     *
     * One per service rather than one for the process, because two services sharing a global
     * would share whatever packs either of them opened.
     */
    external fun open(): Long

    /** Let a core go. Nothing may be asked of it afterwards. */
    external fun close(core: Long)

    /**
     * Read a pack off the disk into a core, and say which language it turned out to be for.
     * Empty when the file is not a pack this build can read.
     *
     * The path rather than the bytes: the pack is a file the app owns and can be tens of
     * megabytes, and handing those through the boundary to read them back out is work with
     * nothing to show for it.
     */
    external fun openPack(core: Long, path: String): String

    /** One of the dictionaries the app carries, turned into a pack it can read. The bytes in
     *  are the gzipped map as it ships; the bytes out are a pack, or nothing where the file is
     *  not one of ours. */
    external fun buildIpaPack(lang: String, gzipped: ByteArray, built: Long): ByteArray

    /**
     * What one word means, as JSON.
     *
     * JSON because an answer is a tree and the boundary carries text; the shape is written in
     * one place in the core, so this side and the browser read the same one.
     *
     * The accent is passed with the languages because it changes the answer: the core says a
     * word the way the reader's accent says it, from that accent's pack where it has one and
     * from its rule where it does not. Empty for the standard reading.
     */
    external fun lookUp(
        core: Long,
        spelling: String,
        source: String,
        target: String,
        accent: String,
        /** The word before it on the screen, which decides a spelling that is several words. */
        before: String,
    ): String

    /** Several words asked as one, as JSON. No dictionary holds a phrase, so what answers it
     *  is the engine; [said] is what the engine made of [text], and the core is what marks the
     *  answer as a machine's. */
    external fun phrase(text: String, said: String, source: String, target: String): String

    /** A transcription, symbol by symbol, as JSON: what each sound is called and where to
     *  read about it. The table is the core's, so both platforms name a sound the same. */
    external fun symbols(ipa: String): String

    /** A transcription as it is shown over a word, given the reader's own settings. The card
     *  always carries the full form. */
    external fun display(ipa: String, narrow: Boolean, hideStress: Boolean): String

    /** Whether this occurrence of a word is one the inline layer draws. */
    external fun picks(word: String, occurrence: Int, density: Int): Boolean

    /** What a position on the reader's frequency bar means, as one word in every N. */
    external fun densityForPos(position: Float): Int

    /** Where on that bar a density sits. */
    external fun posForDensity(density: Int): Float

    /**
     * Annotate a screenful of text: one token per word, as JSON.
     *
     * The whole screen in one call, because which words are annotated depends on how often
     * each has already appeared; a call per node would count from zero each time and annotate
     * the same word wherever it turned up.
     */
    external fun annotate(
        core: Long,
        texts: Array<String>,
        source: String,
        target: String,
        mode: String,
        density: Int,
        narrow: Boolean,
        hideStress: Boolean,
        accent: String,
    ): String

    /** Read the language model into a core; the count of languages, or 0 when it is not one. */
    external fun openModel(core: Long, path: String): Int

    /** What language a piece of text is in, as JSON. */
    external fun detect(core: Long, text: String): String

    /** What a screenful of text is in, and whether it said enough to judge. */
    external fun readScreen(core: Long, text: String): String

    /** Give up a pack, so a dictionary the reader deleted stops answering. */
    external fun closePack(core: Long, lang: String)

    /** What a Wiktionary page says about a word in one language, as JSON. */
    external fun readWiktionary(wikitext: String, lang: String): String

    /**
     * Start the synthesiser against the data unpacked from the apk.
     *
     * Returns whether it is usable. Where it is not, the words no pack holds stay bare, which
     * is exactly how the app read before there was one.
     */
    external fun speechStart(data: String): Int

    /**
     * How a batch of words is said, in one voice.
     *
     * A batch because a screen is a batch: a call per word would cross into the core a hundred
     * times for one page.
     */
    external fun speechPhonemes(voice: String, words: Array<String>): Array<String>

    /**
     * Fill in what an engine answered about the words the packs missed.
     *
     * The answers go back through the core so one answer still drives the page, the card and
     * the audio, and a machine's answer is marked as one in the place that decides that.
     */
    external fun complete(
        core: Long,
        batch: Long,
        tokens: IntArray,
        glosses: Array<String>,
        ipas: Array<String>,
        /** Each token's whole line as the engine translated it, where that was asked for. */
        sentences: Array<String>,
        /** The languages the batch was drawn between, so a word read again in the light of
         *  its translated line is read by the same cascade with the same packs open. */
        source: String,
        target: String,
        accent: String,
        engine: String,
    ): String

    /**
     * One word spoken, as the bytes of a WAV file.
     *
     * The same engine and the same voice the browser uses, so a word does not sound like two
     * different products depending on where a reader met it.
     */
    external fun speechSay(voice: String, word: String): ByteArray

    /**
     * Open a translation direction, from a configuration naming files already on disk.
     *
     * Returns whether it can answer. A direction with no model is one this reader cannot
     * translate, which is an ordinary answer: the words stay as the dictionary left them.
     */
    external fun translateOpen(config: String): Int

    /** Whether a direction is open, so nothing offers what it cannot do. */
    external fun translateReady(): Int

    /**
     * Translate a batch, in the order it was given.
     *
     * A batch because a screen is a batch, and the same reason the synthesiser takes one.
     */
    external fun translateSay(texts: Array<String>): Array<String>

    /**
     * Read a language's homograph classifier off the disk. Returns how many spellings it knows.
     *
     * A trained decision list for the words a language writes the same and says differently.
     * Optional: without one a homograph is still decided by the word before it where that
     * decides, and asked about where it does not.
     */
    external fun openHomographs(core: Long, lang: String, path: String): Int
}
