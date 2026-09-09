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
    val pos: String?,
    val ipa: List<String>,
    /** The answer in the reader's own language, best first. More than one is an ambiguity the
     *  card shows rather than resolves. */
    val says: List<String>,
    /** What the word means in English, which anchors an answer a machine guessed. */
    val glosses: List<String>,
    /** The applying sense's example, where the dump had one. Never invented: a made-up
     *  sentence would be worth less than nothing. */
    val example: String?,
    /** Each word this spelling is, where it is more than one. Empty when there is nothing to
     *  choose between. */
    val readings: List<Reading>,
    val source: String,
    val target: String,
) {
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

    /** The sense that applies, which the card leads with. */
    val headline: String?
        get() = says.firstOrNull() ?: glosses.firstOrNull()

    /** Whether anything was found at all, which decides between a card and a message. */
    val found: Boolean
        get() = headline != null || ipa.isNotEmpty()

    companion object {
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
                pos = o.optString("pos").ifEmpty { null }.takeIf { it != "null" },
                ipa = list("ipa"),
                says = list("says"),
                glosses = list("glosses"),
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
            )
        }
    }
}
