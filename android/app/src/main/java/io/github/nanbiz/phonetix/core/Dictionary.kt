package io.github.nanbiz.phonetix.core

import android.content.Context
import java.io.File

/**
 * How words are said, as the core reads it.
 *
 * Every language the product knows how to pronounce travels with the app, as the gzipped
 * `{word: how it is said}` map it has always shipped as - a fraction of the size of the pack
 * it becomes. A language becomes a pack the first time a screen is read in it, and the pack is
 * kept, so the cost is paid once and a reader who has configured nothing at all is answered.
 *
 * The alternative was a pack built at compile time for one language, English, and a reader of
 * anything else being told to go and find a dictionary somewhere.
 */
object Dictionary {

    /** Where the maps sit inside the apk. */
    private const val CARRIED = "dictionaries"

    /** The one the app opens before it knows what is on screen, so the first pass has
     *  something to answer with. */
    private const val FIRST = "en"

    @Volatile
    var ready = false
        private set

    @Volatile
    private var loading = false

    /** Which languages have been built and opened, so neither happens twice. */
    private val open = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Open the core and the first language, off the caller's thread. Safe to call repeatedly. */
    fun ensureLoaded(context: Context, onReady: (() -> Unit)? = null) {
        if (ready) {
            onReady?.invoke()
            return
        }
        synchronized(this) {
            if (loading) return
            loading = true
        }
        val app = context.applicationContext
        Thread({
            try {
                if (Reading.core == 0L) Reading.core = Lex.open()
                ensure(app, FIRST)
                ready = true
                onReady?.invoke()
            } catch (e: Throwable) {
                // Silence here is the hardest kind of bug to see from the outside: the app
                // would transcribe nothing and say nothing about why.
                android.util.Log.e("Phonetix", "the pronunciation pack did not open", e)
            } finally {
                loading = false
            }
        }, "phonetix-pack").start()
    }

    /**
     * Have the pronunciations for one language open, building them from what the app carries
     * if this is the first time it has been asked for.
     *
     * Called with whatever the screen turned out to be in, so a reader who opens a page in a
     * language they have never read before is answered on the pass after it is recognised.
     * Runs where the caller is: it is tens of milliseconds for a small language and a few
     * seconds for a big one, and the callers are background threads.
     */
    /** Build this language's pronunciations out of what the app carries. */
    private fun build(app: Context, lang: String, into: File): Boolean {
        // Either name: the packager unpacks a `.gz` asset and drops the suffix, so what is in
        // the apk is the JSON itself, but a build that leaves it alone is read just as well.
        val carried = sequenceOf("$CARRIED/$lang.json", "$CARRIED/$lang.json.gz")
            .mapNotNull { name ->
                runCatching { app.assets.open(name).use { it.readBytes() } }.getOrNull()
            }
            .firstOrNull()
        if (carried == null) {
            android.util.Log.w("Phonetix", "no dictionary is carried for $lang")
            return false
        }
        val built = runCatching {
            Lex.buildIpaPack(lang, carried, System.currentTimeMillis() / 1000)
        }.getOrNull()
        if (built == null || built.isEmpty()) {
            android.util.Log.w("Phonetix", "no pronunciations could be built for $lang")
            return false
        }
        // Written whole and then moved: a half-written pack left by a process that went away
        // is a file that opens and answers nonsense.
        val part = File(app.filesDir, "${into.name}.part")
        part.outputStream().use { it.write(built) }
        if (!part.renameTo(into)) {
            part.delete()
            return false
        }
        return true
    }

    /** Forget that this language is answered, so the next ask opens what is there now. */
    fun released(lang: String) {
        open.remove(lang)
    }

    fun ensure(context: Context, lang: String): Boolean {
        if (lang.isBlank() || Reading.core == 0L) return false
        if (lang in open) return true
        synchronized(open) {
            if (lang in open) return true
            val app = context.applicationContext
            // A dictionary the reader has is not covered by the pronunciations this app
            // carries. The core holds one pack per language, so whichever opened last is the
            // one that answers: the carried pack opened over a fetched one took the language
            // and every meaning in it, and a reader who had gone and got Spanish was answered
            // with transcriptions and nothing else.
            if (File(app.filesDir, "lex-$lang.pack").exists()) {
                open.add(lang)
                return true
            }
            val file = File(app.filesDir, "ipa-$lang.pack")
            if (!file.exists() || file.length() == 0L) {
                if (!build(app, lang, file)) return false
            }
            var opened = runCatching { Lex.openPack(Reading.core, file.absolutePath) }
                .getOrNull()
                .orEmpty()
            if (opened.isEmpty()) {
                // A pack that will not open is built again from what the app carries, once.
                //
                // It is a file this app wrote, and the format it writes has changed: a phone
                // that has been through an update holds one an older build made, the core
                // refuses it, and every word of that language comes back as though no
                // dictionary existed at all - on a phone that carries one. Kept rather than
                // thrown away on the first failure, because the file is also how the language
                // is answered on a train.
                android.util.Log.w("Phonetix", "the $lang pronunciations did not open; building them again")
                file.delete()
                if (!build(app, lang, file)) return false
                opened = runCatching { Lex.openPack(Reading.core, file.absolutePath) }
                    .getOrNull()
                    .orEmpty()
            }
            if (opened.isEmpty()) {
                android.util.Log.w("Phonetix", "the $lang pronunciations did not open")
                return false
            }
            open.add(lang)
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "CARRIED $lang opened as $opened")
            }
            return true
        }
    }
}
