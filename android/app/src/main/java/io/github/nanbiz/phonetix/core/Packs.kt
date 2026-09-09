package io.github.nanbiz.phonetix.core

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.URL

/** One pack as the host that serves them describes it, which is what packbuild writes. */
data class Offered(
    val lang: String,
    val entries: Int,
    val bytes: Long,
)

/**
 * The dictionaries this phone has, and the ones it could have.
 *
 * A pack is a file the core opens where it lies, so it is downloaded once into the app's own
 * files and kept. Nothing is fetched because a screen happened to be in a language: a
 * dictionary is tens of megabytes and the reader decides which ones they want.
 *
 * Where they come from is a runtime setting and appears nowhere in the source.
 */
object Packs {

    /** Which languages are open in the core right now. */
    private val open = HashSet<String>()

    private fun file(context: Context, lang: String) =
        File(context.applicationContext.filesDir, "lex-$lang.pack")

    /** Which dictionaries this phone holds. */
    fun held(context: Context): List<String> =
        context.applicationContext.filesDir.listFiles()
            ?.mapNotNull { file ->
                file.name.takeIf { it.startsWith("lex-") && it.endsWith(".pack") }
                    ?.removePrefix("lex-")?.removeSuffix(".pack")
            }
            ?.sorted()
            ?: emptyList()

    /** Open everything this phone holds, so the cascade can answer with it. */
    fun openHeld(context: Context) {
        if (Reading.core == 0L) return
        for (lang in held(context)) {
            if (lang in open) continue
            val opened = runCatching { Lex.openPack(Reading.core, file(context, lang).path) }
                .getOrNull()
            if (!opened.isNullOrEmpty()) open.add(opened)
        }
    }

    /** What the reader's host has to offer, or nothing when they have not said where. */
    fun offered(base: String): List<Offered> {
        if (base.isBlank()) return emptyList()
        val text = runCatching {
            URL("${base.trimEnd('/')}/packs.json").readText()
        }.getOrNull() ?: return emptyList()
        val listed = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until listed.length()).mapNotNull { at ->
            listed.optJSONObject(at)?.let { row ->
                val lang = row.optString("lang")
                if (lang.isEmpty()) {
                    null
                } else {
                    Offered(lang, row.optInt("entries"), row.optLong("bytes"))
                }
            }
        }
    }

    /**
     * Fetch a language's dictionary and open it.
     *
     * Opened before it is kept: a file that cannot be read is worse in the cache than absent
     * from it, and the pack says which language it turned out to be for.
     */
    fun get(context: Context, base: String, lang: String): Boolean {
        if (base.isBlank() || Reading.core == 0L) return false
        val into = file(context, lang)
        val temporary = File(into.parentFile, "${into.name}.part")
        return runCatching {
            URL("${base.trimEnd('/')}/packs/$lang.pack").openStream().use { from ->
                temporary.outputStream().use { to -> from.copyTo(to) }
            }
            val opened = Lex.openPack(Reading.core, temporary.path)
            require(opened.isNotEmpty()) { "not a pack" }
            temporary.renameTo(into)
            open.add(opened)
            true
        }.onFailure {
            temporary.delete()
            android.util.Log.w("Phonetix", "the $lang pack did not arrive", it)
        }.getOrDefault(false)
    }

    /** Give a dictionary up. */
    fun forget(context: Context, lang: String) {
        file(context, lang).delete()
        open.remove(lang)
        if (Reading.core != 0L) runCatching { Lex.closePack(Reading.core, lang) }
    }
}
