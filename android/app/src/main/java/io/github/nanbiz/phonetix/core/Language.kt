package io.github.nanbiz.phonetix.core

/**
 * Whether the screen in front of the reader is in the language the dictionary is for.
 *
 * There is one dictionary here, English, and until now every word on the screen was looked up
 * in it. That put English pronunciations through German sentences: a reader in Germany got them
 * on "Song" and "Video", and on "war", "hat" and "man", which are German words that happen to
 * be spelled like English ones.
 *
 * The question is asked of the screen, not of each line. [Eld] is a good judge of a paragraph
 * and a poor one of a handful of words: asked about "zebra anchor basket copper diamond engine
 * falcon granite" it answers Italian, and says it is sure. Those are English nouns. A line of a
 * page carries no function words to speak of, which is what a detector reads a language from,
 * and suppressing lines one at a time on that evidence left an English page with most of its
 * words untranscribed - a far worse fault than transcribing a German one.
 *
 * All the text on the screen together does carry them, and that is the same thing the extension
 * leans on when a block is too short to judge: the language of the page it is on.
 */
object Language {

    /** The text of a screen, gathered while it is read, and what it turned out to be. */
    class Reading(val language: String?, val words: Int) {
        val silent: Boolean get() = language == null
    }

    val nothing = Reading(null, 0)

    /**
     * Gathers the words of a screen as its lines are read, and answers for the whole of it.
     *
     * Bounded: a detector reads the first thousand characters of what it is given, and a
     * screenful of text is more than enough to say what language it is in.
     */
    class Screen {
        private val text = StringBuilder(ENOUGH_TEXT)
        private var words = 0

        fun add(line: CharSequence) {
            Transcriber.scanWords(line) { i, j -> if (j - i >= MIN_WORD) words++ }
            if (text.length >= ENOUGH_TEXT) return
            if (text.isNotEmpty()) text.append(' ')
            text.append(line, 0, minOf(line.length, ENOUGH_TEXT - text.length))
        }

        /** What the screen is in, or nothing when it did not say. */
        fun read(): Reading {
            if (words < ENOUGH_WORDS) return Reading(null, words)
            val guess = Eld.detect(text)
            return Reading(if (guess.reliable) guess.language else null, words)
        }
    }

    /** Whether a screen that reads like this is worth transcribing. */
    fun ours(screen: Reading): Boolean = screen.silent || screen.language == OURS

    /** The language the dictionary is for. */
    const val OURS = "en"

    /** Shorter than this is not a word for these purposes, matching what is transcribed. */
    const val MIN_WORD = 2

    /** Below this there is not enough on the screen to judge it by, and the words are
     *  transcribed as they were before any of this could tell one language from another. */
    const val ENOUGH_WORDS = 8

    /** How much of a screen is read. A detector looks at the first thousand characters. */
    const val ENOUGH_TEXT = 1000
}
