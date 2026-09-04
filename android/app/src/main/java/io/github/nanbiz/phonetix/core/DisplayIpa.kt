package io.github.nanbiz.phonetix.core

/**
 * How a transcription is shown in running text, as against in the tooltip.
 *
 * The tooltip carries the full form; the text over a word is stripped to what a reader
 * wants mid-sentence. Ported from src/lib/display-ipa.ts and held to it by
 * DisplayIpaParityTest, because the two showing different things for the same word is
 * exactly the kind of drift nothing else would report.
 */
object DisplayIpa {

    /** Primary and secondary stress. */
    private val STRESS = Regex("[ˈˌ]")

    /**
     * Diacritics that mark narrow phonetic detail rather than which sound is meant.
     * "cause" is /kɔːz/ broadly and [kʰoːz̥] narrowly; dropping these turns the second back
     * into the first. Length, nasalization and syllabicity stay: they change the sound.
     */
    private val NARROW_DETAIL = Regex("[ʰʱ̥̬̝̞̟̠̪̺̻̊̈̚˞ˠ̴̘̙̹̜]")

    /** A syllable break, and the brackets around a length that may or may not be held. */
    private val SYLLABLE_BREAK = Regex("\\.")
    private val OPTIONAL_LENGTH = Regex("\\(([ːˑ])\\)")

    fun display(ipa: String, narrow: Boolean = false, hideStress: Boolean = true): String {
        var out = ipa
        if (!narrow) out = NARROW_DETAIL.replace(out, "")
        if (hideStress) out = STRESS.replace(out, "")
        return SYLLABLE_BREAK.replace(OPTIONAL_LENGTH.replace(out, "$1"), "")
    }
}
