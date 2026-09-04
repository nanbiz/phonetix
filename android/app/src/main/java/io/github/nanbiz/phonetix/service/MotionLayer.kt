package io.github.nanbiz.phonetix.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.nanbiz.phonetix.core.ChipStyle
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

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: LayerView? = null

    /** Where the words really were at the last measurement. */
    private var boxes: List<WordBox> = emptyList()
    private var style: ChipStyle = ChipStyle.SOLID

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
            v.offset(predictedX, predictedY)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Take the words over from the small windows, at the positions they are already at. */
    fun start(current: List<WordBox>, chipStyle: ChipStyle) {
        boxes = current
        style = chipStyle
        vx = 0f; vy = 0f
        predictedX = 0f; predictedY = 0f
        lastMeasureAt = SystemClock.uptimeMillis()
        lastFrameAt = lastMeasureAt
        if (view == null) {
            val v = LayerView(context)
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
        view?.set(boxes, style)
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
    fun measured(current: List<WordBox>, chipStyle: ChipStyle) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastMeasureAt).coerceAtLeast(1)
        val movedY = averageShift(boxes, current)
        if (movedY != null && dt < 260) {
            // Blend, so one odd sample does not throw the speed about.
            vy = 0.4f * vy + 0.6f * (movedY / dt)
        } else {
            vy = 0f
        }
        vx = 0f
        boxes = current
        style = chipStyle
        lastMeasureAt = now
        predictedX = 0f
        predictedY = 0f
        view?.set(boxes, style)
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
    private var style: ChipStyle = ChipStyle.SOLID
    private var dx = 0f
    private var dy = 0f
    private val painter = ChipPainter()

    fun set(next: List<WordBox>, nextStyle: ChipStyle) {
        boxes = next
        style = nextStyle
        invalidate()
    }

    fun offset(x: Float, y: Float) {
        if (x == dx && y == dy) return
        dx = x
        dy = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) return
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val out = RectF()
        for (b in boxes) {
            out.set(b.rect.left + dx, b.rect.top + dy, b.rect.right + dx, b.rect.bottom + dy)
            painter.draw(canvas, out, b, style, dark, revealed = false)
        }
    }
}
