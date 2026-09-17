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
            if (!opened.isNullOrEmpty()) {
                open.add(opened)
                openClassifier(context, opened)
            }
        }
    }

    /** Which languages carry a trained homograph classifier, so none is looked for in vain. */
    private val trained = setOf("de", "en", "es", "fr", "it", "ja", "nl", "pt", "ru", "zh")
    private val classified = HashSet<String>()

    /**
     * Give the core a language's homograph classifier, once.
     *
     * Unpacked from the apk on first use, since the engine reads it as a file. A language with
     * none loses nothing: the word before it still decides where it decides.
     */
    fun openClassifier(context: Context, lang: String) {
        if (lang !in trained || lang in classified || Reading.core == 0L) return
        classified.add(lang)
        val into = File(context.applicationContext.filesDir, "homographs")
        val path = File(into, "$lang.hg")
        runCatching {
            if (!path.exists()) {
                into.mkdirs()
                context.assets.open("homographs/$lang.hg").use { source ->
                    path.outputStream().use { source.copyTo(it) }
                }
            }
            val known = Lex.openHomographs(Reading.core, lang, path.absolutePath)
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "HOMOGRAPHS $lang knows $known")
            }
        }.onFailure { android.util.Log.w("Phonetix", "no classifier for $lang", it) }
    }

    /**
     * Where the packs are published: a release of this project, which is a static file on a
     * host somebody else keeps running.
     *
     * A pack is built once out of a dump of gigabytes and does not change again, which is
     * what a release asset is for - the pronunciations the app carries are fetched the same
     * way when it is built. Before this the reader was asked to name a host themselves, and
     * since nobody served them anywhere, a fresh install could fetch nothing at all.
     */
    const val PUBLISHED = "https://github.com/nanbiz/phonetix/releases/download/packs-v1"

    /** Where to fetch from: what was asked for, or where they are published. */
    private fun from(base: String): String =
        base.ifBlank { PUBLISHED }.trimEnd('/')

    /** What there is to be had, listed by the release that holds them. */
    fun offered(base: String): List<Offered> {
        val text = runCatching {
            URL("${from(base)}/packs.json").readText()
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
        if (Reading.core == 0L) return false
        val into = file(context, lang)
        val temporary = File(into.parentFile, "${into.name}.part")
        return runCatching {
            URL("${from(base)}/$lang.pack").openStream().use { source ->
                temporary.outputStream().use { to -> source.copyTo(to) }
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

    /** Where the translation models are kept, one directory per direction. */
    fun models(context: Context): File =
        File(context.applicationContext.filesDir, "models")

    /**
     * Fetch the model for one direction, from the same host the packs come from.
     *
     * Three files and tens of megabytes, so it happens once and only when a reader has chosen
     * a language to read into. The names are the registry's, which is the same list the
     * browser reads.
     */
    fun getModel(context: Context, base: String, from: String, to: String): Boolean {
        if (base.isBlank()) return false
        val host = base.trimEnd('/')
        val listed = runCatching { JSONArray(URL("$host/models.json").readText()) }
            .getOrNull() ?: return false
        val row = (0 until listed.length())
            .mapNotNull { listed.optJSONObject(it) }
            .firstOrNull { it.optString("from") == from && it.optString("to") == to }
            ?: return false
        val files = row.optJSONObject("files") ?: return false
        val into = File(models(context), "$from-$to")
        into.mkdirs()
        // Under the names they are published with. The engine reads the kind of model it is
        // from the file name - a model built for intgemm says so there - so a file renamed to
        // something tidier is a model it cannot use.
        for (key in listOf("model", "vocab", "lex")) {
            val named = files.optJSONObject(key)?.optString("name").orEmpty()
            if (named.isEmpty()) {
                // Only the shortlist is optional; without a model or a vocabulary there is
                // nothing to open.
                if (key == "lex") continue
                return false
            }
            val name = named
            val to_ = File(into, name)
            if (to_.exists() && to_.length() > 0) continue
            val part = File(into, "$name.part")
            val ok = runCatching {
                URL("$host/models/$named").openStream().use { source ->
                    part.outputStream().use { sink -> source.copyTo(sink) }
                }
                part.renameTo(to_)
            }.onFailure {
                part.delete()
                android.util.Log.w("Phonetix", "the $from-$to model did not arrive", it)
            }.getOrDefault(false)
            if (!ok) return false
        }
        return true
    }

    /** Give a dictionary up. */
    fun forget(context: Context, lang: String) {
        file(context, lang).delete()
        open.remove(lang)
        if (Reading.core != 0L) runCatching { Lex.closePack(Reading.core, lang) }
        // And the pronunciations this app carries answer that language again: they were held
        // back while a fetched dictionary was there, since only one pack per language answers.
        Dictionary.released(lang)
        Dictionary.ensure(context, lang)
    }
}
