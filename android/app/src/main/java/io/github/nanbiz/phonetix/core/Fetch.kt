package io.github.nanbiz.phonetix.core

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * What the reader needs, fetched without asking them to.
 *
 * A reader who picks a language, opens a page, or asks the panel for a word has said what
 * they need; making them then find a list and press a button per dictionary was a step
 * nobody could work out, and until they found it the product answered nothing. So each of
 * those moments asks for what it needs here, and it arrives in the background.
 *
 * On a thread of its own. A model is tens of megabytes, and the thread that reads the screen
 * waiting on a download is a screen that stops being read. Each thing is fetched once at a
 * time however often it is asked for, and one that failed is left alone for a while rather
 * than asked for again on every screen - a host that is down is otherwise a download started
 * on every pass.
 */
object Fetch {

    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "phonetix-fetch").apply { isDaemon = true }
    }

    /** What is being fetched now, so a second ask while the first is running is not a second
     *  download. */
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** When each thing last failed, so it is not asked for again straight away. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** What is being fetched right now, for the state dump. */
    fun inFlight(): List<String> = running.toList().sorted()

    /**
     * A language's dictionary, unless this phone already holds it - or unless it is big
     * enough that fetching it is a decision.
     *
     * Most are a few megabytes to a few dozen. English is well over a hundred, because it is
     * every English word with its English definitions, and a reader of English does not need it
     * to be told what a word means in their own language: that comes from their language's
     * dictionary, whose meanings are written in English. So the few that size are fetched when a
     * reader asks for them, from the list, and never on their behalf.
     */
    fun pack(context: Context, lang: String, then: (Boolean) -> Unit = {}) {
        if (lang.isBlank() || lang in Packs.held(context)) return
        start("pack $lang", then) {
            val host = SettingsStore.current.packHost
            val listed = Packs.offered(host).firstOrNull { it.lang == lang }
            listed != null && listed.bytes <= byItself(context) && Packs.get(context, host, lang)
        }
    }

    /**
     * The largest dictionary fetched without being asked for, which depends on what the
     * connection costs. On a metered one, what a page needs up to a size nobody notices; on
     * one that is not, the English dictionary too, which is a hundred megabytes.
     */
    fun byItself(context: Context): Long {
        val net = context.getSystemService(android.net.ConnectivityManager::class.java)
        val metered = runCatching { net?.isActiveNetworkMetered ?: true }.getOrDefault(true)
        return if (metered) BY_ITSELF_METERED else BY_ITSELF_FREE
    }

    const val BY_ITSELF_METERED = 64L * 1024 * 1024
    const val BY_ITSELF_FREE = 160L * 1024 * 1024

    /** The model for one direction, unless this phone already holds it. */
    fun model(context: Context, from: String, to: String, then: (Boolean) -> Unit = {}) {
        if (from.isBlank() || to.isBlank() || from == to) return
        if (Translator.ready(Packs.models(context), from, to)) return
        start("model $from-$to", then) {
            Packs.getModel(context, SettingsStore.current.packHost, from, to)
        }
    }

    /**
     * The same thing, waited for: the panel has a reader looking at it, and what it answers
     * with depends on what arrives.
     */
    fun modelNow(context: Context, from: String, to: String): Boolean {
        if (Translator.ready(Packs.models(context), from, to)) return true
        val key = "model $from-$to"
        if (!mayTry(key) || !running.add(key)) return false
        return try {
            Packs.getModel(context, SettingsStore.current.packHost, from, to)
                .also { ok -> if (ok) failedAt.remove(key) else failedAt[key] = now() }
        } finally {
            running.remove(key)
        }
    }

    private fun start(key: String, then: (Boolean) -> Unit, job: () -> Boolean) {
        if (!mayTry(key) || !running.add(key)) return
        worker.execute {
            val ok = try {
                runCatching(job).getOrDefault(false)
            } finally {
                running.remove(key)
            }
            if (ok) failedAt.remove(key) else failedAt[key] = now()
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "FETCHED $key ok=$ok")
            }
            runCatching { then(ok) }
        }
    }

    private fun mayTry(key: String): Boolean {
        val last = failedAt[key] ?: return true
        return now() - last > RETRY_AFTER_MS
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()

    /** How long something that failed to arrive is left before it is asked for again. */
    private const val RETRY_AFTER_MS = 5 * 60_000L
}
