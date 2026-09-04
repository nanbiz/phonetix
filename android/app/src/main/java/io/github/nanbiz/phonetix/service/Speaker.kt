package io.github.nanbiz.phonetix.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Saying things out loud: the word itself through the phone's own speech engine, and a
 * single sound through the recording Wikimedia holds for it.
 *
 * The extension speaks a word with espeak and plays Wikimedia's recordings for the symbols.
 * The recordings are the same files here; the speech is the phone's, which is already
 * installed, already has voices the reader has chosen, and is far better than shipping a
 * synthesiser inside an accessibility service.
 */
class Speaker(context: Context) {

    private val app = context.applicationContext
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

    fun say(word: String) {
        if (!ttsReady) return
        runCatching { tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "phonetix") }
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
                setOnErrorListener { _, _, _ -> stop(); true }
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
