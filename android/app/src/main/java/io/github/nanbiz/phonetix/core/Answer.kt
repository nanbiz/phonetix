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
    /** The word the answer leads with, which the core picks by the rule the extension draws a
     *  page's words by: "the" for German "die" read into English, where the first gloss is a
     *  note about grammar. Nothing where the core named none. */
    val lead: String? = null,
    /** Which form of its lemma the word is, where its table says: see [Paradigm]. */
    val paradigm: Paradigm? = null,
    /** The dictionary's narrow transcription of the first, where it gives one that differs:
     *  what a reader who asked for narrow transcriptions is shown. */
    val narrow: String? = null,
    /** The accent the first transcription is said in, which a card names rather than the one
     *  the reader chose: nothing where nobody labelled it. */
    val saidIn: String? = null,
    /** The word this spelling is another way of writing, where it is only that. */
    val spellingOf: String? = null,
    /** What the word is, said in the reader's language: its English definition put into it by
     *  the translator, where their language has no word for it or says the same word, or under
     *  every word where they asked for that. Added by the service, never by the core. */
    val described: String? = null,
    /** Exactly what the core wrote, for a surface that reads this shape itself: the settings
     *  screen is a web view drawing the same card the extension draws, off the same JSON. */
    val json: String = "",
    /** The words of an expression read as one, each with what it is on its own: "me gusta" is
     *  "me", to me, and "gusta", it pleases. Empty for a single word. */
    val parts: List<kotlin.Pair<String, String>> = emptyList(),
) {
    /**
     * Which form of its lemma a word is: the grammatical values of its place in the lemma's
     * table in the order a card names them ("preterite", "indicative", "third-person",
     * "singular"), where its own ending starts, and what it means said in that form ("he
     * walked"), as the core works them out.
     */
    data class Paradigm(
        val place: List<String>,
        val endingAt: Int,
        val said: String?,
        /** A pronoun written onto the end of the word: "me" in Spanish "bañarme". */
        val joined: String? = null,
    ) {
        /** The place as a card names it, person and number together: "preterite ·
         *  indicative · 3rd singular". */
        fun named(): List<String> {
            val person = place.firstOrNull { it.endsWith("-person") }
            val number = place.firstOrNull { it == "singular" || it == "plural" || it == "dual" }
            val ordinal = when (person) {
                "first-person" -> "1st"
                "second-person" -> "2nd"
                "third-person" -> "3rd"
                else -> null
            }
            return place.mapNotNull { value ->
                when {
                    value == person -> listOfNotNull(ordinal, number).joinToString(" ")
                    value == number && person != null -> null
                    else -> value
                }
            }
        }
    }

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
        /** What this reading is, said in the reader's language, as [Answer.described]. */
        val described: String? = null,
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
     *  Never the word itself. The row above the card already names it and the transcription
     *  under it is the same word again, so leading with the spelling showed a reader the same
     *  word three times over; a card with no sense to lead with leads with nothing. */
    val headline: String?
        get() = lead ?: says.firstOrNull() ?: glosses.firstOrNull()

    /** Whether anything was found at all, which decides between a card and a message.
     *
     *  Not the spelling: that is the word the reader is already pointing at, and counting it
     *  made this true of every answer there is. A lookup that reached nothing then stood in
     *  front of the transcription the overlay already had, and the card for a word out of a
     *  language with no pack was the word, a chip and nothing else. */
    val found: Boolean
        get() = headline != null || ipa.isNotEmpty() || state == State.Phrase

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
                lead = if (o.isNull("lead")) null else o.optString("lead").ifEmpty { null },
                narrow = if (o.isNull("narrow")) null else o.optString("narrow").ifEmpty { null },
                saidIn = if (o.isNull("saidIn")) null else o.optString("saidIn").ifEmpty { null },
                spellingOf = if (o.isNull("spellingOf")) null
                    else o.optString("spellingOf").ifEmpty { null },
                paradigm = o.optJSONObject("paradigm")?.let { row ->
                    val place = row.optJSONArray("place") ?: JSONArray()
                    Paradigm(
                        place = (0 until place.length()).mapNotNull {
                            place.optJSONObject(it)?.optString("value")?.ifEmpty { null }
                        },
                        endingAt = row.optInt("endingAt"),
                        said = if (row.isNull("said")) null else row.optString("said").ifEmpty { null },
                        joined = if (row.isNull("joined")) null
                            else row.optString("joined").ifEmpty { null },
                    )
                },
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
                parts = (o.optJSONArray("parts") ?: JSONArray()).let { array ->
                    (0 until array.length()).mapNotNull { at ->
                        val part = array.optJSONObject(at) ?: return@mapNotNull null
                        part.optString("word") to part.optString("means")
                    }
                },
            )
        }
    }
}
