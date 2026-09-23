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
    /** What the file has to hash to, where the listing says. */
    val sha256: String = "",
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

    /** Which languages are open in the core right now. Written by the thread that reads the
     *  screen and by the one dictionaries arrive on, so a set that can be. */
    private val open: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
    private val classified: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Give the core a language's homograph classifier, once.
     *
     * Unpacked from the apk on first use, since the engine reads it as a file. A language with
     * none loses nothing: the word before it still decides where it decides.
     */
    fun openClassifier(context: Context, lang: String) {
        // Claimed atomically, so two threads meeting the same language do not both unpack it.
        if (lang !in trained || Reading.core == 0L || !classified.add(lang)) return
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
        val text = runCatching { fetchText("${from(base)}/packs.json") }
            .getOrNull() ?: return emptyList()
        val listed = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until listed.length()).mapNotNull { at ->
            listed.optJSONObject(at)?.let { row ->
                val lang = row.optString("lang")
                if (lang.isEmpty()) {
                    null
                } else {
                    Offered(
                        lang, row.optInt("entries"), row.optLong("bytes"), row.optString("sha256"),
                    )
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
        // Only what the listing offers, and checked against what it says the file hashes to:
        // asking for a language nobody built a pack for is a request that can only fail, and a
        // pack cut short opens as a dictionary quietly missing words.
        val listed = offered(base).firstOrNull { it.lang == lang } ?: return false
        val into = file(context, lang)
        val arriving = File(into.parentFile, "${into.name}.new")
        if (!download("${from(base)}/$lang.pack", arriving, listed.sha256)) return false
        return runCatching {
            val opened = Lex.openPack(Reading.core, arriving.path)
            require(opened.isNotEmpty()) { "not a pack" }
            check(arriving.renameTo(into)) { "could not keep the $lang pack" }
            open.add(opened)
            true
        }.onFailure {
            arriving.delete()
            android.util.Log.w("Phonetix", "the $lang pack did not open", it)
        }.getOrDefault(false)
    }

    /** Where the translation models are kept, one directory per direction. */
    fun models(context: Context): File =
        File(context.applicationContext.filesDir, "models")

    /**
     * Fetch the model for one direction, from the same place the packs come from.
     *
     * Three or four files and tens of megabytes, fetched once. The listing names each file,
     * and where it names a full address and a checksum, that is where the file comes from and
     * what it has to be: the published listing points at the models where their makers
     * publish them, pinned to the versions this app was checked against, rather than copying
     * them somewhere else. A host of the reader's own may list bare names instead, which are
     * then looked for beside the listing.
     */
    fun getModel(context: Context, base: String, from: String, to: String): Boolean {
        val host = from(base)
        val listed = runCatching { JSONArray(fetchText("$host/models.json")) }
            .onFailure { android.util.Log.w("Phonetix", "no model listing at the host", it) }
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
        for (key in MODEL_FILES) {
            val file = files.optJSONObject(key)
            val named = file?.optString("name").orEmpty()
            if (named.isEmpty()) {
                // A model and a vocabulary are what a direction is; the shortlist, and a
                // vocabulary split in two for a pair whose scripts differ, are optional.
                if (key == "model") return false
                continue
            }
            val kept = File(into, named)
            val wanted = file?.optString("sha256").orEmpty()
            if (kept.exists() && kept.length() > 0 && (wanted.isEmpty() || sha256(kept) == wanted)) {
                continue
            }
            val address = file?.optString("url").orEmpty().ifEmpty { "$host/models/$named" }
            if (!download(address, kept, wanted)) return false
        }
        return true
    }

    /** The files a direction can be made of, in the order they are fetched. */
    private val MODEL_FILES = listOf("model", "vocab", "srcvocab", "trgvocab", "lex")

    /**
     * Fetch a file to where it is kept, whole or not at all.
     *
     * Into a partial file first and renamed only once complete and, where a checksum is
     * known, only once it matches: a download that stopped halfway, or a host that answered
     * with an error page, is otherwise a file the engine is handed as a model.
     */
    fun download(address: String, to: File, sha256: String = ""): Boolean {
        val part = File(to.parentFile, "${to.name}.part")
        return runCatching {
            open(address).use { source -> part.outputStream().use { sink -> source.copyTo(sink) } }
            if (sha256.isNotEmpty()) {
                val got = sha256(part)
                check(got == sha256) { "$address arrived as $got, not $sha256" }
            }
            check(part.renameTo(to)) { "could not keep $to" }
        }.onFailure {
            part.delete()
            android.util.Log.w("Phonetix", "$address did not arrive", it)
        }.isSuccess
    }

    /** A connection that gives up rather than hanging: a stalled host is a panel waiting. */
    private fun open(address: String): java.io.InputStream {
        val connection = URL(address).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = CONNECT_MS
        connection.readTimeout = READ_MS
        connection.instanceFollowRedirects = true
        val code = connection.responseCode
        check(code in 200..299) { "$address answered $code" }
        return connection.inputStream
    }

    private fun fetchText(address: String): String =
        open(address).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000

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
