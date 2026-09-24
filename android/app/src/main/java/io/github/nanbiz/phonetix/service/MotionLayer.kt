package io.github.nanbiz.phonetix.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.nanbiz.phonetix.core.WordBox

/**
 * What the transcriptions ride on while the screen is moving.
 *
 * Standing still, each word has its own small window: that is what makes one tappable and
 * what lets every other touch through. Moving, that is the wrong shape entirely - carrying
 * a dozen windows would be a dozen calls to the window manager on every frame - so the
 * whole set is drawn on one full-screen layer instead, which moves by shifting a canvas.
 *
 * Between measurements it predicts. A scroll is only sampled as often as the lines can be
 * asked where they are, tens of milliseconds apart, and stepping the words from one sample
 * to the next looks exactly as stuttery as it is. So the layer carries them at the speed
 * the last samples showed, and each new measurement corrects the position and the speed it
 * is running at. When the speed falls away the motion is over and the small windows come
 * back.
 */
class MotionLayer(private val context: Context) {

    companion object Knobs {
        /** What a measurement is taken to be out of date by when it arrives, on top of the
         *  time it took to arrive: the delay between this layer moving and the screen showing
         *  it moved. Measured against photographs of the screen rather than reasoned about,
         *  and left settable so a test can sweep it. */
        @Volatile
        @JvmStatic
        var leadMs = 0L

        /** How long the words are drawn on the strength of one reading before the layer
         *  stops drawing them. Settable so a test can sweep it. */
        @Volatile
        @JvmStatic
        var staleMs = 600L

        /** How far apart readings may come and the words still be carried between them. */
        @Volatile
        @JvmStatic
        var followableMs = 200L

        /** How far out the drawing may be, measured against the next reading, before the
         *  words are taken off the screen rather than left on the wrong text. */
        @Volatile
        @JvmStatic
        var wrongByPx = 40f

        /** How long a measured speed is carried at full strength, and how long it takes to
         *  fade to nothing after that, both as multiples of the gap between measurements.
         *
         *  A quarter of a gap and a half, which is far less than the one and two they were.
         *  Those were set against a measure that answers yes or no about each transcription
         *  and reports a share, and the same build's share moves by twenty points between
         *  runs, so nothing that was swept against it was ever really measured. Against the
         *  error in pixels, which moves by two: the middle frame of a drag is 12 to 16 pixels
         *  out here against 16 to 21 at the old values, and 72 to 74 per cent of frames are
         *  within half a line of text against 65 to 66. */
        @Volatile
        @JvmStatic
        var coastGaps = 0.25f

        @Volatile
        @JvmStatic
        var fadeGaps = 0.5f

        /** How much further a movement whose speed is not changing is carried. */
        @Volatile
        @JvmStatic
        var steadyGaps = 2.5f

        /** Whether the first measurement of a movement is predicted from at all. */
        @Volatile
        @JvmStatic
        var predictFromFirst = false
    }

    private object Fixed {
        /** How much lateness is worth carrying forward; beyond it the reading is not a
         *  measurement of this movement any more. */
        const val LATE_LIMIT_MS = 250L
        /** How far ahead of itself the layer draws, to arrive on time.
         *
         *  A frame. Not because the app's report is stale - a page does not lay itself out
         *  again to scroll, and the bounds it gives are worked out when they are asked for -
         *  but because what is worked out here is shown a frame later, and the page has moved
         *  on by then. Measured, not reasoned: photographed through a finger swipe at nothing,
         *  a frame, two and three, the error is 8.8, 5.5, 5.9 and 12.2 pixels of the capture,
         *  and it changes sign between two frames and three. */
        const val FRAME_MS = 16L


        /** What a measurement is taken to say about the future, as a multiple of the gap
         *  between measurements: at full speed for one gap, fading to a standstill by the
         *  end of the second. A page stops without announcing it - a stroke ends, a finger
         *  lifts - and the only news of that is the next measurement, so a speed carried
         *  unquestioned until then took the words off the screen. */
        const val COAST = 1.0f
        const val FADE = 2.0f
        /** The gap between measurements is smoothed and kept inside this, so neither one
         *  quick pass nor one slow one decides how far the layer trusts itself. */
        const val GAP_MIN_MS = 24f
        const val GAP_MAX_MS = 120f
        /** Faster than any page travels: a screen height in a couple of frames. Anything
         *  above it came out of two readings that were not of the same screen. */
        const val SANE_PX_PER_MS = 8f
        /** And however fast it is going, the words are never carried further than this from
         *  where they were last measured. */
        const val CARRY_LIMIT_PX = 400f

