package io.github.nanbiz.phonetix.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import io.github.nanbiz.phonetix.core.Speech
import java.io.File
import java.util.Locale

/**
 * Saying things out loud: the word itself through the phone's own speech engine, and a
 * single sound through the recording Wikimedia holds for it.
 *
 * The extension speaks a word with espeak and plays Wikimedia's recordings for the symbols.
 * Both are the same here: the recordings are the same files, and the speech is the same
 * engine in the same voice, so a word does not sound like two different products depending on
 * where a reader met it.
 *
 * The phone's own text-to-speech is the fallback and only that. It says a word in whatever
 * voice the reader installed, which is a different voice per phone and says nothing about the
 * accent they chose; and it cannot say a word in a language they have no voice for, which is
 * most of the languages this reads.
 */
class Speaker(context: Context) {

    private val app = context.applicationContext
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null

    @Volatile
    private var ttsReady = false

    // Held in a var rather than a val: the engine's ready callback wants to configure the
    // very object being constructed, which a val cannot name yet.
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(app) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) runCatching { tts?.language = Locale.US }
        }
    }

    val canSpeak: Boolean get() = ttsReady

    /**
     * Say a word, in the voice the reader is reading in.
     *
     * The synthesiser first, because it is the one the browser uses and the one the accent was
     * chosen for. Off the main thread: synthesis is milliseconds but not none, and this is
     * called from a card that has just opened.
     */
    fun say(word: String, voice: String = "") {
        io.execute {
            val wav = if (voice.isNotEmpty()) Speech.say(voice, word) else ByteArray(0)
            if (wav.isNotEmpty()) {
                playWav(wav)
            } else if (ttsReady) {
                runCatching { tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "phonetix") }
            }
        }
    }

    /** Play bytes the engine produced, through the same path a recording takes. */
    private fun playWav(wav: ByteArray) {
        stop()
        runCatching {
            val file = File.createTempFile("said", ".wav", app.cacheDir)
            file.writeBytes(wav)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stop(); file.delete() }
                prepareAsync()
            }
        }.onFailure { android.util.Log.w("Phonetix", "the voice did not play", it) }
    }

    /** Play a Wikimedia recording. Streamed rather than downloaded: it is played once. */
    fun play(url: String) {
        stop()
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(url)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, what, extra ->
                    android.util.Log.w("Phonetix", "play failed what=$what extra=$extra")
                    stop(); true
                }
                prepareAsync()
            }
        }
    }

    fun stop() {
        player?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        player = null
    }

    fun destroy() {
        stop()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
    }
}
