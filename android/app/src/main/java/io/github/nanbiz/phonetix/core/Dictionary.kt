package io.github.nanbiz.phonetix.core

import android.content.Context
import android.util.JsonReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * The English pronunciation dictionary, the same data the extension ships, read straight
 * out of the packed asset.
 *
 * It is streamed with JsonReader rather than parsed into a tree: the file holds nearly two
 * hundred thousand entries, and building an intermediate object graph for all of them
 * would cost several times the memory the finished map needs.
 */
object Dictionary {
    private const val TAG = "Phonetix"
    private val words = HashMap<String, String>(220_000)
    private val common = HashSet<String>(512)

    @Volatile
    var ready = false
        private set

    @Volatile
    private var loading = false

    /** Load once, off the caller's thread. Safe to call from anywhere, repeatedly. */
    fun ensureLoaded(context: Context, onReady: (() -> Unit)? = null) {
        if (ready) { onReady?.invoke(); return }
        synchronized(this) {
            if (loading) return
            loading = true
        }
        val app = context.applicationContext
        Thread({
            // A failure here leaves the app silently transcribing nothing, which is the
            // hardest kind of bug to see from the outside, so it is logged rather than
            // swallowed.
            try {
                loadCommon(app)
                loadWords(app)
                android.util.Log.i(TAG, "dictionary ready: ${words.size} words, ${common.size} common")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "dictionary load failed after ${words.size} words", t)
            }
            ready = true
            loading = false
            onReady?.invoke()
        }, "phonetix-dict").start()
    }

    /**
     * The dictionary stream, however the build left it. The asset is checked in gzipped,
     * but the packaging step unpacks a .gz asset and stores the plain file beside its
     * original name, so both spellings have to be accepted or the app ships with an empty
     * dictionary and silently transcribes nothing.
     */
    private fun openWords(context: Context): java.io.InputStream =
        runCatching { GZIPInputStream(context.assets.open("en.json.gz")) as java.io.InputStream }
            .getOrElse { context.assets.open("en.json") }

    private fun loadWords(context: Context) {
        JsonReader(InputStreamReader(openWords(context), Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                val k = r.nextName()
                val v = r.nextString()
                if (k.isNotEmpty()) words[k] = v
            }
            r.endObject()
        }
    }

    /** The most common words, skipped so the sprinkle lands on words worth reading. */
    private fun loadCommon(context: Context) {
        JsonReader(InputStreamReader(context.assets.open("common-words.json"), Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                val lang = r.nextName()
                if (lang == "en") {
                    r.beginArray()
                    while (r.hasNext()) common.add(r.nextString())
                    r.endArray()
                } else {
                    r.skipValue()
                }
            }
            r.endObject()
        }
    }

    fun isCommon(word: String): Boolean = word in common

    /** The transcription for a word, or null when the dictionary does not have it. */
    fun lookup(word: String): String? {
        if (!ready) return null
        return words[word] ?: words[word.lowercase()]
    }

    val size: Int get() = words.size
}
