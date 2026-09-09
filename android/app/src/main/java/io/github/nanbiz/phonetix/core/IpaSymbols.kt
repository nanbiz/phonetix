package io.github.nanbiz.phonetix.core

import org.json.JSONArray

/** What a single symbol of a transcription is called, and a word it is heard in. */
data class SymbolInfo(
    val token: String,
    val name: String,
    /** Vowel, consonant, suprasegmental or diacritic. */
    val kind: String,
    val example: String,
    /** Wikipedia article for the sound, a recording of it, and the sagittal section that
     *  shows how it is made. Null where the table has none. */
    val wiki: String? = null,
    val audio: String? = null,
    val diagram: String? = null,
    /** Seeing Speech deep link: the same sound as MRI and ultrasound of a real mouth. */
    val seeing: String? = null,
)

/** Where Wikimedia serves a file by name; the extension builds the same URL. */
fun wikimediaFileUrl(file: String): String =
    "https://commons.wikimedia.org/wiki/Special:FilePath/" +
        java.net.URLEncoder.encode(file.replace(' ', '_'), "UTF-8").replace("+", "%20")

/** A raster of a Wikimedia file at a given width, so an SVG diagram can be shown. */
fun wikimediaThumbUrl(file: String, width: Int): String = wikimediaFileUrl(file) + "?width=" + width

/**
 * What the sounds of a transcription are, asked of the core.
 *
 * The table and the rules for reading it live in the core and are compiled into it, so this
 * side holds neither: a copy here would be the same table twice, which is how one sound ends
 * up with two names on two devices.
 */
object IpaSymbols {

    /** The symbols of a transcription, each with what is known about that sound. */
    fun explain(ipa: String): List<SymbolInfo> {
        if (ipa.isBlank()) return emptyList()
        val array = JSONArray(Lex.symbols(ipa))
        return (0 until array.length()).mapNotNull { at ->
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
    }

    /** One symbol of a transcription, or nothing when the core knows no such sound. */
    fun describe(token: String): SymbolInfo? = explain(token).firstOrNull()

    /** How a transcription is shown over a word, given what the reader asked for. */
    fun display(ipa: String, narrow: Boolean, hideStress: Boolean): String =
        Lex.display(ipa, narrow, hideStress)
}