        /**
         * Except when the page said how far it went, which is not a prediction to be guarded
         * against but its own account of what happened.
         *
         * The limit above is there so a speed worked out from readings cannot throw the words
         * across the screen when it is wrong. A reported scroll cannot be wrong in that way,
         * and holding it to the same limit is why the words followed only a third of a
         * movement: a fling moves twelve hundred pixels and four hundred was all they were
         * allowed. Words carried past the edge have gone with their text, which is where they
         * belong; a few screens is enough to bound anything pathological.
         */
        const val TOLD_LIMIT_PX = 6000f
        /** How much further a steady movement is predicted into than a changing one. */
        const val STEADY = 2.5f

        /** Below this the layer has stopped believing the speed it has, and what a page says
         *  it moved is the only thing left that knows the page is moving. */
        const val TRUST_ENOUGH = 0.5f
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: LayerView? = null

    /** Where the words really were at the last measurement. */
    private var boxes: List<WordBox> = emptyList()

    /** Pixels per millisecond, from the last two measurements. */
    private var vx = 0f
    private var vy = 0f
    private var lastMeasureAt = 0L
    /** When the last measurement reached this layer, as against when it was taken. */
    private var lastArrivedAt = 0L
    private var predictedX = 0f

    /**
     * Where the words are drawn, as two separate things that must not be confused.
     *
     * [guessedY] is what the layer worked out for itself from the speed of the last readings,
     * and it is bounded: a speed can be wrong, and a wrong speed run far enough throws the
     * words across the screen. [carriedY] is what the page itself reported having scrolled,
     * which is not a guess about the future but an account of what already happened, and
     * holding it to the same bound is why the words followed a third of a fling. Summed, they
     * are what is on the screen.
     */
    /**
     * Where the page has been, and where that says it is now.
     *
     * Every report of the page's own position is a sample, and what the words are carried by
     * between reports is what a fit of the last few hundred milliseconds says about this
     * instant. That replaces carrying them at the speed of the last interval, which through a
     * fling is always the speed of a moment that is over: too fast at the end of one and too
     * slow at the start.
     */
    private val track = Track()

    /** The page's own position, as far as it has reported: the running total of what it has
     *  said it scrolled. Its zero is whenever this movement began and means nothing else. */
    private var page = 0f

    /** Where that total stood when the last reading was taken, which is the position the words
     *  on the layer are correct for. */
    private var pageAtReading = 0f

    /** How far the words are carried from where the last reading put them. */
    private var carriedY = 0f
    private val predictedY: Float get() = carriedY
    private var lastFrameAt = 0L
    /** How far apart the measurements have been coming, smoothed: how long a speed of
     *  theirs is worth believing. */
    private var gap = Fixed.GAP_MAX_MS
    /** How many measurements this movement has had. */
    private var measurements = 0
    /** How well the last two speeds agreed, from nothing to one. */
    private var steadiness = 0f

    val isRunning: Boolean get() = view != null

    /** Whether the words are currently hidden for want of a fresh measurement. */
    private var wasStale = false

    /** When the page last said how far it had moved. */
    private var lastToldAt = 0L

    /** How far apart the last two readings actually came. */
    private var arrivedApart = 0L

    /** How far out the words were when the last reading landed. */
    private var drewOut = 0f

