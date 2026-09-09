package io.github.nanbiz.phonetix.core

import android.content.Context
import java.io.File

/**
 * How words are said, as the core reads it.
 *
 * The pack is the same file the browser opens and the same reader opens it. What used to be
 * here was a compressed map of word to transcription that this platform parsed itself, which
 * meant a pronunciation had two paths and one of them could be wrong on its own.
 *
 * The pack ships in the app's assets and is copied out once, because a pack is read where it
 * lies and an asset inside an APK is not a file.
 */
object Dictionary {
    private const val PACK = "ipa-en.pack"

    @Volatile
    var ready = false
        private set

    @Volatile
    private var loading = false

    /** Open the pack once, off the caller's thread. Safe to call repeatedly. */
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
                val file = File(app.filesDir, PACK)
                if (!file.exists() || file.length() == 0L) {
                    app.assets.open(PACK).use { from ->
                        file.outputStream().use { to -> from.copyTo(to) }
                    }
                }
                val core = Lex.open()
                Lex.openPack(core, file.absolutePath)
                Reading.core = core
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
}
