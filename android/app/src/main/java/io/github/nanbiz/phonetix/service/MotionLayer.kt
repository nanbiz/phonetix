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

        /** How long a measured speed is carried at full strength, and how long it takes to
         *  fade to nothing after that, both as multiples of the gap between measurements.
         *  Settable so a test can sweep them against photographs of a real swipe. */
        @Volatile
        @JvmStatic
        var coastGaps = 1.0f

        @Volatile
        @JvmStatic
        var fadeGaps = 2.0f

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
        /** How much further a steady movement is predicted into than a changing one. */
        const val STEADY = 2.5f
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
    private var predictedY = 0f
    private var lastFrameAt = 0L
    /** How far apart the measurements have been coming, smoothed: how long a speed of
     *  theirs is worth believing. */
    private var gap = Fixed.GAP_MAX_MS
    /** How many measurements this movement has had. */
    private var measurements = 0
    /** How well the last two speeds agreed, from nothing to one. */
    private var steadiness = 0f

    val isRunning: Boolean get() = view != null

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val v = view ?: return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrameAt).coerceIn(0, 48).toFloat()
            lastFrameAt = now
            // A measurement says where the words were and how fast they were going, and that
            // is worth carrying for about as long as it takes the next one to arrive. Past
            // that the speed is a guess about a page nobody has looked at, so it is let go of
            // rather than run on: a page that stopped between two strokes used to have its
            // words carried on at the speed of the stroke that ended, hundreds of pixels off
            // the text they belong to, until a reading caught up with them.
            val trust = trustAt(now)
            predictedX += vx * dt * trust
            predictedY = (predictedY + vy * dt * trust)
                .coerceIn(-Fixed.CARRY_LIMIT_PX, Fixed.CARRY_LIMIT_PX)
            // Moved, not redrawn. Recording the whole set again every frame was work the
            // display did sixty times a second and, on a machine with no real GPU, enough
            // to starve the very reads that tell the layer where the words have got to: a
            // measurement that costs twelve milliseconds on a still screen took six hundred
            // in the middle of the movement it was following.
            v.translationX = predictedX
            v.translationY = predictedY
            // What is on the screen this frame, which is the only thing a reader sees. The
            // readings the service takes are what it knows; between them the words are where
            // this puts them, and a test that only ever saw the readings could not tell a
            // transcription riding its word from one sliding off it.
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                // Named by the reading it is drawing, so that what was on the screen can be
                // held against the positions it was drawn from rather than against whichever
                // reading happens to be nearest in time.
                android.util.Log.d("Phonetix", "LAYER $now $predictedY $lastMeasureAt")
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * How much of the last measured speed still applies, from one at the moment it was taken
     * to nothing once it is twice the usual gap old.
     */
    private fun trustAt(now: Long): Float {
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

    /** Take the words over from the small windows, at the positions they are already at. */
    fun start(current: List<WordBox>) {
        boxes = current
        vx = 0f; vy = 0f
        predictedX = 0f; predictedY = 0f
        gap = Fixed.GAP_MAX_MS
        measurements = 0
        steadiness = 0f
        lastMeasureAt = SystemClock.uptimeMillis()
        lastArrivedAt = lastMeasureAt
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
            ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
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
    fun measured(current: List<WordBox>, at: Long, speed: Float) {
        val now = SystemClock.uptimeMillis()
        // How far apart the readings have been coming, which is how long a speed of theirs is
        // worth carrying. Not what the speed is measured over: that is measured where it can
        // be measured properly.
        val arrived = (now - lastArrivedAt).coerceAtLeast(1)
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
        val bigger = maxOf(kotlin.math.abs(fresh), kotlin.math.abs(vy))
        steadiness = if (bigger < 0.05f) 0f
        else (1f - kotlin.math.abs(fresh - vy) / bigger).coerceIn(0f, 1f)
        // Blended, so one odd reading does not throw the speed about.
        vy = 0.4f * vy + 0.6f * fresh
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
        predictedY = if (measurements < 2 && !predictFromFirst) 0f
        else (vy * late).coerceIn(-Fixed.CARRY_LIMIT_PX, Fixed.CARRY_LIMIT_PX)
        lastFrameAt = now
        view?.set(boxes)
        view?.let { v -> v.translationY = predictedY }
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
private class LayerView(context: Context) : View(context) {

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
        for (b in boxes) {
            painter.draw(canvas, b.rect, b, dark, revealed = false)
        }
    }
}