    /** Whether the app in front can be followed between its answers at all. */
    private var followable = true

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val v = view ?: return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrameAt).coerceIn(0, 48).toFloat()
            lastFrameAt = now
            // What the page's own account of itself says about this instant, held against
            // what it said when the words were last read. Nothing is integrated frame by
            // frame: the fit already knows where the page is at any moment inside the reach of
            // its samples, and asking it is the same answer however often the frames come.
            predictedX += vx * dt
            carriedY = (-(track.at(now) - pageAtReading))
                .coerceIn(-Fixed.TOLD_LIMIT_PX, Fixed.TOLD_LIMIT_PX)
            // Moved, not redrawn. Recording the whole set again every frame was work the
            // display did sixty times a second and, on a machine with no real GPU, enough
            // to starve the very reads that tell the layer where the words have got to: a
            // measurement that costs twelve milliseconds on a still screen took six hundred
            // in the middle of the movement it was following.
            v.translationX = predictedX
            v.translationY = predictedY
            // Nothing at all, rather than words on the wrong text.
            //
            // Between measurements the words are where this layer believes they are, and that
            // belief is only as good as the last reading. An app that answers every thirty
            // milliseconds is followed; one that takes a second - a Compose conversation while
            // it is being scrolled - is not, and carrying its words on regardless leaves a
            // pronunciation of one word sitting on another. Measured on a page built like
            // such an app, two thirds of what was on the screen through a drag named a word
            // that was not under it. A transcription of the wrong word is worse than no
            // transcription, so past this the layer shows nothing and the next reading brings
            // it straight back.
            // Nothing at all, rather than words on the wrong text.
            //
            // Whether this app can be followed is decided when a reading lands, not frame by
            // frame: deciding it here made the words flash back on for the moment after each
            // reading and off again, which is worse to look at than either answer. Age still
            // counts, because a reading that never arrives is the same as one that cannot be
            // trusted.
            val stale = !followable || now - lastMeasureAt > staleMs
            if (stale != wasStale) {
                wasStale = stale
                v.visibility = if (stale) View.INVISIBLE else View.VISIBLE
                if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Phonetix",
                        "SHOWN ${!stale} followable=$followable age=${now - lastMeasureAt} " +
                            "apart=$arrivedApart out=$drewOut",
                    )
                }
            }
            // What is on the screen this frame, which is the only thing a reader sees. The
            // readings the service takes are what it knows; between them the words are where
            // this puts them, and a test that only ever saw the readings could not tell a
            // transcription riding its word from one sliding off it.
            // Named by the reading it is drawing, so that what was on the screen can be held
            // against the positions it was drawn from rather than against whichever reading
            // happens to be nearest in time.
            drew(now)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Say where the words have just been put, whoever put them there.
     *
     * The layer is moved from three places: the frame callback, a page reporting a scroll, and
     * a fresh reading. Only the first of them used to record it, and it is the one that stops
     * running when the device is busy - so a fling that arrived as eight scroll reports and one
     * frame was recorded as one position, and a measure reading those recordings called it a
     * fling the words did not follow at all. They had followed; nothing had written it down.
     */
    private fun drew(now: Long) {
        if (!io.github.nanbiz.phonetix.BuildConfig.DEBUG) return
        android.util.Log.d(
            "Phonetix",
            "LAYER $now $predictedY $lastMeasureAt showing=${if (wasStale) 0 else 1} " +
                "page=${track.at(now)} anchor=$pageAtReading",
        )
    }

    /**
     * How much of the last measured speed still applies, from one at the moment it was taken
     * to nothing once it is twice the usual gap old.
     */
    private fun trustAt(now: Long): Float {
        // A speed of nothing is nothing to trust. On a list whose bounds do not move while it
        // does, every reading measures a speed of nought, so the layer has no speed of its own
        // at any point in a fling - and a full trust in that nought was what kept the page's
        // own estimate of where it had got to from being used at all for the first gap after
        // each reading. With readings arriving every forty milliseconds and the gap about the
        // same, that was most of the movement.
        if (vy == 0f) return 0f
        val age = (now - lastMeasureAt).toFloat()
        // A page whose last two readings gave the same speed is being carried along steadily,
        // and the next moment of it is worth predicting further into: it is a page that has
        // just changed speed which may be about to stop. Readings do not always come when
        // they are wanted - a round trip into an app busy laying itself out can take a fifth
        // of a second - and through one of those the words either keep up or stand still on
        // a moving page.
        val steady = if (steadiness > 0.75f) steadyGaps else 1f
        val coast = gap * coastGaps * steady
        if (age <= coast) return 1f
        val fade = gap * fadeGaps * steady
        if (age >= fade) return 0f
        return 1f - (age - coast) / (fade - coast)
    }

    /**
     * The page says it has just moved by this much, so move the words with it.
     *
     * Between two readings the words are wherever this layer's guess at the page's speed puts
     * them, and that guess is the whole of what goes wrong on an app that answers slowly. A
     * scroll event carries how far the view says it moved, and where an app fills that in it
     * is exact - 997 pixels reported against 991 really travelled, across a drag. It arrives
     * without being asked for and costs nothing, so it is worth more than a guess. The next
     * reading corrects whatever it got wrong, as it does for the guess.
     */
    /**
     * @param exact whether the page reported this distance or it was worked out from the
     *   offset a list estimates for itself. A reported distance is an account of what
     *   happened and replaces what the layer had guessed for the same stretch; an estimate is
     *   another guess, and two guesses added together overshoot.
     */
    /**
     * @param when_ the moment the page is describing, which is when the event was made rather
     *   than when it reached this process. A sample timestamped on arrival says the page was
     *   there later than it was, and a fit of samples all late by the same amount describes a
     *   page that is behind the real one by exactly that: photographed mid-drag, every
     *   transcription on the screen a row behind its word.
     */
    fun told(dy: Float, exact: Boolean, when_: Long) {
        if (view == null || dy == 0f) return
        val now = when_.coerceAtMost(SystemClock.uptimeMillis())
        lastToldAt = SystemClock.uptimeMillis()
        // A report is not a jump and not a speed: it is one more sample of where the page has
        // got to, and what it is worth is decided by the fit along with all the others. Adding
        // it to the drawing directly counted the same movement twice; taking the speed it
        // implies and running with that assumed the page is still doing what it did over the
        // interval the report covers, which is the whole of what went wrong through a fling.
        page += dy
        track.add(now, page)
        carriedY = (-(track.at(now) - pageAtReading))
            .coerceIn(-Fixed.TOLD_LIMIT_PX, Fixed.TOLD_LIMIT_PX)
        lastFrameAt = now
        view?.let { it.translationY = predictedY }
        drew(now)
    }

    /** Take the words over from the small windows, at the positions they are already at. */
    fun start(current: List<WordBox>) {
        boxes = current
        vx = 0f; vy = 0f
        track.clear()
        page = 0f
        pageAtReading = 0f
        predictedX = 0f; carriedY = 0f
        gap = Fixed.GAP_MAX_MS
        measurements = 0
        steadiness = 0f
        lastMeasureAt = SystemClock.uptimeMillis()
        lastToldAt = lastMeasureAt
        lastArrivedAt = lastMeasureAt
        arrivedApart = 0L
        drewOut = 0f
        followable = true
        lastFrameAt = lastMeasureAt
        if (view == null) {
            val v = LayerView(context).also { OverlayMute.apply(it) }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                // An accessibility overlay, not an application one. A window put up with
                // SYSTEM_ALERT_WINDOW is hidden by the platform over any screen that asks for it -
                // the settings app asks, and so does every permission dialog, to stop a window from
                // covering what the reader is agreeing to. Over those screens the windows were still
                // there, still visible, still in the right places, and nothing was painted: a real
                // app that showed no transcriptions at all while the log said it had drawn eighteen.
                // This type belongs to the service that is already reading the screen, is exempt
                // from that hiding, and needs no permission of its own.
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Nothing here is touchable: it is a moving picture, and every touch during
                // a scroll belongs to the app being scrolled.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
                overScreen(this)
            }
            runCatching { wm.addView(v, lp) }.onSuccess { view = v }
        }
        view?.set(boxes)
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /**
     * A fresh measurement: where the words actually are now.
     *
     * The speed is taken from how far they moved since the last one, and the prediction is
     * reset to the truth, so error cannot accumulate the way it did when the scroll event's
     * own delta was believed.
     */
    /**
     * @param movedSince whether this reading found the lines anywhere new. A reading that did
     *   not cannot say whether the drawing has gone wrong: on a list whose bounds do not move
     *   while it does, every reading through a fling repeats the positions the plan was made
     *   with, so whatever the layer has carried the words by reads as exactly that much error.
     *   The better it followed, the more error it appeared to have, and past half a line of it
     *   the layer takes the words off the screen: measured through a Compose fling, twenty-one
     *   frames with nothing on them against twelve with the words.
     */
    fun measured(current: List<WordBox>, at: Long, speed: Float, movedSince: Boolean) {
        val now = SystemClock.uptimeMillis()
        // How far out the words were, just before this reading landed.
        //
        // The layer drew them at where the last reading put them plus what it has carried
        // them by since; this reading says where they actually are. The difference is what a
        // reader was looking at - and it is the only thing that knows whether an app can be
        // followed at all. Neither the age of a reading nor the rhythm of them says it: a
        // Compose conversation answers often enough and still moves several lines between
        // answers, because the speed worked out from its answers is not the speed it is
        // moving at. Past half a line of error the words are not on their words, and the
        // layer stops drawing until a reading lands where it was expected.
        val was = boxes.firstOrNull { old -> current.any { it.word == old.word } }
        val same = if (was == null) null else current.first { it.word == was.word }
        // And not while the page has reported scrolling that this reading may not show yet.
        // The layer moved because the page said it had moved; a reading taken from an app
        // whose bounds lag behind its own scrolling then differs from the drawing by exactly
        // the distance the page reported, and that is not the drawing being wrong. Measured on
        // a Compose fling: 126 pixels of "error" against a reading that was itself behind, and
        // the words taken off the screen for it.
        val reportedSince = lastToldAt > lastMeasureAt
        if (movedSince && !reportedSince && was != null && same != null && measurements > 0) {
            drewOut = kotlin.math.abs((was.rect.top + predictedY) - same.rect.top)
        }
        // An app worth following answers often, and where it is expected to. Neither on its
        // own is enough: a Compose conversation answers every two hundred milliseconds and
        // has moved several lines by then, and an app answering late but predictably can be
        // carried through it.
        // The first answer of a movement is taken on trust, and only the ones after it are
        // judged. Demanding that the first arrive promptly too was tried, on the reasoning
        // that the opening of a movement is otherwise drawn on trust - and it is worse: on a
        // loaded machine even a page that can be followed takes a few hundred milliseconds to
        // answer the first time, so the words were withheld at the start of every scroll and
        // what was shown after was no better placed. Measured, twice each: 43% and 53% of
        // what was on the screen named a word that was not under it, against 12% and 18%.
        // A reading is also worthless if it was already old when it arrived. Asking a line
        // where it is goes to the app's own thread, and on a page of paragraphs that answer
        // has taken six hundred milliseconds - by which time the page has moved further than
        // the answer describes. The layer used to clamp that lateness and draw anyway.
        val arrivedOld = now - at
        followable = measurements < 2 || (
            arrivedApart <= followableMs && drewOut <= wrongByPx &&
                arrivedOld <= Fixed.LATE_LIMIT_MS
            )
        // How far apart the readings have been coming, which is how long a speed of theirs is
        // worth carrying. Not what the speed is measured over: that is measured where it can
        // be measured properly.
        val arrived = (now - lastArrivedAt).coerceAtLeast(1)
        // Unsmoothed and unclamped, unlike the gap below: what is wanted here is how far
        // apart the readings really are, not a figure to predict with.
        if (measurements > 0) arrivedApart = arrived
        gap = (0.5f * gap + 0.5f * arrived.toFloat()).coerceIn(Fixed.GAP_MIN_MS, Fixed.GAP_MAX_MS)
        lastArrivedAt = now
        measurements++

        // The speed the reading itself measured, from one line asked where it is twice, over
        // the interval between those two askings.
        //
        // Working it out here instead - from how far the whole set of words appears to have
        // moved between two readings - could not be made to hold. Two readings are not always
        // of the same set of words, and the interval between them is not the interval between
        // the moments they describe: a reading taken two hundred milliseconds ago can arrive
        // thirty milliseconds after the last one. Both mistakes inflate the speed, and it came
        // out at twice to eight times the speed the page was really going.
        val fresh = speed.coerceIn(-Fixed.SANE_PX_PER_MS, Fixed.SANE_PX_PER_MS)
        // No speed at all is not a speed of nothing.
        //
        // A reading measures how fast the page is going by asking a line where it is twice.
        // Some pages cannot answer that: a Compose list reports every word at the same pixel
        // for the whole of a fling and only jumps when it is over, so the reading comes back
        // with nothing rather than with a number. Blended in as zero, arriving every twenty
        // milliseconds, it crushed whatever the page's own scroll reports had established, and
        // the words stood still on a page travelling eight hundred pixels. What a page that
        // has really stopped looks like is a speed that stops being renewed, and the trust
        // above already lets that fade.
        if (fresh != 0f) {
            val bigger = maxOf(kotlin.math.abs(fresh), kotlin.math.abs(vy))
            steadiness = if (bigger < 0.05f) 0f
            else (1f - kotlin.math.abs(fresh - vy) / bigger).coerceIn(0f, 1f)
            // Blended, so one odd reading does not throw the speed about.
            vy = 0.4f * vy + 0.6f * fresh
        }
        vx = 0f

        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "MEAS told=$speed vy=$vy arrived=$arrived gap=$gap steady=$steadiness",
            )
        }
        boxes = current
        lastMeasureAt = at
        // Where the words are now, not where they were when they were read. Asking an app
        // that is scrolling where its lines are takes tens of milliseconds, and at the speed
        // of a flick the page has moved a hundred pixels by the time the answer arrives;
        // drawing the answer as if it were current is drawing the page as it was.
        // Plus a frame, because the answer was already a frame old when it was given: an
        // app reports the bounds of the tree as it was last laid out, not as it is being
        // laid out, and at the speed of a flick that frame is fifty pixels.
        val late = (now - at + Fixed.FRAME_MS + leadMs).coerceIn(0, Fixed.LATE_LIMIT_MS)
        predictedX = 0f
        // However late the answer and however fast the page, the words are not carried off
        // the screen to catch up with it: past this the prediction is worth less than the
        // measurement it is correcting.
        //
        // And nothing is predicted from the first measurement of a movement. Its speed comes
        // from one reading taken while the page was still and one taken after it started, so
        // it describes neither, and running a reading's whole age forward at it threw the
        // words a couple of hundred pixels ahead of the text in the opening frames of every
        // scroll. The second measurement is of the movement itself, and prediction starts
        // there.
        // The reading is a sample too, and the better one where it has anything to say.
        //
        // Only where the page reports nothing about itself. A reading's speed comes from one
        // line asked where it is twice, and it runs high: measured against a page reporting its
        // own position, samples taken from it put the words a fifth further along than the page
        // had actually gone, and every frame until the next correction was drawn there. Where
        // the page does report, its own account is the one to fit; where it does not - a list
        // whose bounds do not move while it does - the reading is all there is.
        val wasAtReading = pageAtReading
        if (!reportedSince && measurements > 0 && speed != 0f && at > lastMeasureAt) {
            page = wasAtReading + -speed * (at - lastMeasureAt)
            track.add(at, page)
        }
        // The words on the layer are correct for the page as it stood when this reading was
        // taken, so that is where the carrying starts from.
        pageAtReading = track.at(at)

        // A reading says where the words actually are, so everything carried since the last one
        // has been accounted for and both terms start again from it.
        //
        // Including a reading that found the lines exactly where it left them. Keeping the
        // carry through those was tried, for the sake of a list whose bounds do not move while
        // it does: it also keeps it through a page that has genuinely stopped, whose readings
        // agree for that reason, and the words then sit at the last carried offset over text
        // that is not moving. Measured on a page of paragraphs standing still after a drag,
        // 26, 18, 68 and 32 per cent of the transcriptions off their word where it had been
        // nothing at all.
        // Plus however far the page has gone in the time the answer took to arrive, which the
        // fit knows because the reports kept coming while it was in flight.
        carriedY = (-(track.at(now + late) - pageAtReading))
            .coerceIn(-Fixed.TOLD_LIMIT_PX, Fixed.TOLD_LIMIT_PX)
        lastFrameAt = now
        view?.set(boxes)
        view?.let { v -> v.translationY = predictedY }
        drew(now)
    }

    /** Hand the words back to the small windows and stop drawing. */
    fun stop() {
        Choreographer.getInstance().removeFrameCallback(frame)
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        boxes = emptyList()
        vx = 0f; vy = 0f
    }
}

/** Draws the whole set at an offset. One window, one canvas, one translate per frame. */
/**
 * Every transcription on one canvas.
 *
 * One window and one draw for a whole screenful, instead of a window per word. What it cannot
 * do is take a touch on one word, so it is what the overlay uses while a page moves - and,
 * where the reader has not asked for the words themselves to be touchable, while it holds
 * still as well.
 */
internal class LayerView(context: Context) : View(context) {

    private var boxes: List<WordBox> = emptyList()
    private val painter = ChipPainter()

    fun set(next: List<WordBox>) {
        boxes = next
        translationX = 0f
        translationY = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) return
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        painter.plan(boxes)
        for (b in boxes) {
            painter.draw(canvas, b.rect, b, dark, revealed = false)
        }
    }
}
