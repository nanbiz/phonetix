package io.github.nanbiz.phonetix.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.nanbiz.phonetix.core.WordBox
import io.github.nanbiz.phonetix.ui.Tokens
import kotlin.math.roundToInt

/**
 * The lens: what a reader drags over a screen to be told what a word means.
 *
 * A transcription lying over a word can be tapped only if the overlay takes touches, and an
 * overlay that takes touches takes the swipes that start on a word too, which on a page of
 * text is most of the page. The lens is the way out of that: one small window the reader
 * moves, which asks what it is passing over rather than intercepting anything. Everything
 * else on the screen keeps working exactly as it did.
 *
 * It parks at the edge when it is let go, so it is somewhere to reach for rather than
 * something in the way.
 */
class LensController(
    private val context: Context,
    /** What word is at a point on the screen, which only the overlay knows. */
    private val wordAt: (Float, Float) -> WordBox?,
    /** Which word the lens is over, as it moves, and nothing when it is over none. */
    private val onWord: (WordBox?) -> Unit,
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics,
    )

    /** Whether the lens is on screen. */
    val showing: Boolean get() = view != null

    /** Put the lens up, parked at the right edge. */
    fun show() {
        if (view != null) return
        val size = dp(Tokens.Scale.lensSize).roundToInt()
        val metrics = context.resources.displayMetrics
        val lens = Ring(context)
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable would take the keyboard from the app being read; the lens only ever
            // wants the touches that land on itself.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - size - dp(8f).roundToInt()
            y = metrics.heightPixels / 2
        }
        lens.setOnTouchListener(Dragger(lp))
        runCatching { wm.addView(lens, lp) }
            .onSuccess {
                view = lens
                // Where it parked, so a check can reach for it rather than guess: the window
                // sits in the display's own metrics, which are not the screen's dimensions.
                if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                    android.util.Log.d("Phonetix", "LENSPARKED ${lp.x},${lp.y},$size,$size")
                }
            }
            .onFailure { android.util.Log.w("Phonetix", "the lens did not go up", it) }
    }

    /** Take it down. */
    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        onWord(null)
    }

    /** Where the lens is looking, in screen coordinates. */
    fun centre(): android.graphics.PointF? {
        val lens = view ?: return null
        val lp = lens.layoutParams as? WindowManager.LayoutParams ?: return null
        return android.graphics.PointF(
            lp.x + lens.width / 2f,
            lp.y + lens.height / 2f,
        )
    }

    /** The drag itself: the window follows the finger, and every move asks what is under it. */
    private inner class Dragger(private val lp: WindowManager.LayoutParams) :
        View.OnTouchListener {
        private var fromX = 0
        private var fromY = 0
        private var startX = 0f
        private var startY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fromX = lp.x
                    fromY = lp.y
                    startX = event.rawX
                    startY = event.rawY
                    ask(v)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = fromX + (event.rawX - startX).roundToInt()
                    lp.y = fromY + (event.rawY - startY).roundToInt()
                    runCatching { wm.updateViewLayout(v, lp) }
                    ask(v)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ask(v)
                    return true
                }
            }
            return false
        }

        /** What the lens is over now. */
        private fun ask(v: View) {
            val x = lp.x + v.width / 2f
            val y = lp.y + v.height / 2f
            val found = wordAt(x, y)
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "LENSAT $x,$y -> ${found?.word ?: "nothing"}")
            }
            onWord(found)
        }
    }

    /** The ring itself: a hole to read through, with an edge that says where it is. */
    private inner class Ring(context: Context) : View(context) {
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(Tokens.Scale.lensRing)
            color = 0xFFB45309.toInt()
        }
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            // Barely there: the point is to see the words underneath, not to tint them.
            color = 0x14FFFFFF
        }

        override fun onDraw(canvas: Canvas) {
            val radius = (width.coerceAtMost(height) - edge.strokeWidth) / 2f
            canvas.drawCircle(width / 2f, height / 2f, radius, glass)
            canvas.drawCircle(width / 2f, height / 2f, radius, edge)
        }
    }
}
