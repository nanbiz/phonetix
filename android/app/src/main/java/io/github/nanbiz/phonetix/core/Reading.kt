package io.github.nanbiz.phonetix.core

import org.json.JSONArray
import org.json.JSONObject

/** One word of a run: where it sits in that run's text, and what is drawn over it. */
data class Annotated(
    val run: Int,
    /** UTF-16 offsets into the run's own text, which is how this side indexes it. */
    val start: Int,
    val end: Int,
    val spelling: String,
    /** How it is said, already shown the way the reader asked. Empty when nothing said it. */
    val ipa: String,
    /** What it means, cut to what an inline annotation can carry. Empty when nothing does. */
    val gloss: String,
    /** Whether this occurrence is one the inline layer draws. */
    val inline: Boolean,
)

/**
 * What a screenful of text gets drawn on it, decided by the core.
 *
 * Finding the text is this side's work, since an accessibility tree is nothing like a
 * document. Which words are worth annotating, what they mean and how they are said is the
 * core's, so a phone and a browser reading the same sentence annotate the same words.
 */
object Reading {

    /** The core the overlay keeps open, or 0 when nothing has opened one yet. */
    @Volatile
    var core: Long = 0L

    /** Annotate a screenful. The runs come back keyed by their position in [texts]. */
    fun annotate(
        texts: List<String>,
        source: String,
        target: String,
        mode: String,
        density: Int,
        narrow: Boolean = false,
        hideStress: Boolean = true,
    ): List<Annotated> {
        if (core == 0L || texts.isEmpty()) return emptyList()
        val written = Lex.annotate(
            core, texts.toTypedArray(), source, target, mode, density, narrow, hideStress,
        )
        val batch = JSONObject(written)
        val tokens = batch.optJSONArray("tokens") ?: JSONArray()
        return (0 until tokens.length()).mapNotNull { at ->
            tokens.optJSONObject(at)?.let { row ->
                Annotated(
                    run = row.optInt("run"),
                    start = row.optInt("start"),
                    end = row.optInt("end"),
                    spelling = row.optString("spelling"),
                    ipa = row.optString("ipa").takeIf { it != "null" }.orEmpty(),
                    gloss = row.optString("gloss").takeIf { it != "null" }.orEmpty(),
                    inline = row.optBoolean("inline"),
                )
            }
        }
    }

    /**
     * What one word means, as the cascade answers it.
     *
     * The card is the surface that shows a translation, so it asks for one rather than being
     * handed a transcription: with no pack for the pair the cascade says so, and the card
     * shows what it does know.
     */
    fun lookUp(word: String, source: String, target: String): Answer? {
        if (core == 0L || word.isBlank()) return null
        val written = runCatching { Lex.lookUp(core, word, source, target) }.getOrNull()
            ?: return null
        return Answer.parse(written)
    }
}
