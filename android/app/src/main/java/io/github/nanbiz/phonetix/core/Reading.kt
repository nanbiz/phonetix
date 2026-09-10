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
        accent: String = "",
    ): List<Annotated> {
        if (core == 0L || texts.isEmpty()) {
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG && core == 0L) {
                android.util.Log.d("Phonetix", "ANNOTATE asked with no core open")
            }
            return emptyList()
        }
        val written = Lex.annotate(
            core, texts.toTypedArray(), source, target, mode, density, narrow, hideStress,
            accent,
        )
        var batch = JSONObject(written)
        // What the packs could not say, said by the synthesiser. The core reports what it is
        // missing and only that is asked for, so a word a dictionary answered keeps the
        // pronunciation the dictionary recorded and what a machine produced is marked as a
        // machine's - in the core, which is the one place that decides what a reader is told.
        batch = filled(batch, source, accent)
        // And what no dictionary could translate, translated. The engine answers only where a
        // reader has chosen a language to read into and the model for that direction is here.
        batch = meant(batch, source, target)
        val tokens = batch.optJSONArray("tokens") ?: JSONArray()
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "ANNOTATED ${tokens.length()} tokens from ${texts.size} lines, " +
                    "first=${tokens.optJSONObject(0)}",
            )
        }
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
     * Fill in how the words no pack could say are said.
     *
     * Nothing happens where the engine did not start: those words stay bare, exactly as they
     * read before the phone had one.
     */
    private fun filled(batch: JSONObject, source: String, accent: String): JSONObject {
        if (!Speech.usable) return batch
        val misses = batch.optJSONArray("misses") ?: return batch
        val tokens = batch.optJSONArray("tokens") ?: return batch
        val wanted = ArrayList<Int>(misses.length())
        val words = ArrayList<String>(misses.length())
        for (at in 0 until misses.length()) {
            val row = misses.optJSONObject(at) ?: continue
            if (row.optString("need") == "Gloss") continue
            val which = row.optInt("token")
            val token = tokens.optJSONObject(which) ?: continue
            wanted.add(which)
            words.add(token.optString("spelling"))
        }
        if (wanted.isEmpty()) return batch
        val said = Speech.phonemes(Accents.voiceOf(source, accent), words)
        if (said.isEmpty()) return batch
        val kept = ArrayList<Int>(wanted.size)
        val sounds = ArrayList<String>(wanted.size)
        for (at in wanted.indices) {
            val ipa = said[words[at]] ?: continue
            kept.add(wanted[at])
            sounds.add(ipa)
        }
        if (kept.isEmpty()) return batch
        val written = runCatching {
            Lex.complete(
                core,
                batch.optLong("batch"),
                kept.toIntArray(),
                Array(kept.size) { "" },
                sounds.toTypedArray(),
                "espeak",
            )
        }.getOrNull() ?: return batch
        return runCatching { JSONObject(written) }.getOrDefault(batch)
    }

    /**
     * Fill in what the packs could not translate.
     *
     * The dictionary answers first and this fills the rest: a word with no entry, a pair no
     * pack covers. What comes back is a machine's guess and is marked as one by the core, so
     * a reader is told which of the two answered.
     */
    private fun meant(batch: JSONObject, source: String, target: String): JSONObject {
        if (!Translator.usable || target.isEmpty() || target == source) return batch
        val misses = batch.optJSONArray("misses") ?: return batch
        val tokens = batch.optJSONArray("tokens") ?: return batch
        val wanted = ArrayList<Int>(misses.length())
        val words = ArrayList<String>(misses.length())
        for (at in 0 until misses.length()) {
            val row = misses.optJSONObject(at) ?: continue
            if (row.optString("need") == "Ipa") continue
            val which = row.optInt("token")
            val token = tokens.optJSONObject(which) ?: continue
            wanted.add(which)
            words.add(token.optString("spelling"))
        }
        if (wanted.isEmpty()) return batch
        val said = Translator.meanings(words)
        if (said.isEmpty()) return batch
        val kept = ArrayList<Int>(wanted.size)
        val meanings = ArrayList<String>(wanted.size)
        for (at in wanted.indices) {
            val gloss = said[words[at]] ?: continue
            kept.add(wanted[at])
            meanings.add(gloss)
        }
        if (kept.isEmpty()) return batch
        val written = runCatching {
            Lex.complete(
                core,
                batch.optLong("batch"),
                kept.toIntArray(),
                meanings.toTypedArray(),
                Array(kept.size) { "" },
                "bergamot",
            )
        }.getOrNull() ?: return batch
        return runCatching { JSONObject(written) }.getOrDefault(batch)
    }

    /**
     * What one word means, as the cascade answers it.
     *
     * The card is the surface that shows a translation, so it asks for one rather than being
     * handed a transcription: with no pack for the pair the cascade says so, and the card
     * shows what it does know.
     */
    fun lookUp(
        word: String,
        source: String,
        target: String,
        accent: String = "",
        /** The word before it on the screen, which decides a spelling that is several words. */
        before: String = "",
    ): Answer? {
        if (core == 0L || word.isBlank()) return null
        val written = runCatching { Lex.lookUp(core, word, source, target, accent, before) }
            .getOrNull()
            ?: return null
        return Answer.parse(written)
    }
}
