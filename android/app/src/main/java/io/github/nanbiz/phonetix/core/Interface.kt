package io.github.nanbiz.phonetix.core

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * What the app's own drawing says, in the reader's language.
 *
 * The screens in the WebView switch themselves (src/data/interface.ts); this is the same for
 * what is drawn natively - the card, the panel - from the same tables, which the settings page
 * carries in the app's assets beside it. A language with no table, or none chosen yet and a
 * device in one the interface does not speak, is English.
 */
object Interface {

    /** Speak the language the reader made their own, or the device's while they have none. */
    fun follow(context: Context, mine: String) {
        val wanted = mine.ifEmpty { Locale.getDefault().language }
        if (wanted == Wording.speaking) return
        val words = if (wanted == "en") null else read(context, wanted)
        Wording.speakIn(wanted, words)
    }

    /** What to call a language to the reader, in the language the interface is in. */
    fun named(code: String): String {
        val english = Languages.english(code)
        if (Wording.speaking == "en" || english == code) return english
        val ui = Locale.forLanguageTag(Wording.speaking)
        val said = Locale.forLanguageTag(code).getDisplayLanguage(ui)
        return if (said.isEmpty() || said == code) english
        else said.replaceFirstChar { it.titlecase(ui) }
    }

    private fun read(context: Context, lang: String): Map<String, String>? = try {
        val text = context.assets.open("ui/interface/$lang.json").bufferedReader().use { it.readText() }
        val says = JSONObject(text).optJSONObject("says") ?: JSONObject()
        says.keys().asSequence().associateWith { says.optString(it) }.filterValues { it.isNotEmpty() }
    } catch (_: java.io.IOException) {
        null
    }
}
