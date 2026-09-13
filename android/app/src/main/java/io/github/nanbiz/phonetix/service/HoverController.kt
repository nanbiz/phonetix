package io.github.nanbiz.phonetix.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import io.github.nanbiz.phonetix.core.WordBox
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The circle a reader drags across a screen to be told what a word is.
 *
 * Carried over from Taplex, where this gesture was built, because the ring that replaced it
 * in the merge was under the finger: the word being asked about was under the hand asking.
 * Here the mark stays under the thumb that picked it up and the circle that does the looking
 * rides a clear distance above it, joined by a thread of light, so what is being read is in
 * sight the whole time.
 *
 * Nothing here takes a touch except the mark itself. The screen underneath keeps working
 * while the reader drags over it, which is the whole point: a transcription that can be
 * tapped swallows the swipe that started on it, and on a page of text that is most of the
 * page.
 *
 * The mark rests at the edge when it is let go, so it is somewhere to reach for rather than
 * something in the way, and the thread draws itself back into it.
 */
class HoverController(
    private val context: Context,
    /** What word is at a point on the screen, which only the overlay knows. */
    private val wordAt: (Float, Float) -> WordBox?,
    /** The word the circle is over, as it moves, and nothing when it is over none. */
    private val onWord: (WordBox?) -> Unit,
    /** Where the hand is, so the answer can open clear of it. */
    private val onHand: (Int) -> Unit = {},
    /**
     * A press that went nowhere: the other question the mark answers.
     *
     * Not one word but the whole screen, in the reader's own language, and the same press
     * again to put it back. A drag asks about a word; a tap asks about the page.
     */
    private val onTap: () -> Unit = {},
    /**
     * Several words asked as one, which is the phone's answer to selecting a clause.
     *
     * A reader cannot select an app's own text: the words belong to the app and our overlay
     * takes no touches. So the run is swept with the mark instead - held down first, then
     * dragged - and every word the circle passes over joins it.
     */
    private val onPhrase: (List<WordBox>) -> Unit = {},
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var mark: HoverBubbleView? = null
    private var layer: FrameLayout? = null
    private var highlight: HoverHighlightView? = null
    private var mist: MistView? = null

    /** Where the mark sits, and where it returns to when a drag ends. */
    private var markX = -1
    private var markY = -1

    private var hovered: WordBox? = null

    /** The words a sweep has taken in, in the order the circle met them. Empty unless the
     *  reader held the mark down before dragging, which is what asks about a run rather than
     *  about each word in turn. */
    private var sweeping = false
    private val swept = ArrayList<WordBox>()

    /** Whether the circle is on screen at all. */
    val showing: Boolean get() = mark != null

    private fun dp(value: Float): Float = value * density

    private fun markPx(): Int = dp(MARK_DP).roundToInt()

    private fun screen(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(wm.currentWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            Rect(0, 0, wm.defaultDisplay.width, wm.defaultDisplay.height)
        }

    /** Where the mark rests: the right edge, a margin in. */
    private fun restingX(size: Int): Int = screen().width() - size - dp(EDGE_DP).roundToInt()

    /** Put the circle up, parked at the edge. */
    fun show() {
        if (mark != null) return
        val size = markPx()
        markX = restingX(size)
        if (markY <= 0) markY = screen().height() / 2
        val view = HoverBubbleView(context)
        view.setOnTouchListener(Hand(view))
        runCatching { wm.addView(view, markParams(size)) }
            .onSuccess {
                mark = view
                if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                    android.util.Log.d("Phonetix", "LENSPARKED $markX,$markY,$size,$size")
                }
            }
            .onFailure { android.util.Log.w("Phonetix", "the circle did not go up", it) }
    }

    /** Take it down, and everything it had drawn with it. */
    fun hide() {
        hideLayer()
        mark?.let { runCatching { wm.removeView(it) } }
        mark = null
        hovered = null
        onWord(null)
    }

    /** Where the circle is looking, in screen coordinates. */
    fun centre(): android.graphics.PointF? {
        val view = mark ?: return null
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return null
        return android.graphics.PointF(lp.x + view.width / 2f, lp.y + view.height / 2f)
    }

    private fun markParams(size: Int) = WindowManager.LayoutParams(
        size,
        size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Focusable would take the keyboard from the app being read; the mark only ever wants
        // the touches that land on itself.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = markX
        y = markY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }

    /** The layer the thread and the word's mark are drawn on, which takes no touch at all. */
    private fun layerParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }

    private fun showLayer() {
        if (layer != null) return
        val container = FrameLayout(context)
        val marks = HoverHighlightView(context)
        val flow = MistView(context).apply { onLight = mark?.onLight == true }
        container.addView(marks, FrameLayout.LayoutParams(MATCH, MATCH))
        container.addView(flow, FrameLayout.LayoutParams(MATCH, MATCH))
        runCatching { wm.addView(container, layerParams()) }
            .onSuccess {
                layer = container
                highlight = marks
                mist = flow
            }
            .onFailure { android.util.Log.w("Phonetix", "the thread did not go up", it) }
    }

    private fun hideLayer() {
        layer?.let { runCatching { wm.removeView(it) } }
        layer = null
        highlight = null
        mist = null
    }

    /** What the circle is over now, told once per word rather than once per frame. */
    private fun hoverAt(x: Int, y: Int) {
        val found = wordAt(x.toFloat(), y.toFloat())
        if (found?.word == hovered?.word && found?.rect == hovered?.rect) return
        hovered = found
        highlight?.mark(found?.rect?.let {
            Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        })
        if (found != null) {
            // A tick under the thumb each time the circle takes a new word, since the eye is
            // on the word rather than on the circle.
            mark?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (sweeping && swept.none { it.word == found.word && it.rect == found.rect }) {
                swept.add(found)
                highlight?.gather(swept.map {
                    Rect(it.rect.left.toInt(), it.rect.top.toInt(),
                        it.rect.right.toInt(), it.rect.bottom.toInt())
                })
            }
        }
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "LENSAT $x,$y -> ${found?.word ?: "nothing"}")
        }
        // A sweep is one question, asked when it ends. Opening a card for each word along the
        // way would answer the wrong thing and put a card over the words still to be swept.
        if (!sweeping) onWord(found)
    }

    /**
     * The hand on the mark: what it does while it is down, and what is left when it goes.
     *
     * The mark itself stays under the finger that grabbed it - moving it above would
     * teleport it out from under the thumb the moment a drag began - and the circle that
     * does the looking is drawn where it can be seen: half the contact patch the screen
     * reports for this touch, plus the circle's own radius, plus a clear width more.
     */
    private inner class Hand(private val view: HoverBubbleView) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var formed = false
        private var active = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        /**
         * The hold that turns the next drag into a sweep.
         *
         * Held first, then dragged: a drag that starts straight away is the reader asking
         * about the words it passes, one at a time, which is what the mark is mostly for. The
         * hold is what says this one is about a run.
         */
        private var held = false

        private val hold = Runnable {
            if (dragging || !active) return@Runnable
            held = true
            sweeping = true
            swept.clear()
            highlight?.gather(emptyList())
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "LENSSWEEP on")
            }
        }

        // The ball is not nailed to a point above the finger; it is on the end of the thread.
        // It is pulled towards where the finger holds it, it has weight, and it swings past
        // and settles rather than stopping where the hand stopped - which is what makes the
        // thread read as a leash instead of a stick.
        private var ballX = 0f
        private var ballY = 0f
        private var ballVx = 0f
        private var ballVy = 0f
        private var wantX = 0f
        private var wantY = 0f
        private var fingerX = 0f
        private var fingerY = 0f
        private var lastSwing = 0L
        private var asked = false

        private val swing = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!active) return
                val now = android.os.SystemClock.uptimeMillis()
                val dt = if (lastSwing == 0L) 0.016f else (now - lastSwing) / 1000f
                lastSwing = now
                step(dt.coerceIn(0.001f, 0.05f))
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        /** One frame of the leash: pulled towards where it is wanted, and heavy. */
        private fun step(dt: Float) {
            val ax = (wantX - ballX) * STIFFNESS - ballVx * DAMPING
            val ay = (wantY - ballY) * STIFFNESS - ballVy * DAMPING + GRAVITY * density
            ballVx += ax * dt
            ballVy += ay * dt
            ballX += ballVx * dt
            ballY += ballVy * dt
            // Never so far behind that it is somewhere else entirely: a fling would leave the
            // ball halfway up the screen from where the hand is.
            val slack = dp(LEASH_DP)
            val dx = ballX - wantX
            val dy = ballY - wantY
            val far = hypot(dx, dy)
            if (far > slack) {
                ballX = wantX + dx / far * slack
                ballY = wantY + dy / far * slack
            }
            val radius = view.width / 2f
            mist?.follow(fingerX, fingerY, ballX, ballY, radius)
            // The thread is redrawn every frame because that is what makes it move; what is
            // under the ball is asked half as often, because a word is a hundred times the
            // size of the distance the ball travels in a frame and reading the screen for one
            // is not free.
            asked = !asked
            if (asked) hoverAt(ballX.roundToInt(), ballY.roundToInt())
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    formed = false
                    active = true
                    held = false
                    sweeping = false
                    swept.clear()
                    view.active = true
                    showLayer()
                    main.postDelayed(hold, HOLD_MS)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(event.rawX - downX, event.rawY - downY) > slop) {
                        dragging = true
                        // Moved before the hold was up: this is the other gesture, and the
                        // hold must not turn it into a sweep halfway through.
                        if (!sweeping) main.removeCallbacks(hold)
                    }
                    if (dragging) follow(event)
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    active = false
                    view.active = false
                    main.removeCallbacks(hold)
                    Choreographer.getInstance().removeFrameCallback(swing)
                    highlight?.mark(null)
                    highlight?.gather(emptyList())
                    hovered = null
                    // The run, asked as one thing, now that it is finished. One word is not a
                    // phrase: a sweep that took in a single word is the question the drag
                    // already answers, and it is answered that way rather than as a clause.
                    val run = if (sweeping) ArrayList(swept) else emptyList()
                    sweeping = false
                    swept.clear()
                    if (run.size > 1) {
                        onWord(null)
                        onPhrase(run)
                    } else if (run.size == 1) {
                        onWord(run[0])
                    } else {
                        onWord(null)
                    }
                    // No hand on the screen any more: a card opened by a press after this
                    // would otherwise still be dodging a finger that had gone.
                    onHand(0)
                    if (dragging) {
                        // The thread falls back into the mark where the mark comes to rest,
                        // and the mark reappears only once it has: no disc slides home.
                        val home = park()
                        mist?.onDissolved = {
                            view.masked = false
                            // Taken away on the next turn of the loop rather than inside the
                            // frame that finished it: a view removed while its own frame
                            // callback is still running takes the renderer with it.
                            main.post { hideLayer() }
                        }
                        mist?.dissolve(home.x.toFloat(), home.y.toFloat())
                        ballVx = 0f
                        ballVy = 0f
                    } else {
                        hideLayer()
                        // Nothing was dragged, so nothing was being asked about a word: this
                        // is the press that asks about the whole screen. A press held long
                        // enough to arm a sweep and then let go asked for nothing, and
                        // replacing the page under it would be the opposite of what the
                        // reader had just decided not to do.
                        if (!held) onTap()
                    }
                    return true
                }
            }
            return false
        }

        /**
         * Where the circle goes when the finger leaves: back to the edge it lives on, so what
         * was being read is not left with a disc sitting in the middle of it.
         */
        private fun park(): Point {
            val size = view.width
            val target = restingX(size)
            val from = markX
            val restY = markY.coerceIn(0, screen().height() - size)
            ValueAnimator.ofInt(from, target).apply {
                duration = PARK_MS
                // Comes to rest rather than stopping dead. At a constant speed a thing that
                // stops looks stopped, not settled.
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    if (!view.isAttachedToWindow) return@addUpdateListener
                    markX = it.animatedValue as Int
                    markY = restY
                    runCatching { wm.updateViewLayout(view, markParams(size)) }
                }
                start()
            }
            return Point(target + size / 2, restY + size / 2)
        }

        private fun follow(event: MotionEvent) {
            val size = view.width
            val covered = event.touchMajor.takeIf { it > 1f }
                ?: (FINGER_INCHES * context.resources.displayMetrics.ydpi)
            val radius = size / 2f
            val lift = covered / 2f + radius + size * CLEARANCE
            markX = (event.rawX - radius).roundToInt()
            markY = (event.rawY - radius).roundToInt()
            runCatching { wm.updateViewLayout(view, markParams(size)) }
            fingerX = event.rawX
            fingerY = event.rawY
            wantX = event.rawX
            wantY = event.rawY - lift
            onHand(event.rawY.roundToInt())
            if (!formed) {
                formed = true
                view.masked = true
                // It appears where it is wanted, above the finger, rather than at the finger
                // and springing up: that spring was one frame of the circle low by the hand
                // before it climbed.
                ballX = wantX
                ballY = wantY
                ballVx = 0f
                ballVy = 0f
                lastSwing = 0L
                mist?.onLight = view.onLight
                mist?.form(event.rawX, event.rawY, ballX, ballY, radius)
                Choreographer.getInstance().postFrameCallback(swing)
            }
        }
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

        /** The mark's width. */
        const val MARK_DP = 40f

        /** How far in from the edge it rests. */
        const val EDGE_DP = 8f

        /**
         * What a fingertip covers when the screen will not say. Touchscreens report the
         * contact patch, and this stands in only for the ones that report nothing: a finger
         * pad is around 11mm across, which is what this is in inches of screen.
         */
        const val FINGER_INCHES = 0.43f

        /**
         * How far above the hand the circle rides, as a multiple of its own width, on top of
         * half the contact patch and its own radius.
         */
        const val CLEARANCE = 1.1f

        /** How hard the thread pulls the ball towards where the hand is holding it. */
        const val STIFFNESS = 260f

        /** And how quickly the swing dies away. Under-damped, so it settles by swinging. */
        const val DAMPING = 18f

        /** The weight on the end of it, in dp a second a second. */
        const val GRAVITY = 900f

        /** How far behind the ball may fall before the thread is simply taut, in dp. */
        const val LEASH_DP = 120f

        /** How long the mark takes to travel back to the edge. */
        const val PARK_MS = 260L

        /**
         * How long the mark is held before a drag becomes a sweep.
         *
         * The platform's own long press, rather than a number of ours: a reader who has
         * learnt what a long press feels like on their phone has learnt this gesture too.
         */
        val HOLD_MS = android.view.ViewConfiguration.getLongPressTimeout().toLong()
    }
}
