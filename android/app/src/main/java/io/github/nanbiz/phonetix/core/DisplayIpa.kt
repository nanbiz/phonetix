package io.github.nanbiz.phonetix.core

/**
 * How a transcription is shown in running text, as against on the card.
 *
 * The card carries the full form; the line over a word is stripped to what a reader wants
 * mid-sentence. Which marks that means is the core's answer, so the browser and the phone
 * cannot show different things for the same word.
 */
object DisplayIpa {

    fun display(ipa: String, narrow: Boolean = false): String =
        IpaSymbols.display(ipa, narrow)
}
