package io.github.nanbiz.phonetix.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the core says about one word.
 *
 * The core decides this once and both platforms draw it, so nothing here interprets: a field
 * that is empty is empty because the cascade found nothing, and the card says so rather than
 * filling it in. The shape is written in the core, in one place, and read here.
 */
data class Answer(
    val state: State,
    /** What the reader tapped, as it is written on the page. */
    val spelling: String,
    /** The dictionary form, where that is a different word from the one tapped. */
    val lemma: String?,
    /** What form the spelling is, where the dump named it: "plural", "past participle". The
     *  lemma alone does not say it, and the relation is what a reader is trying to learn. */
    val form: String?,
    val pos: String?,
    val ipa: List<String>,
    /** The first transcription, symbol by symbol: the card offers each sound on its own and
     *  holds no table to look them up in. */
    val symbols: List<SymbolInfo>,
    /** The answer in the reader's own language, best first. More than one is an ambiguity the
     *  card shows rather than resolves. */
    val says: List<String>,
    /** What the word means in English, which anchors an answer a machine guessed. */
    val glosses: List<String>,
    /** What the dump marks each of those senses as, in the same order: "colloquial",
     *  "archaic", "Latin America". Worth knowing before a reader uses the word. */
    val marks: List<List<String>>,
    /** The applying sense's example, where the dump had one. Never invented: a made-up
     *  sentence would be worth less than nothing. */
    val example: String?,
    /** Each word this spelling is, where it is more than one. Empty when there is nothing to
     *  choose between. */
    val readings: List<Reading>,
    /** Where the answer came from: a dictionary, a machine, or a synthesised voice. The card
     *  is the surface that most owes a reader that difference. */
    val provenance: Provenance?,
    val source: String,
    val target: String,
    /** Exactly what the core wrote, for a surface that reads this shape itself: the settings
     *  screen is a web view drawing the same card the extension draws, off the same JSON. */
    val json: String = "",
) {
    /** What produced an answer. A reader deciding whether to trust a word is owed it. */
    sealed interface Provenance {
        data class Dictionary(val pack: String) : Provenance
        data class Guess(val engine: String) : Provenance
        data object Synthesised : Provenance
    }

    /** One of the words a spelling is. */
    data class Reading(
        val pos: String?,
        val ipa: List<String>,
        val says: List<String>,
        val glosses: List<String>,
    ) {
        /** What this reading answers with, or its English meaning where it answered nothing. */
        val headline: String?
            get() = says.firstOrNull() ?: glosses.firstOrNull()
    }

    /** How far the word got through the cascade, which is what the card draws from. */
    enum class State {
        Entry, Form, Homograph, Mono, Guess, Phrase, IpaOnly, None, NoPack, UnknownLang, Loading,
        ;

        companion object {

            fun of(name: String): State =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: None
        }
    }

    /** The sense that applies, which the card leads with.
     *
     *  The word itself where there is no sense at all. The spelling is kept off the headline
     *  because the answer takes that place and the page is already showing the word; with no
     *  answer there is nothing else to lead with, and a headless card is worse than a repeated
     *  word. */
    val headline: String?
        get() = says.firstOrNull()
            ?: glosses.firstOrNull()
            ?: spelling.takeIf { it.isNotBlank() && ipa.isNotEmpty() }

    /** Whether anything was found at all, which decides between a card and a message. */
    val found: Boolean
        get() = headline != null || ipa.isNotEmpty()

    companion object {
        /**
         * What is known about a word when all that is known is how it is said.
         *
         * A build with no dictionary pack still has the transcriptions the app ships with, and
         * that is a real answer to "how do I say this" even though it answers nothing about
         * meaning. It is the same state as a pack that has not been fetched yet, because from a
         * reader's side it is the same situation.
         */
        fun ofTranscription(spelling: String, ipa: String, source: String): Answer = Answer(
            state = State.IpaOnly,
            spelling = spelling,
            lemma = null,
            form = null,
            pos = null,
            ipa = if (ipa.isBlank()) emptyList() else listOf(ipa),
            // The sounds of it, from the core: this side has no table of its own.
            symbols = IpaSymbols.explain(ipa),
            says = emptyList(),
            glosses = emptyList(),
            marks = emptyList(),
            example = null,
            readings = emptyList(),
            // Nothing says where it came from, because nothing here knows: this is what the
            // overlay already had, handed to a card as a last resort.
            provenance = null,
            source = source,
            target = source,
        )

        /** One answer, as the core wrote it. */
        fun parse(json: String): Answer? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            fun list(name: String): List<String> {
                val array = o.optJSONArray(name) ?: JSONArray()
                return (0 until array.length()).mapNotNull { array.optString(it).ifEmpty { null } }
            }
            return Answer(
                state = State.of(o.optString("state")),
                spelling = o.optString("spelling"),
                lemma = o.optString("lemma").ifEmpty { null }.takeIf { it != "null" },
                form = o.optString("form").ifEmpty { null }.takeIf { it != "null" },
                pos = o.optString("pos").ifEmpty { null }.takeIf { it != "null" },
                ipa = list("ipa"),
                symbols = (o.optJSONArray("symbols") ?: JSONArray()).let { array ->
                    (0 until array.length()).mapNotNull { at ->
                        array.optJSONObject(at)?.let { row ->
                            fun text(name: String): String? = row.optString(name).ifEmpty { null }
                            SymbolInfo(
                                token = row.optString("token"),
                                name = row.optString("name"),
                                kind = row.optString("kind"),
                                example = row.optString("example"),
                                wiki = text("wiki"),
                                audio = text("audio"),
                                diagram = text("diagram"),
                                seeing = text("seeing"),
                            )
                        }
                    }
                },
                says = list("says"),
                glosses = list("glosses"),
                marks = (o.optJSONArray("marks") ?: JSONArray()).let { outer ->
                    (0 until outer.length()).map { at ->
                        val inner = outer.optJSONArray(at) ?: JSONArray()
                        (0 until inner.length()).mapNotNull {
                            inner.optString(it).ifEmpty { null }
                        }
                    }
                },
                provenance = o.optJSONObject("provenance")?.let { row ->
                    when (row.optString("kind")) {
                        "dictionary" -> Provenance.Dictionary(row.optString("pack"))
                        "guess" -> Provenance.Guess(row.optString("engine"))
                        "synthesised" -> Provenance.Synthesised
                        else -> null
                    }
                },
                example = o.optString("example").ifEmpty { null }.takeIf { it != "null" },
                readings = (o.optJSONArray("readings") ?: JSONArray()).let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        array.optJSONObject(i)?.let { row ->
                            fun of(name: String): List<String> {
                                val items = row.optJSONArray(name) ?: JSONArray()
                                return (0 until items.length())
                                    .mapNotNull { items.optString(it).ifEmpty { null } }
                            }
                            Reading(
                                pos = row.optString("pos").ifEmpty { null }
                                    .takeIf { it != "null" },
                                ipa = of("ipa"),
                                says = of("says"),
                                glosses = of("glosses"),
                            )
                        }
                    }
                },
                source = o.optString("source"),
                target = o.optString("target"),
                json = json,
            )
        }
    }
}
