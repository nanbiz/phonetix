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
    /** How that meaning is said, where the reader asked for both and the language they read
     *  into has a dictionary here. The sound of the word being given, not of the word read. */
    val glossIpa: String,
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
        // What the core could not answer, carried across both engines.
        //
        // Completing a batch gives back the tokens as they are now, and what is still missing
        // from them is a question the core answers about a fresh batch rather than about this
        // one: filling in how a word is said left a batch that says nothing is missing, and
        // the translator was then asked for nothing at all. Every word no dictionary held
        // went without a meaning on a product whose point is telling a reader what a word
        // means. The browser carries them the same way, in its own host.
        val asked = batch.optJSONArray("misses") ?: JSONArray()
        // What the packs could not say, said by the synthesiser. The core reports what it is
        // missing and only that is asked for, so a word a dictionary answered keeps the
        // pronunciation the dictionary recorded and what a machine produced is marked as a
        // machine's - in the core, which is the one place that decides what a reader is told.
        batch = filled(batch, source, target, accent)
        batch.put("misses", asked)
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
                    glossIpa = row.optString("glossIpa").takeIf { it != "null" }.orEmpty(),
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
    private fun filled(
        batch: JSONObject,
        source: String,
        target: String,
        accent: String,
    ): JSONObject {
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
                Array(kept.size) { "" },
                source,
                target,
                accent,
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
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "MEANT ${words.size} asked, ${said.size} answered")
        }
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
                Array(kept.size) { "" },
                source,
                target,
                "",
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

    /**
     * The word for something the reader wants to say.
     *
     * The other direction, and Taplex's: what is typed is in the language the reader already
     * has, and what comes back is the word in the one they are learning, with that word's own
     * entry under it so a machine's answer can be judged rather than taken.
     *
     * The engine holds one direction open at a time, so this opens the reverse pair, asks, and
     * puts the reading direction back. It costs seconds and is paid while the reader is in
     * this app rather than reading a screen, which is the only place the question is asked
     * from. Nothing comes back where the reverse model was never fetched: the reader is told
     * that, rather than handed their own words back.
     */
    fun say(context: android.content.Context, text: String, source: String, target: String): Answer? {
        val asked = text.trim()
        if (asked.isEmpty() || source.isEmpty() || target.isEmpty() || source == target) {
            return null
        }
        // The dictionaries this phone holds, since the entry under the machine's answer comes
        // out of one: asked from the app's own screen, nothing else has opened them.
        Dictionary.ensureLoaded(context)
        Packs.openHeld(context)
        val models = Packs.models(context)
        // The engine is turned round and turned back under one lock: a screen read while the
        // reverse pair was open would be answered backwards.
        val word = Translator.reversed(models, target, source, listOf(asked))
            .firstOrNull().orEmpty().trim()
        if (word.isEmpty() || word.equals(asked, ignoreCase = true)) return null
        // A machine that answered with several words is answered as a phrase: no dictionary
        // holds one, and a card claiming an entry for it would be claiming one that is not
        // there.
        return if (word.split(Regex("\\s+")).size > 1) {
            phrase(word, source, target)
        } else {
            lookUp(word, source, target)?.takeIf { it.found } ?: phrase(word, source, target)
        }
    }

    /**
     * What several words mean, asked as one thing.
     *
     * A distinct question from a word: no dictionary holds a clause, so the engine is what
     * answers and the card says a machine did. Nothing comes back where the engine has no
     * model for the pair, because a phrase card with the phrase itself on it tells the reader
     * nothing they were not already looking at.
     */
    fun phrase(text: String, source: String, target: String): Answer? {
        val asked = text.trim()
        if (asked.isEmpty() || source == target) return null
        val said = Translator.lines(listOf(asked)).firstOrNull().orEmpty()
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "PHRASE $source->$target usable=${Translator.usable} $asked = $said",
            )
        }
        if (said.isEmpty() || said.equals(asked, ignoreCase = true)) return null
        val written = runCatching { Lex.phrase(asked, said, source, target) }.getOrNull()
            ?: return null
        return Answer.parse(written)
    }
}
