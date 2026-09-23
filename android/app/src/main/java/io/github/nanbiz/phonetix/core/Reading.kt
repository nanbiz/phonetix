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
        // Which word a spelling is, where its line translated says: first, so that what the
        // other two engines are asked about is what the reader will see.
        batch = settled(batch, texts, source, target)
        batch.put("misses", asked)
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

    /** What a miss asks for that a voice can answer, and what a translator can. */
    private val SOUND = setOf("Ipa", "Both")
    private val MEANING = setOf("Gloss", "Both")

    /**
     * Lines as the engine translated them, keyed by direction and text, most recently used
     * last. A screen is read again many times a second while it moves, and the same lines come
     * back each time: translated once, they are answered from here after that.
     */
    private val translated = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > LINES_KEPT
    }
    private val translating = HashSet<String>()

    /** The lines the last read asked about. A line that has gone from the screen by the time
     *  its turn comes is not translated: a backlog of lines from pages already left kept
     *  reading the screen again long after, and each of those reads cleared whatever else was
     *  waiting on the reading thread - a card's lookup among it. */
    @Volatile
    private var onScreen: Set<String> = emptySet()
    private const val LINES_KEPT = 512

    /** Where lines asked about a word's senses are translated, away from the read. */
    private val lineWorker = java.util.concurrent.Executors.newSingleThreadExecutor { job ->
        Thread(job, "phonetix-lines").apply { isDaemon = true }
    }

    /** Told when lines translated in the background have arrived, so the screen is read again
     *  and drawn with them. */
    @Volatile
    var onLinesArrived: (() -> Unit)? = null

    /**
     * Which word a spelling is, and which of its senses, where the line it is on can say.
     *
     * French "est" is "east" or "is", with nothing on the page deciding which, and the core
     * asks for the line translated before it will say. "banco" is decided - the noun - but is a
     * bank and a bench, and the core asks for the line too, to draw the one it is about. The
     * engine reads the whole line, and the core compares what each reading and each sense
     * means with what it wrote. One translation per line, however many words on it asked.
     *
     * Neither holds the screen up. With the published dictionaries nearly every line has a
     * word of one kind or the other, and translating a new screen's lines before drawing it
     * was a second and a half on the emulator for seventeen lines: what the dictionary ranks
     * first is drawn at once, the lines go to the engine on a thread of their own, and the
     * screen is read again once they are back.
     */
    private fun settled(
        batch: JSONObject,
        texts: List<String>,
        source: String,
        target: String,
    ): JSONObject {
        // Nothing on this screen is waiting, until it says otherwise below.
        onScreen = emptySet()
        if (!Translator.usable || target.isEmpty() || target == source) return batch
        // Only a direction the engine is open for: a line it cannot translate out of is a line
        // it says nothing about.
        if (Translator.direction != "$source-$target") return batch
        val misses = batch.optJSONArray("misses") ?: return batch
        val tokens = batch.optJSONArray("tokens") ?: return batch
        val wanted = ArrayList<Int>()
        val later = LinkedHashSet<Int>()
        for (at in 0 until misses.length()) {
            val row = misses.optJSONObject(at) ?: continue
            val need = row.optString("need")
            if (need != "Sentence" && need != "Sense") continue
            val which = row.optInt("token")
            val token = tokens.optJSONObject(which) ?: continue
            val lang = token.optString("lang")
            if (lang.isNotEmpty() && lang != "null" && lang != source) continue
            val run = token.optInt("run", -1)
            if (texts.getOrNull(run).isNullOrBlank()) continue
            wanted.add(which)
            later.add(run)
        }
        if (wanted.isEmpty()) return batch
        val key = { run: Int -> "$source>$target\n${texts[run]}" }
        onScreen = later.mapTo(HashSet(), key)
        // Sent off to be translated where nobody has yet.
        val waiting = synchronized(translated) {
            later.filter { translated[key(it)] == null && translating.add(key(it)) }
        }
        if (waiting.isNotEmpty()) {
            val asked = waiting.map { texts[it] }
            val keys = waiting.map(key)
            lineWorker.execute {
                // A line at a time, so the engine is free between them for the words a read
                // is waiting on: asked as one batch, it held the engine for the whole of it.
                val said = asked.mapIndexed { at, text ->
                    if (keys[at] !in onScreen) return@mapIndexed ""
                    runCatching { Translator.lines(listOf(text)).firstOrNull() }.getOrNull()
                        .orEmpty()
                }
                synchronized(translated) {
                    keys.forEachIndexed { at, k ->
                        translating.remove(k)
                        said.getOrNull(at)?.takeIf { it.isNotEmpty() }?.let { translated[k] = it }
                    }
                }
                if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                    android.util.Log.d("Phonetix", "LINES ${said.size} of ${asked.size} arrived")
                    asked.forEachIndexed { at, text ->
                        android.util.Log.d("Phonetix", "LINE $text => ${said.getOrNull(at)}")
                    }
                }
                // Read again only for what is still there to be drawn with.
                val still = onScreen
                if (keys.indices.any { said[it].isNotEmpty() && keys[it] in still }) {
                    onLinesArrived?.invoke()
                }
            }
        }
        val line = synchronized(translated) {
            wanted.associateWith { translated[key(tokens.optJSONObject(it).optInt("run"))] }
        }
        val kept = wanted.filter { !line[it].isNullOrEmpty() }
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "SETTLED ${kept.size} words over ${later.size} lines, " +
                    "${waiting.size} sent off, first=${line.values.firstOrNull { it != null }}",
            )
        }
        if (kept.isEmpty()) return batch
        val written = runCatching {
            Lex.complete(
                core,
                batch.optLong("batch"),
                kept.toIntArray(),
                Array(kept.size) { "" },
                Array(kept.size) { "" },
                Array(kept.size) { line[kept[it]].orEmpty() },
                source,
                target,
                "",
                "bergamot",
            )
        }.getOrNull() ?: return batch
        return runCatching { JSONObject(written) }.getOrDefault(batch)
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
            // Only what asked for a sound: a word waiting on its sentence has the dictionary's
            // sound already, and a voice's guess over it would replace what a person wrote.
            if (row.optString("need") !in SOUND) continue
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
            // Only what asked for a meaning: a word waiting on its sentence has the
            // dictionary's readings, and translated alone it would be answered with a guess
            // made without the context it was waiting for.
            if (row.optString("need") !in MEANING) continue
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
     * The reverse pair is opened beside the reading direction rather than in its place, so
     * the first question costs a model load and the ones after it cost nothing, and the
     * screen being read keeps answering in its own direction. Nothing comes back where the
     * reverse model was never fetched: the reader is told that, rather than handed their own
     * words back.
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
        val word = Translator.between(models, target, source, listOf(asked))
            .firstOrNull().orEmpty().trim()
        if (word.isEmpty() || word.equals(asked, ignoreCase = true)) return null
        // A machine that answered with several words is answered as a phrase: no dictionary
        // holds one, and a card claiming an entry for it would be claiming one that is not
        // there.
        // Read back in its own direction rather than the screen's: the panel asks in whatever
        // language the reader picks, which need not be the one the screen is in.
        return if (word.split(Regex("\\s+")).size > 1) {
            phrase(word, source, target, models)
        } else {
            lookUp(word, source, target)?.takeIf { it.found } ?: phrase(word, source, target, models)
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
    fun phrase(text: String, source: String, target: String, models: java.io.File? = null): Answer? {
        val asked = text.trim()
        if (asked.isEmpty() || source == target) return null
        val lines = if (models != null) {
            Translator.between(models, source, target, listOf(asked))
        } else {
            Translator.lines(listOf(asked))
        }
        val said = lines.firstOrNull().orEmpty()
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
