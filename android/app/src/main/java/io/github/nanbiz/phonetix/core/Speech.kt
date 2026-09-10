package io.github.nanbiz.phonetix.core

import android.content.Context
import java.io.File

/**
 * The synthesiser, and the data it reads.
 *
 * A dictionary answers the words it holds and nothing else. The browser has always had a
 * synthesiser behind that for the rest, which is why a word it has never seen still comes back
 * with a transcription there; the phone had none, so those words came back bare and the audio
 * was whatever voice the system happened to carry.
 *
 * The engine reads its data from a directory rather than from the apk, so the data is unpacked
 * once on first use and kept. It is a megabyte and a half of tables plus one small file per
 * language, so this is not a download and not a decision a reader has to make.
 */
object Speech {

    private const val ASSETS = "espeak"

    @Volatile
    private var ready = false

    /** Whether the engine can answer at all. */
    val usable: Boolean get() = ready

    /**
     * Unpack the data if it is not already there, and start the engine.
     *
     * Safe to call from anywhere and as often as anything likes: the work happens once. Called
     * off the main thread, since the first call writes a few megabytes.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (ready) return true
        val into = File(context.filesDir, ASSETS)
        runCatching {
            if (!File(into, "phontab").exists()) unpack(context, ASSETS, into)
            ready = Lex.speechStart(into.absolutePath) != 0
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Phonetix",
                    "SPEECH ready=$ready from ${into.absolutePath}",
                )
            }
        }.onFailure {
            android.util.Log.w("Phonetix", "the synthesiser did not start", it)
            ready = false
        }
        return ready
    }

    /**
     * How these words are said, keyed by the word.
     *
     * Empty for anything the engine could not read, which the caller leaves bare rather than
     * filling with a guess about a guess.
     */
    fun phonemes(voice: String, words: List<String>): Map<String, String> {
        if (!ready || words.isEmpty()) return emptyMap()
        val asked = words.distinct()
        val said = runCatching { Lex.speechPhonemes(voice, asked.toTypedArray()) }
            .getOrNull() ?: return emptyMap()
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "SPOKE voice=$voice ${asked.size} words, first=${asked.firstOrNull()}" +
                    "=${said.firstOrNull()}",
            )
        }
        return asked.indices
            .mapNotNull { at ->
                val text = said.getOrNull(at).orEmpty()
                if (text.isEmpty()) null else asked[at] to text
            }
            .toMap()
    }

    /** One word spoken, as WAV bytes, or nothing where the engine cannot say it. */
    fun say(voice: String, word: String): ByteArray {
        if (!ready || word.isBlank()) return ByteArray(0)
        return runCatching { Lex.speechSay(voice, word) }.getOrNull() ?: ByteArray(0)
    }

    /** Copy a directory out of the apk, keeping its shape: the engine walks it as it is. */
    private fun unpack(context: Context, from: String, into: File) {
        val names = context.assets.list(from) ?: return
        into.mkdirs()
        for (name in names) {
            val path = "$from/$name"
            val children = context.assets.list(path)
            if (children.isNullOrEmpty()) {
                context.assets.open(path).use { source ->
                    File(into, name).outputStream().use { source.copyTo(it) }
                }
            } else {
                unpack(context, path, File(into, name))
            }
        }
    }
}
