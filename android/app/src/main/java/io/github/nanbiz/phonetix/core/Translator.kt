package io.github.nanbiz.phonetix.core

import java.io.File

/**
 * The translation engine, and the models it reads.
 *
 * A dictionary answers the pairs it has a pack for. Everything else is this: a word with no
 * entry, a pair no pack covers. What it produces is a guess and is marked as one all the way to
 * the card, because a machine's answer wearing a dictionary's authority is what the whole
 * cascade is shaped to avoid.
 *
 * The same engine the browser runs, built native, reading the same models: Firefox's own
 * translation models, fetched by [Fetch] into [Packs.models] from the addresses the pack
 * listing names.
 */
object Translator {

    /** The direction a screen is read in, so a change of language is noticed. */
    @Volatile
    private var open: String = ""

    /** The direction a screen is read in, as "from-to", or empty with none open. */
    val direction: String get() = open

    /** Where that direction's files are, so it can be read again if the engine let it go. */
    @Volatile
    private var openFrom: File? = null

    /**
     * Whether the reading direction is open and can answer.
     *
     * The engine keeps two directions and lets the least recently used one go when a third is
     * opened, so a reader asking the other way round in two languages in a row can cost the
     * reading direction its place. It is read back here rather than reported as gone.
     */
    val usable: Boolean get() = reading()

    @Synchronized
    private fun reading(): Boolean {
        if (open.isEmpty()) return false
        val from = openFrom ?: return false
        return opened(from, open)
    }

