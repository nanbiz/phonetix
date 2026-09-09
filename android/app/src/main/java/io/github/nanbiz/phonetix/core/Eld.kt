package io.github.nanbiz.phonetix.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Which language a piece of text is in, asked of the core.
 *
 * The detector is eld's, ported once and compiled into the core, so the phone and the browser
 * cannot decide a line is German in one place and English in the other. The model is a file
 * the core is handed, the same file on both.
 */
object Eld {

    /** What the text was found to be, and whether the finding is worth acting on. */
    class Guess(val language: String?, val reliable: Boolean, val scores: Map<String, Float>)

    /** What a screenful of text is in, and how many words it had to go on. */
    class Read(val language: String?, val words: Int, val enough: Boolean)

    val nothing = Guess(null, false, emptyMap())

    private const val MODEL = "eld.bin"

    @Volatile
    var ready = false
        private set

    /** Hand the core the model, once, off the caller's thread. */
    fun ensureLoaded(context: Context, then: (() -> Unit)? = null) {
        if (ready) {
            then?.invoke()
            return
        }
        val app = context.applicationContext
        Thread({
            try {
                val file = File(app.filesDir, MODEL)
                if (!file.exists() || file.length() == 0L) {
                    // The model ships compressed and the build unpacks it into the APK under
                    // its plain name, so it is taken as it lies and only decompressed when it
                    // is still packed.
                    val packed = app.assets.list("")?.contains("$MODEL.gz") == true
                    val source = if (packed) {
                        GZIPInputStream(app.assets.open("$MODEL.gz"))
                    } else {
                        app.assets.open(MODEL)
                    }
                    source.use { from -> file.outputStream().use { to -> from.copyTo(to) } }
                }
                waitForCore()
                ready = Lex.openModel(Reading.core, file.absolutePath) > 0
                if (!ready) android.util.Log.w("Phonetix", "the language model did not open")
                then?.invoke()
            } catch (e: Throwable) {
                android.util.Log.e("Phonetix", "the language model did not open", e)
            }
        }, "phonetix-model").start()
    }

    /** The model goes into the core the packs are in, which the dictionary opens. */
    private fun waitForCore() {
        var waited = 0
        while (Reading.core == 0L && waited < 5000) {
            Thread.sleep(50)
            waited += 50
        }
    }

    /**
     * What a screenful of text is in, decided by the core's own rule.
     *
     * How much text is enough, and how sure the detector has to be, are the core's: a phone
     * that wanted eight words and a browser that wanted three would treat the same screen
     * differently, which is what putting the detector there was for.
     */
    fun readScreen(text: CharSequence): Read {
        if (!ready || Reading.core == 0L || text.isEmpty()) return Read(null, 0, false)
        val written = runCatching { Lex.readScreen(Reading.core, text.toString()) }.getOrNull()
            ?: return Read(null, 0, false)
        val found = JSONObject(written)
        return Read(
            language = found.optString("language").ifEmpty { null }.takeIf { it != "null" },
            words = found.optInt("words"),
            enough = found.optBoolean("enough"),
        )
    }

    /** What language one piece of text is in, whatever its length. */
    fun detect(text: CharSequence): Guess {
        if (!ready || Reading.core == 0L || text.isEmpty()) return nothing
        val written = runCatching { Lex.detect(Reading.core, text.toString()) }.getOrNull()
            ?: return nothing
        val found = JSONObject(written)
        val scores = found.optJSONObject("scores")
        val out = HashMap<String, Float>(scores?.length() ?: 0)
        scores?.keys()?.forEach { code -> out[code] = scores.optDouble(code, 0.0).toFloat() }
        return Guess(
            language = found.optString("language").ifEmpty { null }.takeIf { it != "null" },
            reliable = found.optBoolean("reliable"),
            scores = out,
        )
    }
}
