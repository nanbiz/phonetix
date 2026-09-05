package io.github.nanbiz.phonetix.service

import android.graphics.RectF
import android.os.Handler
import android.os.SystemClock
import io.github.nanbiz.phonetix.BuildConfig

/**
 * The colours each line of text is drawn in, read off the screen and kept per app.
 *
 * Accessibility carries no colour, so the only way a transcription can wear the ink and
 * surface of the text it replaces is to capture the display and sample the line - and only
 * ever from a frame taken while our own transcriptions are hidden, or the sampler reads its
 * own paint back as the app's.
 *
 * A line is the unit, not a word. One word carries a few dozen glyph pixels and a short one
 * carries none worth the name; a line carries thousands, and every word on it is drawn in
 * the colour of its neighbours. Keying on the line's own text also keeps a heading apart
 * from the paragraph under it, and one message in a conversation apart from the next, which
 * a single colour per app cannot do.
 */
class LineColours(
    private val sampler: ScreenSampler,
    private val main: Handler,
    private val io: Handler,
    /** Take the overlay down now, on the main thread; returns once it is down. */
    private val hideOverlay: () -> Unit,
    /** Ask for the screen to be read again, because colours have arrived or not. */
    private val readAgain: () -> Unit,
) {

    private val lines = HashMap<String, WordColors>(64)

    /** How often a line has been looked for without being read, and when it was last
     *  tried, so the overlay steps aside a bounded number of times for a line it cannot
     *  resolve - and so a screen that failed while the device was busy is tried again later
     *  rather than being left without colours for as long as it is open. */
    private class Attempts(var count: Int, var at: Long)
    private val tries = HashMap<String, Attempts>(64)

    /** The page's own colours, for a line whose own could not be read. */
    @Volatile private var page: WordColors? = null

    @Volatile private var forPackage: String? = null

    /** What was read for the apps seen before this one. */
    private class Remembered(val lines: Map<String, WordColors>, val page: WordColors?)
    private val byPackage = LinkedHashMap<String, Remembered>()

    /** Set while the overlay is briefly down so a capture can see the text underneath. */
    @Volatile private var capturing = false
    @Volatile private var capturingSince = 0L
    /** When the overlay actually came down for a capture, as opposed to being asked to. */
    @Volatile private var hiddenAt = 0L
    @Volatile private var cleanFrameAt = 0L

    /** A line is known by its text, which is what stays the same while it scrolls. */
    fun key(text: String): String = text.length.toString() + ":" + text.hashCode()

    /**
     * The app in front has changed, or not. Returns true when it has.
     *
     * Colours are kept per app rather than thrown away whenever another one is in front for
     * a moment. A notification shade or a system dialog counts as a different app here, and
     * forgetting a screen's colours for it meant reading them all again - and painting a
     * screenful in the fallback palette until they arrived.
     */
    fun switchTo(pkg: String?): Boolean {
        if (pkg == forPackage) return false
        forPackage?.let { previous ->
            if (lines.isNotEmpty() || page != null) {
                byPackage[previous] = Remembered(HashMap(lines), page)
            }
        }
        val kept = byPackage[pkg]
        lines.clear()
        tries.clear()
        if (kept != null) lines.putAll(kept.lines)
        page = kept?.page
        forPackage = pkg
        cleanFrameAt = 0L
        while (byPackage.size > REMEMBERED_APPS) byPackage.remove(byPackage.keys.first())
        return true
    }

    /** Whether this line is still worth stepping aside for. */
    fun wanted(text: String, now: Long = SystemClock.uptimeMillis()): Boolean {
        val k = key(text)
        val tried = tries[k]
        return k !in lines &&
            (tried == null || tried.count < COLOR_TRIES || now - tried.at > COLOR_RETRY_MS)
    }

    /** The line's own colours, if they have been read. */
    fun of(text: String): WordColors? = lines[key(text)]

    /**
     * What a line should be painted in right now, or null to withhold it.
     *
     * A line whose colours have not been read yet waits rather than being painted in a
     * palette of ours: a screen that appears in gold on brown and corrects itself a moment
     * later is seen as the correction. The page's own colours stand in only once the line
     * has been given up on - using them straight away would be quicker and wrong, since a
     * heading, a quotation and a highlighted phrase all differ from the page - and after a
     * bounded number of attempts an app that can never be captured is still transcribed,
     * in the fallback palette, by returning `null` with `givenUp` true.
     */
    class Decision(val colours: WordColors?, val givenUp: Boolean)

    fun decide(text: String): Decision {
        val k = key(text)
        val capturingNow = capturing && SystemClock.uptimeMillis() - capturingSince < CAPTURE_TIMEOUT_MS
        val givenUp = (tries[k]?.count ?: 0) >= COLOR_TRIES && !capturingNow
        val c = lines[k] ?: if (givenUp) page else null
        return Decision(c, givenUp)
    }

    /** Whether a line that has no colours yet should still be carried while following. */
    fun stillPending(text: String): Boolean = (tries[key(text)]?.count ?: 0) < COLOR_TRIES

    /**
     * Hide, capture, read the colours of these lines, show again.
     *
     * The overlay is down for one capture. It is throttled, and only asked for when a line
     * on screen has no colour yet, so a screen whose lines are already known never blinks.
     */
    fun read(wanted: List<Pair<String, RectF>>) {
        val now = SystemClock.uptimeMillis()
        // A capture that never came back must not shut this for good, and must not leave the
        // words waiting for colours that are not coming either.
        if (capturing && now - capturingSince < CAPTURE_TIMEOUT_MS) return
        // The first reading for an app is not made to wait: until it lands there are no
        // colours at all for this screen, and the words are either withheld or painted in a
        // palette that is not the page's.
        val nothingKnown = lines.isEmpty() && page == null
        if (!nothingKnown && now - cleanFrameAt < CLEAN_FRAME_GAP_MS) return
        capturing = true
        capturingSince = now
        cleanFrameAt = now
        val asked = wanted.toList()
        // When the overlay was really taken down, which is not when it was asked to be: the
        // request is posted to the main thread and waits its turn there. A frame captured
        // between the asking and the hiding still has our own paint in it, and reading that
        // gives our own gold back as the colour of the app's text.
        hiddenAt = 0L
        main.post { hideOverlay(); hiddenAt = SystemClock.uptimeMillis() }
        io.postDelayed({
            sampler.invalidateFrame()
            sampler.refreshIfStale(force = true)
            io.postDelayed({
                // Only a frame taken after the overlay went down can be trusted; anything
                // older still has our transcriptions in it.
                val down = hiddenAt
                if (!sampler.hasFrame || down == 0L || sampler.frameAt < down + SETTLE_MS) {
                    // The lines were asked for and the answer did not come. That counts:
                    // otherwise an app the platform refuses to capture would leave every one
                    // of them waiting for colours that can never arrive, and unpainted.
                    for ((k, _) in asked) countAttempt(k)
                    capturing = false
                    if (BuildConfig.DEBUG) android.util.Log.d("Phonetix", "COLOURS no clean frame")
                    readAgain()
                    return@postDelayed
                }
                // The frame now holds the app's own text where our transcriptions were.
                var read = 0
                for ((k, rect) in asked) {
                    countAttempt(k)
                    if (k in lines) continue
                    val c = sampler.sampleRegion(rect) ?: continue
                    lines[k] = c
                    read++
                }
                // Whatever could not be read still has to be covered by something, and the
                // page's own colour is a better guess than a palette of ours.
                page = sampler.screenColors() ?: page
                capturing = false
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Phonetix",
                        "COLOURS read=$read of ${asked.size} screen=" +
                            (page?.let { Integer.toHexString(it.background) } ?: "none"),
                    )
                }
                readAgain()
            }, 320)
        }, 80)
    }

    private fun countAttempt(k: String) {
        val now = SystemClock.uptimeMillis()
        val tried = tries[k]
        if (tried == null) {
            tries[k] = Attempts(1, now)
        } else {
            // A run of attempts long ago says nothing about now: the device was busy, or the
            // app was mid-layout. Time since the last one starts the count again.
            if (now - tried.at > COLOR_RETRY_MS) tried.count = 0
            tried.count++
            tried.at = now
        }
    }

    private companion object {
        /** How rarely the overlay may step aside to be able to read a colour. */
        const val CLEAN_FRAME_GAP_MS = 1500L
        /** How often a line's colours are looked for before the page's own are used. */
        const val COLOR_TRIES = 3
        /** And how long before a line that could not be read is worth trying again. */
        const val COLOR_RETRY_MS = 8000L
        /** After this a capture is treated as lost rather than still on its way. */
        const val CAPTURE_TIMEOUT_MS = 2500L
        /** How long after the overlay comes down before a frame is free of it: what was
         *  drawn is still on the display for a frame or two after the window has gone. */
        const val SETTLE_MS = 48L
        /** How many apps' colours are kept, so switching between two is not a fresh read. */
        const val REMEMBERED_APPS = 4
    }
}
