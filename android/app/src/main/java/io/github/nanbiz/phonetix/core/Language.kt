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

        /** How much of the screen's text each language that a line was found in holds. */
        private val held = HashMap<String, Int>()
        private var judged = 0

        fun add(line: CharSequence) {
            // Each line that says enough is judged on its own and counts for as much text as
            // it has. The first thousand characters alone were the chat's English title and
            // the app's notices, and a screen holding a German answer was read as English.
            if (line.length >= LINE_SAYS_ENOUGH && judged < LINES_JUDGED) {
                judged += 1
                Eld.readScreen(line).language?.let { held[it] = (held[it] ?: 0) + line.length }
            }
            if (text.length >= ENOUGH_TEXT) return
            if (text.isNotEmpty()) text.append(' ')
            text.append(line, 0, minOf(line.length, ENOUGH_TEXT - text.length))
        }

        /** What the screen is in, or nothing when it did not say.
         *
         *  The rule is the core's, thresholds included: a phone that wanted eight words and
         *  a browser that wanted three would treat the same screen differently. Where lines
         *  were judged, the language most of the text is in; otherwise the screen as a
         *  whole. */
        fun read(): Reading {
            val read = Eld.readScreen(text)
            val most = held.maxByOrNull { it.value }?.key
            return Reading(most ?: read.language, read.words)
        }
    }

    /** How long a line has to be to be judged on its own, as the core judges a line. */
    const val LINE_SAYS_ENOUGH = 24

    /** How many lines of a screen are judged, which is as many as a screen holds. */
    const val LINES_JUDGED = 60

    /** Whether a screen that reads like this is worth transcribing. */
    fun ours(screen: Reading): Boolean = screen.silent || screen.language == OURS

    /** The language the dictionary is for. */
    const val OURS = "en"

    /** Shorter than this is not a word for these purposes, matching what is transcribed. */
    const val MIN_WORD = 2

    /** How much of a screen is read. A detector looks at the first thousand characters. */
    const val ENOUGH_TEXT = 1000
}
