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

    private companion object {
        /** How much lateness is worth carrying forward; beyond it the reading is not a
         *  measurement of this movement any more. */
        const val LATE_LIMIT_MS = 250L
        /** What the app's own reporting is behind by, at sixty frames a second. */
        const val FRAME_MS = 16L
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: LayerView? = null

    /** Where the words really were at the last measurement. */
    private var boxes: List<WordBox> = emptyList()

    /** Pixels per millisecond, from the last two measurements. */
    private var vx = 0f
    private var vy = 0f
    private var lastMeasureAt = 0L
    private var predictedX = 0f
    private var predictedY = 0f
    private var lastFrameAt = 0L

    val isRunning: Boolean get() = view != null

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val v = view ?: return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrameAt).coerceIn(0, 48).toFloat()
            lastFrameAt = now
            predictedX += vx * dt
            predictedY += vy * dt
            // Moved, not redrawn. Recording the whole set again every frame was work the
            // display did sixty times a second and, on a machine with no real GPU, enough
            // to starve the very reads that tell the layer where the words have got to: a
            // measurement that costs twelve milliseconds on a still screen took six hundred
            // in the middle of the movement it was following.
            v.translationX = predictedX
            v.translationY = predictedY
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Take the words over from the small windows, at the positions they are already at. */
    fun start(current: List<WordBox>) {
        boxes = current
        vx = 0f; vy = 0f
        predictedX = 0f; predictedY = 0f
        lastMeasureAt = SystemClock.uptimeMillis()
        lastFrameAt = lastMeasureAt
        if (view == null) {
            val v = LayerView(context).also { OverlayMute.apply(it) }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
    fun measured(current: List<WordBox>, at: Long) {
        val now = SystemClock.uptimeMillis()
        val dt = (at - lastMeasureAt).coerceAtLeast(1)
        val movedY = averageShift(boxes, current)
        if (movedY != null && dt < 260) {
            // Blend, so one odd sample does not throw the speed about.
            vy = 0.4f * vy + 0.6f * (movedY / dt)
        } else {
            vy = 0f
        }
        vx = 0f
        boxes = current
        lastMeasureAt = at
        // Where the words are now, not where they were when they were read. Asking an app
        // that is scrolling where its lines are takes tens of milliseconds, and at the speed
        // of a flick the page has moved a hundred pixels by the time the answer arrives;
        // drawing the answer as if it were current is drawing the page as it was.
        // Plus a frame, because the answer was already a frame old when it was given: an
        // app reports the bounds of the tree as it was last laid out, not as it is being
        // laid out, and at the speed of a flick that frame is fifty pixels.
        val late = (now - at + FRAME_MS).coerceIn(0, LATE_LIMIT_MS)
        predictedX = 0f
        predictedY = vy * late
        lastFrameAt = now
        view?.set(boxes)
        view?.let { v -> v.translationY = predictedY }
    }

    /** How far the words as a set moved between two measurements, if they are the same set. */
    private fun averageShift(before: List<WordBox>, after: List<WordBox>): Float? {
        if (before.isEmpty() || after.isEmpty()) return null
        val byWord = HashMap<String, Float>(before.size)
        for (b in before) byWord[b.word + "@" + b.ipa] = b.rect.top
        var sum = 0f
        var n = 0
        for (a in after) {
            val was = byWord[a.word + "@" + a.ipa] ?: continue
            sum += a.rect.top - was
            n++
        }
        return if (n == 0) null else sum / n
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