    /**
     * Open [direction] from the files in [models], on its own model or through English:
     * whichever the files here make possible.
     */
    private fun opened(models: File, direction: String): Boolean {
        val (from, to) = direction.split("-", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (direct(models, from, to)) return load(models, direction)
        val steps = through(from, to)
        return steps.isNotEmpty() && steps.all { (a, b) -> load(models, "$a-$b") }
    }

    /**
     * Whether a direction could be opened at all: the files for it are here.
     *
     * Asked before opening, because opening is seconds of work and because "no model for that
     * direction" is a different thing to tell a reader than "no word for that".
     */
    fun ready(models: File, from: String, to: String): Boolean =
        direct(models, from, to) ||
            through(from, to).let { steps ->
                steps.isNotEmpty() && steps.all { (a, b) -> direct(models, a, b) }
            }

    /** Whether the files for exactly this direction are here. */
    private fun direct(models: File, from: String, to: String): Boolean {
        val here = File(models, "$from-$to").listFiles().orEmpty()
        return here.any { it.name.startsWith("model") && it.name.endsWith(".bin") } &&
            vocabularies(here) != null
    }

    /**
     * The two directions a pair is translated through where it has no model of its own.
     *
     * The published models go into English and out of it, and nothing else: German to
     * Spanish is German to English and English to Spanish. Empty for a pair with English on
     * either side, which either has a model or has none at all.
     */
    fun through(from: String, to: String): List<Pair<String, String>> =
        if (from == "en" || to == "en" || from == to) emptyList()
        else listOf(from to "en", "en" to to)

    /**
     * The vocabularies a direction reads, source then target.
     *
     * One file for most pairs, used for both sides. A pair whose two languages share no
     * script - Japanese and English - carries one for each, and handing the engine the same
     * one twice makes it read the target language's pieces as the source's.
     */
    private fun vocabularies(here: Array<out File>): Pair<File, File>? {
        val source = here.firstOrNull { it.name.startsWith("srcvocab") && it.name.endsWith(".spm") }
        val target = here.firstOrNull { it.name.startsWith("trgvocab") && it.name.endsWith(".spm") }
        if (source != null && target != null) return source to target
        val shared = here.firstOrNull {
            it.name.endsWith(".spm") && !it.name.startsWith("srcvocab") &&
                !it.name.startsWith("trgvocab")
        } ?: return null
        return shared to shared
    }

    /**
     * Open a direction, from the model files already fetched into [models].
     *
     * Returns whether it can answer. Opening is seconds of work the first time, so it is done
     * off the main thread and only when the direction actually changes.
     */
    @Synchronized
    fun start(models: File, from: String, to: String): Boolean {
        val wanted = "$from-$to"
        if (open == wanted && reading()) return true
        val ok = opened(models, wanted)
        open = if (ok) wanted else ""
        openFrom = if (ok) models else null
        return ok
    }

    /**
     * Open [direction] in the engine from the files in [models], leaving whatever the reader's
     * screen is read in as it is.
     */
    private fun load(models: File, direction: String): Boolean {
        if (runCatching { Lex.translateReady(direction) != 0 }.getOrDefault(false)) return true
        // Found by what they are rather than by a name of ours: the files keep the names they
        // are published under, because the engine reads what kind of model it is from them.
        val here = File(models, direction).listFiles().orEmpty()
        val model = here.firstOrNull { it.name.startsWith("model") && it.name.endsWith(".bin") }
        val vocabs = vocabularies(here)
        val shortlist = here.firstOrNull { it.name.startsWith("lex") && it.name.endsWith(".bin") }
        if (model == null || vocabs == null) return false
        val (vocab, targetVocab) = vocabs
        // The configuration marian-decoder takes, naming files rather than carrying bytes: the
        // app has them on disk already and handing seventeen megabytes across the boundary to
        // read it straight back would be a copy for nothing.
        val config = buildString {
            append("models:\n  - ${model.absolutePath}\n")
            append("vocabs:\n  - ${vocab.absolutePath}\n  - ${targetVocab.absolutePath}\n")
            if (shortlist != null) {
                append("shortlist:\n  - ${shortlist.absolutePath}\n  - false\n")
            }
            append("beam-size: 1\n")
            append("normalize: 1.0\n")
            append("word-penalty: 0\n")
            append("max-length-break: 128\n")
            append("mini-batch-words: 1024\n")
            append("workspace: 128\n")
            append("max-length-factor: 2.0\n")
            append("skip-cost: true\n")
            append("cpu-threads: 0\n")
            append("quiet: true\n")
            append("quiet-translation: true\n")
            append("gemm-precision: int8shiftAlphaAll\n")
            append("alignment: soft\n")
            // Where the engine says what went wrong. It reports through stderr otherwise,
            // which a phone drops, so a model it refuses fails with nothing anywhere saying
            // why - which is a whole evening.
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                append("log: ${File(models, "engine.log").absolutePath}\n")
                append("log-level: info\n")
            }
        }
        val ok = runCatching { Lex.translateOpen(direction, config) != 0 }
            .onFailure { android.util.Log.w("Phonetix", "the translator did not open", it) }
            .getOrDefault(false)
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "TRANSLATOR $direction open=$ok")
        }
        return ok
    }

    /**
     * What these lines say from [from] into [to], whichever direction the screen is read in.
     *
     * The engine holds two directions, so the screen being read keeps its own while the reader
     * asks the other way round, and the next screen does not wait for it to be reopened.
     */
    @Synchronized
    fun between(models: File, from: String, to: String, texts: List<String>): List<String> {
        val direction = "$from-$to"
        if (texts.isEmpty() || !opened(models, direction)) return emptyList()
        return say(direction, texts)
    }

    /**
     * What these lines say in the reader's language, in the order they were given.
     *
     * Whole lines rather than words, and nothing dropped: a line that comes back as itself is
     * still what the engine made of it, which is not true of a word - there, a word that
     * answers itself is the engine having nothing to say.
     *
     * Under the same lock as opening a direction, so nothing can be asked of an engine that is
     * in the middle of being opened.
     */
    @Synchronized
    fun lines(texts: List<String>): List<String> {
        if (texts.isEmpty() || !reading()) return emptyList()
        return say(open, texts)
    }

    private fun say(direction: String, texts: List<String>): List<String> {
        // Through English where the pair has no model of its own: the first half's answers
        // are what the second half is asked.
        val (from, to) = direction.split("-", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val models = openFrom
        val steps = through(from, to)
        if (steps.isNotEmpty() && (models == null || !direct(models, from, to))) {
            var said = texts
            for ((a, b) in steps) {
                said = sayOnce("$a-$b", said)
                if (said.isEmpty()) return emptyList()
            }
            return said
        }
        return sayOnce(direction, texts)
    }

    private fun sayOnce(direction: String, texts: List<String>): List<String> {
        val said = runCatching { Lex.translateSay(direction, texts.toTypedArray()) }
            .onFailure { android.util.Log.w("Phonetix", "the translator refused a page", it) }
            .getOrNull()
            ?: return emptyList()
        return texts.indices.map { at -> said.getOrNull(at).orEmpty().trim() }
    }

    /**
     * What these words mean, keyed by the word.
     *
     * Empty for anything the engine could not answer, which the caller leaves as the dictionary
     * left it rather than filling with a guess about a guess.
     */
    @Synchronized
    fun meanings(words: List<String>): Map<String, String> {
        if (words.isEmpty() || !reading()) return emptyMap()
        val asked = words.distinct()
        val said = say(open, asked)
        if (said.isEmpty()) return emptyMap()
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "TRANSLATED ${asked.size} words, first=${asked.firstOrNull()}=${said.firstOrNull()}",
            )
        }
        return asked.indices
            .mapNotNull { at ->
                val text = said.getOrNull(at).orEmpty().trim()
                if (text.isEmpty() || text == asked[at]) null else asked[at] to text
            }
            .toMap()
    }
}
