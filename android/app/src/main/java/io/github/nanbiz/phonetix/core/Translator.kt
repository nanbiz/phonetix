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
 * The same engine the browser runs, built native. Where the models come from is the reader's
 * own host, the same one the packs come from: nothing here reaches for a stranger's server on
 * their behalf, and with no host set there is no translation rather than a request.
 */
object Translator {

    /** The direction that is open, so a change of language is noticed. */
    @Volatile
    private var open: String = ""

    /** Whether a direction is open and can answer. */
    val usable: Boolean get() = open.isNotEmpty() && runCatching { Lex.translateReady() != 0 }
        .getOrDefault(false)

    /**
     * Whether a direction could be opened at all: the files for it are here.
     *
     * Asked before opening, because opening is seconds of work and because "no model for that
     * direction" is a different thing to tell a reader than "no word for that".
     */
    fun ready(models: File, from: String, to: String): Boolean {
        val here = File(models, "$from-$to").listFiles().orEmpty()
        return here.any { it.name.startsWith("model") && it.name.endsWith(".bin") } &&
            here.any { it.name.endsWith(".spm") }
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
        if (open == wanted) return true
        // Found by what they are rather than by a name of ours: the files keep the names they
        // are published under, because the engine reads what kind of model it is from them.
        val here = File(models, wanted).listFiles().orEmpty()
        val model = here.firstOrNull { it.name.startsWith("model") && it.name.endsWith(".bin") }
        val vocab = here.firstOrNull { it.name.endsWith(".spm") }
        val shortlist = here.firstOrNull { it.name.startsWith("lex") && it.name.endsWith(".bin") }
        if (model == null || vocab == null) {
            open = ""
            return false
        }
        // The configuration marian-decoder takes, naming files rather than carrying bytes: the
        // app has them on disk already and handing seventeen megabytes across the boundary to
        // read it straight back would be a copy for nothing.
        val config = buildString {
            append("models:\n  - ${model.absolutePath}\n")
            append("vocabs:\n  - ${vocab.absolutePath}\n  - ${vocab.absolutePath}\n")
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
        val ok = runCatching { Lex.translateOpen(config) != 0 }
            .onFailure { android.util.Log.w("Phonetix", "the translator did not open", it) }
            .getOrDefault(false)
        open = if (ok) wanted else ""
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "TRANSLATOR $wanted open=$ok")
        }
        return ok
    }

    /**
     * What these lines say the other way round, with the reading direction put back after.
     *
     * The engine holds one direction at a time, so asking the reverse question means opening
     * the reverse pair - and a screen being read while that pair was open would be answered
     * backwards. Opening, asking and restoring happen under the one lock that guards opening,
     * so no other caller can see the engine pointing the wrong way.
     */
    @Synchronized
    fun reversed(models: File, from: String, to: String, texts: List<String>): List<String> {
        val back = open
        if (!start(models, from, to)) return emptyList()
        return try {
            lines(texts)
        } finally {
            val (was, wants) = back.split("-").let {
                if (it.size == 2) it[0] to it[1] else "" to ""
            }
            if (was.isNotEmpty()) start(models, was, wants)
        }
    }

    /**
     * What these lines say in the reader's language, in the order they were given.
     *
     * Whole lines rather than words, and nothing dropped: a line that comes back as itself is
     * still what the engine made of it, which is not true of a word - there, a word that
     * answers itself is the engine having nothing to say.
     *
     * Under the same lock as opening a direction, so nothing can be asked of an engine that is
     * in the middle of being turned round.
     */
    @Synchronized
    fun lines(texts: List<String>): List<String> {
        if (!usable || texts.isEmpty()) return emptyList()
        val said = runCatching { Lex.translateSay(texts.toTypedArray()) }
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
        if (!usable || words.isEmpty()) return emptyMap()
        val asked = words.distinct()
        val said = runCatching { Lex.translateSay(asked.toTypedArray()) }
            .onFailure { android.util.Log.w("Phonetix", "the translator refused", it) }
            .getOrNull()
            ?: return emptyMap()
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
