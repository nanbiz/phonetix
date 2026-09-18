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
    /** Which screenful the service is on, so a capture can tell whether the screen changed
     *  under it: the frame is taken a moment after it is asked for, and a frame of the page
     *  before answers for lines that were never in it. */
    private val screenful: () -> Int = { 0 },
    /** When the screen being read arrived. A frame older than that is a photograph of the
     *  page before: the tree changes as soon as the app lays a new screen out and the pixels
     *  follow, so a capture already in flight comes back showing what was there before. */
    private val screenfulAt: () -> Long = { 0L },

    /** Take the overlay down now, on the main thread; returns once it is down. */
    private val hideOverlay: () -> Unit,
    /** Ask for the screen to be read again, because colours have arrived or not. */
    private val readAgain: () -> Unit,
) {

    private val lines = HashMap<String, WordColors>(64)

    /** When each line's colours were read, so a reading that went wrong does not stand for
     *  the life of the screen: see [STALE_MS]. */
    private val readAt = HashMap<String, Long>(64)

    /** How often a line has been looked for without being read, and when it was last
     *  tried, so the overlay steps aside a bounded number of times for a line it cannot
     *  resolve - and so a screen that failed while the device was busy is tried again later
     *  rather than being left without colours for as long as it is open. */
    private class Attempts(var count: Int, var at: Long) {
        /** Attempts that never got a frame to look at, kept apart from attempts that looked
         *  and could not tell the text from what it was written on. */
        var blind: Int = 0
    }
    private val tries = HashMap<String, Attempts>(64)

    /** The page's own colours, for a line whose own could not be read and whose surface
     *  could not be read either. */
    @Volatile private var page: WordColors? = null

    /** The surface each unreadable line was standing on, and how far down the screen it was,
     *  which is not the same colour for every line of a screen that is not one colour. */
    private class Surface(val colours: WordColors, val top: Int)
    private val surfaces = HashMap<String, Surface>(32)

    @Volatile private var forPackage: String? = null

    /** What was read for the apps seen before this one. */
    private class Remembered(val lines: Map<String, WordColors>, val page: WordColors?)
    private val byPackage = LinkedHashMap<String, Remembered>()

    /** Whether a look at this screen is already arranged for when the throttle is up. */
    @Volatile private var retryPosted = false

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
        surfaces.clear()
        if (kept != null) lines.putAll(kept.lines)
        page = kept?.page
        forPackage = pkg
        cleanFrameAt = 0L
        while (byPackage.size > REMEMBERED_APPS) byPackage.remove(byPackage.keys.first())
        return true
    }

    /**
     * Forget every colour read anywhere, because the screen they were read from is gone.
     *
     * A theme change repaints every app on the device without changing a word of their text,
     * and these are keyed by text: after switching to a dark theme the transcriptions kept
     * the light background they were read on and sat on the page as pale patches.
     */
    fun forget() {
        lines.clear()
        tries.clear()
        surfaces.clear()
        byPackage.clear()
        page = null
        forPackage = null
        cleanFrameAt = 0L
    }

    /** Whether this line is still worth stepping aside for. */
    fun wanted(text: String, now: Long = SystemClock.uptimeMillis()): Boolean {
        val k = key(text)
        val tried = tries[k]
        // A colour already read is read again once it is old enough.
        //
        // What is read can be wrong: a frame taken before our own paint had left the display
        // gives our ink back as the app's, and kept for ever that one bad reading is what
        // every word of that line is drawn in for as long as the reader stays on the screen -
        // there was no way back from it. Re-reading costs a capture at most every few
        // seconds, and the words keep the colours they have until the new ones arrive, so
        // nothing flickers while it happens.
        if (k in lines && now - (readAt[k] ?: 0L) > STALE_MS) return true
        return k !in lines && (
            tried == null || tried.count < COLOR_TRIES || tried.blind < BLIND_TRIES ||
                now - tried.at > COLOR_RETRY_MS
            )
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

    fun decide(text: String, top: Int = -1, where: RectF? = null, impatient: Boolean = false): Decision {
        val k = key(text)
        val capturingNow = capturing && SystemClock.uptimeMillis() - capturingSince < CAPTURE_TIMEOUT_MS
        val tried = tries[k]
        val givenUp = ((tried?.count ?: 0) >= COLOR_TRIES ||
            (tried?.blind ?: 0) >= BLIND_TRIES) && !capturingNow
        // Its own colours if they were read; otherwise, once it has been given up on, the
        // surface it stands on - or the surface of the nearest line that has one, because
        // lines near each other are on the same thing, and a screen that is two colours had
        // two of its four coloured lines fall all the way through to the page's single colour
        // and come back black on a coloured band. Only failing all of that, the page.
        // A line that arrived while the page was moving cannot wait for its own colours.
        // Reading them means photographing the screen with the overlay down, which is not
        // done while the page is moving - a moving screen photographs as a smear - so a line
        // that scrolled into view was withheld for as long as the scrolling lasted. Through
        // one swipe of a page of text that is most of the screen: a reader watched two thirds
        // of the transcriptions disappear while their finger was down and come back when they
        // lifted it. The surface under it, or the one its neighbours are standing on, is a
        // good enough answer to be drawn in, and its own colours replace it the moment the
        // page stops.
        val c = lines[k] ?: if (givenUp || impatient) {
            // Its own surface, unless that was read while the line was somewhere else - a
            // capture taken while the page was still settling reads the surface under where
            // the line used to be, and on a page of bands that is the wrong band. Then the
            // nearest line that does have one is the better answer.
            val own = surfaces[k]
            val mine = if (own != null && (top < 0 || kotlin.math.abs(own.top - top) <= MOVED_ON))
                own.colours
            else
                null
            // Failing that, read the surface under it now, out of the last picture taken
            // while the overlay was down. The attempts that were meant to read it may all
            // have come at bad moments - before the page settled, while it moved - and there
            // is usually still a good picture to hand. This is a lookup in a bitmap, not
            // another capture: nothing blinks for it.
            val fell = mine ?: readSurfaceNow(k, where) ?: nearestSurface(top) ?: page
            if (BuildConfig.DEBUG && mine == null) {
                android.util.Log.d(
                    "Phonetix",
                    "FELLBACK '${text.take(14)}' asked at $top, its surface " +
                        (own?.let { "at ${it.top}" } ?: "unknown") +
                        ", took ${fell?.let { Integer.toHexString(it.background) } ?: "nothing"}",
                )
            }
            fell
        } else {
            null
        }
        return Decision(c, givenUp)
    }

    /** The surface under a line, read from the picture already in hand, and kept. */
    private fun readSurfaceNow(k: String, where: RectF?): WordColors? {
        if (where == null || where.isEmpty) return null
        val under = sampler.surfaceUnder(where) ?: return null
        surfaces[k] = Surface(under, where.top.toInt())
        return under
    }

    /**
     * The surface of the nearest line that has one, if it is near enough to be the same one.
     *
     * Within a couple of lines: further than that is a different part of the screen, and a
     * screen is only worth guessing about this way where it changes slowly - which is what
     * makes a band, a header over artwork, or a card on a page.
     */
    private fun nearestSurface(top: Int): WordColors? {
        if (top < 0) return null
        var best: Surface? = null
        for (s in surfaces.values) {
            val gap = kotlin.math.abs(s.top - top)
            if (gap > NEARBY_PX) continue
            if (best == null || gap < kotlin.math.abs(best.top - top)) best = s
        }
        return best?.colours
    }

    /** Whether a line that has no colours yet should still be carried while following. */
    fun stillPending(text: String): Boolean {
        val tried = tries[key(text)] ?: return true
        return tried.count < COLOR_TRIES && tried.blind < BLIND_TRIES
    }

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
        if (!nothingKnown && now - cleanFrameAt < CLEAN_FRAME_GAP_MS) {
            // Come back when the throttle is up. Nothing else will: a screen only gets looked
            // at again because something on it announced a change, and a page holding still -
            // an article, a paused player - announces nothing. A line whose colours were not
            // read on the one attempt this allowed was therefore never attempted again, never
            // given up on either, and so never painted: the overlay showed nothing at all for
            // as long as the reader stayed on that screen.
            if (!retryPosted) {
                retryPosted = true
                io.postDelayed({ retryPosted = false; readAgain() },
                    CLEAN_FRAME_GAP_MS - (now - cleanFrameAt) + 1)
            }
            return
        }
        capturing = true
        capturingSince = now
        /** When the last capture was allowed, so one thrown away does not count as one. */
        val lastAllowed = cleanFrameAt
        cleanFrameAt = now
        val asked = wanted.toList()
        /** The screenful these lines belong to. */
        val of = screenful()
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
                    for ((k, _) in asked) countAttempt(k, sawFrame = false)
                    capturing = false
                    if (BuildConfig.DEBUG) android.util.Log.d("Phonetix", "COLOURS no clean frame")
                    readAgain()
                    return@postDelayed
                }
                // And it is a frame of the screen these lines are on. A page that changed
                // while the capture was in flight - a check moving to the next fixture, a
                // reader opening something - photographs as the page before, and every line
                // of the new one then takes its colours from text that was never there: on a
                // page of coloured bands, every word came out the white of the page before.
                // And a photograph of this screen rather than of the one before it.
                val older = sampler.frameAt < screenfulAt()
                if (screenful() != of || older) {
                    capturing = false
                    // A frame thrown away is not a frame read: without this the throttle
                    // counts it, and the lines wait the whole gap again for a colour that
                    // was never taken - which reads as a screen that draws nothing.
                    cleanFrameAt = lastAllowed
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Phonetix",
                            "COLOURS the screen was " +
                                (if (older) "not the one photographed" else "changed under it"),
                        )
                    }
                    readAgain()
                    return@postDelayed
                }
                // The frame now holds the app's own text where our transcriptions were.
                var read = 0
                for ((k, rect) in asked) {
                    countAttempt(k, sawFrame = true)
                    // Every line asked for is read from this frame, held colours included.
                    // The capture has already been paid for, and a line keeping what it was
                    // read as on another screen of the same app is a line wearing a
                    // background it is no longer standing on.
                    val c = sampler.sampleRegion(rect)
                    if (c == null) {
                        // Its text could not be told from what it is written on, but what it
                        // is written on can still be read, and that is what it will be drawn
                        // in if it is given up on.
                        val under = sampler.surfaceUnder(rect)
                        if (under != null) surfaces[k] = Surface(under, rect.top.toInt())
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "Phonetix",
                                "SURFACEUNDER ${if (under == null) "none" else
                                    Integer.toHexString(under.background)} for $rect",
                            )
                        }
                        continue
                    }
                    lines[k] = c
                    readAt[k] = SystemClock.uptimeMillis()
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

    /**
     * @param sawFrame whether there was a picture of the screen to look at. A capture that
     *   never arrived says nothing about whether this line can be read, and counting it
     *   towards giving up meant a busy moment - three of them in four seconds - left the line
     *   painted in the colour of the whole screen for as long as the screen stayed up. A
     *   device that will not be captured at all is still given up on, after more tries.
     */
    private fun countAttempt(k: String, sawFrame: Boolean) {
        val now = SystemClock.uptimeMillis()
        val tried = tries[k]
        if (tried == null) {
            tries[k] = Attempts(if (sawFrame) 1 else 0, now).also { if (!sawFrame) it.blind = 1 }
        } else if (!sawFrame) {
            if (now - tried.at > COLOR_RETRY_MS) tried.blind = 0
            tried.blind++
            tried.at = now
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

        /** How far a line may have moved since its surface was read before that reading is
         *  taken to describe somewhere else. */
        const val MOVED_ON = 60

        /** How far away a line may be and still be taken to stand on the same surface.
         *
         *  A quarter of a tall screen. Three lines of text was not enough: on a page with a
         *  coloured band four lines deep, only the lines next to the one that had been read
         *  were rescued and the rest fell through to the colour of the whole screen. What is
         *  being guessed at here is a surface that spans a part of the screen, so the reach
         *  is a part of the screen. */
        const val NEARBY_PX = 500

        /** How many attempts that never saw a frame are allowed before a line is given up on
         *  anyway. Higher than the tries that did see one: a capture failing says nothing
         *  about the line, only about the moment. */
        const val BLIND_TRIES = 8
        /** And how long before a line that could not be read is worth trying again. */
        const val COLOR_RETRY_MS = 8000L
        /** After this a capture is treated as lost rather than still on its way. */
        const val CAPTURE_TIMEOUT_MS = 2500L
        /**
         * How long after the overlay comes down before a frame is free of it.
         *
         * What was drawn is still on the display for a frame or two after the window has
         * gone, and on a device with nothing to spare that is longer than three frames at
         * sixty. Read too early, what comes back is our own paint: the green line of the
         * colour fixture was read as near-white - the ink of the transcriptions lying over it
         * - and every word on it was then drawn in a colour the page never used. It costs
         * nothing to wait: a colour read is throttled to one every second and a half anyway.
         */
        const val SETTLE_MS = 160L
        /** How old a line's colours may be before they are read again. */
        const val STALE_MS = 20_000L

        /** How many apps' colours are kept, so switching between two is not a fresh read. */
        const val REMEMBERED_APPS = 4
    }
}
