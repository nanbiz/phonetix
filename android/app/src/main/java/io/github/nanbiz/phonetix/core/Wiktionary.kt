package io.github.nanbiz.phonetix.core

import org.json.JSONObject
import java.net.URL

/** What a Wiktionary page says about a word: a transcription a person wrote, and recordings. */
data class Said(val lang: String, val ipa: List<String>, val audio: List<String>)

/**
 * What Wiktionary says about a word, which is more than a pack holds.
 *
 * A pack is a snapshot of the dump. The page carries the two things a reader trusts most: a
 * transcription somebody wrote, and a recording of somebody saying the word. Fetching the
 * page is this side's work because a network call is; reading it is the core's, so the phone
 * and the browser read the same page the same way.
 */
object Wiktionary {

    private val asked = HashMap<String, Said?>(64)

    /** What the page says, or nothing when there is no page or no section for the language. */
    fun about(word: String, lang: String): Said? {
        if (word.isBlank()) return null
        val key = "$lang:${word.lowercase()}"
        synchronized(asked) { if (asked.containsKey(key)) return asked[key] }
        val said = runCatching {
            val url = "https://en.wiktionary.org/w/index.php?action=raw&title=" +
                java.net.URLEncoder.encode(word, "UTF-8")
            val wikitext = URL(url).readText()
            val written = Lex.readWiktionary(wikitext, lang)
            if (written == "null") {
                null
            } else {
                val row = JSONObject(written)
                fun list(name: String): List<String> {
                    val array = row.optJSONArray(name) ?: return emptyList()
                    return (0 until array.length()).mapNotNull { array.optString(it).ifEmpty { null } }
                }
                Said(row.optString("lang"), list("ipa"), list("audio"))
            }
        }.getOrNull()
        synchronized(asked) { asked[key] = said }
        return said
    }
}
