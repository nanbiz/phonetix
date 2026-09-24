package io.github.nanbiz.phonetix.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
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
import io.github.nanbiz.phonetix.core.SettingsStore
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
    /** Every word the overlay believes is on screen, for the state dump alone: a card about
     *  the wrong word is explained by which boxes were near the circle and how near. */
    private val onScreen: () -> List<WordBox> = { emptyList() },
    /** The word the circle is over, as it moves, and nothing when it is over none. */
    private val onWord: (WordBox?) -> Unit,
    /** Where the hand is, so the answer can open clear of it. */
    private val onHand: (Int, Int) -> Unit = { _, _ -> },
    /** The top of the keyboard while one is open, and zero while none is: the button waits
     *  above it, since a button under a keyboard cannot be picked up. */
    private val keyboardTop: () -> Int = { 0 },
    /** A drag that passed over nothing, so what would explain it can be written down. */
    private val onFoundNothing: () -> Unit = {},
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
    /**
     * The mark held and let go without moving: the other direction.
     *
     * Everything else the mark answers is about a word somebody else wrote. This one is the
     * word the reader is looking for, and it is the one gesture the mark had left: a press
     * held long enough to arm a sweep and then released where it started asked for nothing
     * at all.
     */
    private val onHold: () -> Unit = {},
    /**
     * The mark dragged onto the target at the foot of the screen and let go there: the
     * reader putting Phonetix away until they turn it on again.
     */
    private val onPutAway: () -> Unit = {},
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private var mark: HoverBubbleView? = null
    private var layer: FrameLayout? = null
    private var highlight: HoverHighlightView? = null
    private var mist: MistView? = null

    /** The target the mark is put away on, up only while a drag is near the foot of the
     *  screen. */
    private var drop: DropTargetView? = null

    /** Where the mark sits, and where it returns to when a drag ends. */
    private var markX = -1
    private var markY = -1

    /** Whether a finger is on the mark, so that nothing moves it out from under one. */
    @Volatile private var holding = false

    /** Whether a finger is on the button now, so what can wait does not interrupt it. */
    val held: Boolean get() = holding

    private var hovered: WordBox? = null

    /** When the thumb was last told it had taken a word, so it cannot be told without pause. */
    private var lastTick = 0L

    /** Whether the drag under way has been over any word at all: see [onFoundNothing]. */
    private var tookAnything = false

    /** The words a sweep has taken in, in the order the circle met them. Empty unless the
     *  reader held the mark down before dragging, which is what asks about a run rather than
     *  about each word in turn. */
    private var sweeping = false
    private val swept = ArrayList<WordBox>()

    private val askTheRun = Runnable {
        if (holding && sweeping && swept.size > 1) onPhrase(ArrayList(swept))
    }

    /** Whether the circle is on screen at all. */
    /** Whether the circle is on the screen. The window outlives being put away - see [hide] -
     *  so a window that exists is not the same as a mark a reader can press. */
    val showing: Boolean get() = mark?.visibility == View.VISIBLE

    /** Where the circle last looked, which is not where the finger is: the circle is carried
     *  above the thumb so the word can be seen, and what it reports is its own centre. */
    private var lookedAt = android.graphics.Point(-1, -1)

    /** Everything this believes, for [StateDump]: what is on screen, where the mark is, what
     *  the circle is over and whether a sweep is being gathered. */
    fun state(): org.json.JSONObject = org.json.JSONObject()
        .put("markShowing", showing)
        .put("markAt", org.json.JSONObject().put("x", markX).put("y", markY))
        .put("dragging", layer != null)
        .put("lookingAt", org.json.JSONObject()
            .put("x", lookedAt.x).put("y", lookedAt.y))
        .put("hovered", StateDump.box(hovered))
        // Why that word and not another: the circle is wider than a word and sits between two
        // of them as often as on one, so what it takes is the nearest box within a line's
        // height - and a card about a word the reader was not pointing at is decided here.
        .put("nearest", nearest())
        .put("sweeping", sweeping)
        .put("swept", StateDump.boxes(swept, limit = 40))

    /** The words closest to where the circle is looking, nearest first, with how far off it
     *  is from each: for [StateDump], where a wrong word has to be explained. */
    private fun nearest(): org.json.JSONArray {
        val out = org.json.JSONArray()
        if (lookedAt.x < 0) return out
        val x = lookedAt.x.toFloat()
        val y = lookedAt.y.toFloat()
        val near = onScreen()
            .map { box ->
                val dx = kotlin.math.abs(x - box.rect.centerX()) - box.rect.width() / 2f
                val dy = kotlin.math.abs(y - box.rect.centerY()) - box.rect.height() / 2f
                Triple(box, dx.coerceAtLeast(0f), dy.coerceAtLeast(0f))
            }
            .sortedBy { (_, dx, dy) -> hypot(dx, dy) }
            .take(4)
        for ((box, dx, dy) in near) {
            out.put(
                org.json.JSONObject()
                    .put("word", box.word)
                    .put("rect", StateDump.rect(box.rect))
                    .put("pastLeftOrRight", dx.toInt())
                    .put("aboveOrBelow", dy.toInt())
                    .put("inside", box.rect.contains(x, y)),
            )
        }
        return out
    }

    private fun dp(value: Float): Float = value * density

    private fun markPx(): Int = dp(MARK_DP).roundToInt()

    private fun screen(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(wm.currentWindowMetrics.bounds)
        } else {
            @Suppress("DEPRECATION")
            Rect(0, 0, wm.defaultDisplay.width, wm.defaultDisplay.height)
        }

    /**
     * When a finger last came down on the button.
     *
     * Read from outside to tell one touch from two: a tap on the button while a panel of ours
     * is open is a single touch that both closes the panel, by landing outside it, and
     * reaches the button, which would open it again.
     */
    @Volatile
    var touchedDownAt = 0L
        private set

    /** Which side the reader keeps the button on, which is the hand they hold the phone in. */
    private fun restsRight(): Boolean = SettingsStore.current.side != "left"

    /** Whether the button may sit anywhere, which is to say it stays where it is put. */
    private fun loose(): Boolean = SettingsStore.current.side == "free"

    /**
     * Where the button waits, as the top left of a button this big.
     *
     * On the side the reader keeps it, and never behind the keyboard: a button under an open
     * keyboard cannot be picked up at all, and a reader typing is exactly the reader who
     * wants to ask about what they are reading.
     *
     * How far down is the reader's only where they have pinned it. Unpinned there is no
     * height to go to: coming to rest is the shortest way to the side, which is straight
     * across from wherever the button already is. Only before it has ever been placed is
     * there nothing to keep, and then it starts at [HOME_DOWN], low on the side, where a
     * thumb holding the phone already is.
     */
    private fun restingAt(size: Int): Point {
        val edges = screen()
        val settings = SettingsStore.current
        val margin = dp(EDGE_DP).roundToInt()
        val foot = dp(FOOT_DP).roundToInt()
        // Free to sit anywhere, it does not travel at all: where it was let go is where it
        // waits, held inside the screen so a button dropped half over an edge is still one.
        val x = when {
            loose() && markX >= 0 -> markX.coerceIn(0, (edges.width() - size).coerceAtLeast(0))
            restsRight() -> edges.width() - size - margin
            else -> margin
        }
        // Above whatever the system has put over the bottom of the screen, which is the
        // keyboard when one is open and the gesture strip otherwise.
        val floor = (keyboardTop().takeIf { it > 0 } ?: (edges.height() - foot)) - size - margin
        val y = when {
            settings.pin && !loose() -> (settings.restY * edges.height()).roundToInt() - size / 2
            markY >= 0 -> markY
            else -> (HOME_DOWN * edges.height()).roundToInt() - size / 2
        }
        return Point(x, y.coerceIn(margin, floor.coerceAtLeast(margin)))
    }

    private fun restingX(size: Int): Int = restingAt(size).x

    /** Put the circle up, parked at the edge. */
    fun show() {
        mark?.let { up ->
            // Back into view where it was put away, and never taken away if it was only
            // about to be: see [hide].
            main.removeCallbacks(putTheMarkAway)
            if (up.visibility != View.VISIBLE) up.visibility = View.VISIBLE
            // Already up, but not necessarily where it now belongs: the side it rests on is
            // the reader's to choose, and a mark that only moves when it is built again
            // stayed on the old edge until something else took it down. Left where it is
            // while a finger is on it, which would be the mark jumping out from under a hand.
            val size = up.width.takeIf { it > 0 } ?: markPx()
            val belongs = restingAt(size)
            if ((markX != belongs.x || markY != belongs.y) && !holding) {
                markX = belongs.x
                markY = belongs.y
                runCatching { wm.updateViewLayout(up, markParams(size)) }
            }
            // Where it is is said again anyway: a reader cannot see the window list, and
            // neither can a check - all either has is what the service reports, and reporting
            // it only the once means the mark is invisible to anything that started watching
            // afterwards.
            parked()
            return
        }
        val size = markPx()
        val waits = restingAt(size)
        markX = waits.x
        markY = waits.y
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "LENSNEW")
        }
        val view = HoverBubbleView(context)
        view.setOnTouchListener(Hand(view))
        runCatching { wm.addView(view, markParams(size)) }
            .onSuccess {
                mark = view
                parked()
            }
            .onFailure { android.util.Log.w("Phonetix", "the circle did not go up", it) }
    }

    /** Where the mark is sitting, for anything that can only read what the service says. */
    private fun parked() {
        if (!io.github.nanbiz.phonetix.BuildConfig.DEBUG) return
        val size = mark?.width?.takeIf { it > 0 } ?: markPx()
        android.util.Log.d("Phonetix", "LENSPARKED $markX,$markY,$size,$size")
    }

    /** Take it down, and everything it had drawn with it. */
    /** Take the circle down for good, which is what the service leaving means. */
    fun destroy() {
        hideLayer()
        hideDrop()
        mark?.let { runCatching { wm.removeView(it) } }
        mark = null
        hovered = null
    }

    /**
     * Take the circle off the screen, keeping the window it is drawn on.
     *
     * Put away and built again, it blinked: a screen that is in front for a moment - a
     * popup, a keyboard, this app's own settings - takes it down and the next read puts it
     * straight back, and what a reader sees is the mark flashing every time they touch
     * anything. Hiding a window is one call; building one is a window added, laid out and
     * drawn.
     */
    fun hide() {
        hideLayer()
        hideDrop()
        hovered = null
        onWord(null)
        // A moment later, not now. What is in front for a frame or two - a popup opening, a
        // keyboard, a screen on its way somewhere - takes the circle down and the next read
        // puts it straight back, and what a reader sees is the mark blinking every time they
        // touch anything. Anything that lasts is still gone a third of a second later.
        val up = mark ?: return
        if (up.visibility != View.VISIBLE) return
        main.removeCallbacks(putTheMarkAway)
        main.postDelayed(putTheMarkAway, SETTLE_MS)
    }

    private val putTheMarkAway = Runnable {
        val up = mark ?: return@Runnable
        if (up.visibility == View.VISIBLE) {
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "LENSGONE")
            }
            up.visibility = View.GONE
        }
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
        layoutInDisplayCutoutMode = intoTheCutout()
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
        layoutInDisplayCutoutMode = intoTheCutout()
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

    /** The target's window: the full width of the screen and as tall as its shade, at the
     *  foot, taking no touch. */
    private fun dropParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        dp(DropTargetView.SHADE_DP).roundToInt(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        layoutInDisplayCutoutMode = intoTheCutout()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }

    private fun showDrop(): DropTargetView? {
        drop?.let { return it }
        val view = DropTargetView(context).apply { lifted = footBar().toFloat() }
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            val centre = dropCentre()
            android.util.Log.d(
                "Phonetix",
                "LENSTARGET ${centre.x.roundToInt()},${centre.y.roundToInt()}",
            )
        }
        return runCatching { wm.addView(view, dropParams()) }
            .map { view.also { drop = it } }
            .onFailure { android.util.Log.w("Phonetix", "the target did not go up", it) }
            .getOrNull()
    }

    private fun hideDrop() {
        drop?.let { runCatching { wm.removeView(it) } }
        drop = null
    }

    /** Where the target's middle is on the screen when it is all the way up. */
    private fun dropCentre(): android.graphics.PointF {
        val edges = screen()
        return android.graphics.PointF(
            edges.width() / 2f,
            edges.height() - footBar() - dp(DropTargetView.BOTTOM_DP) -
                dp(DropTargetView.RADIUS_DP),
        )
    }

    /** How much of the foot of the screen the system's own bar takes: three buttons, or the
     *  gesture strip. */
    private fun footBar(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        } else {
            dp(FOOT_DP).roundToInt()
        }

    /** What the circle is over now, told once per word rather than once per frame. */
    private fun hoverAt(x: Int, y: Int) {
        lookedAt.set(x, y)
        val found = wordAt(x.toFloat(), y.toFloat())
        // The same word, even where its box has shifted: the screen is read again several
        // times a second, and on one whose content keeps changing - a chat, a feed - the box
        // under a still finger arrives a pixel from where it was. Compared exactly, every one
        // of those was a new word: a tick under the thumb and a card built again, without end.
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "LENSAT $x,$y -> ${found?.word ?: "nothing"}")
        }
        val was = hovered
        val same = found?.word == was?.word &&
            (found == null || was == null || RectF.intersects(found.rect, was.rect))
        if (same) {
            // The newest box all the same, so what is asked about is where the word is now.
            hovered = found
            if (found != null && !sweeping) onWord(found)
            return
        }
        hovered = found
        highlight?.mark(found?.rect?.let {
            Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        })
        if (found != null) {
            tookAnything = true
            // A tick under the thumb each time the circle takes a new word, since the eye is
            // on the word rather than on the circle. Never faster than a reader can move
            // between words: whatever else goes wrong above, the phone must not buzz without
            // stopping in a reader's hand.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastTick > TICK_APART_MS) {
                lastTick = now
                mark?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            if (sweeping && swept.none { it.word == found.word && it.rect == found.rect }) {
                swept.add(found)
                highlight?.gather(swept.map {
                    Rect(it.rect.left.toInt(), it.rect.top.toInt(),
                        it.rect.right.toInt(), it.rect.bottom.toInt())
                })
                // Answered while the finger is still down, a moment after the run stops
                // growing: the card goes when the button is let go, so a run asked about only
                // once it was finished would never be seen.
                if (swept.size > 1) {
                    main.removeCallbacks(askTheRun)
                    main.postDelayed(askTheRun, RUN_SETTLES_MS)
                }
            }
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

        /** Where the mark waited when the finger came down, as its centre: the point the
         *  carry is measured from. Taken then rather than asked each move, because a mark
         *  being dragged is under the finger, and a carry measured from the finger is a carry
         *  along one axis only - sideways, with the word under the thumb never lifted clear. */
        private var homeX = 0f
        private var homeY = 0f

        private var lastSwing = 0L
        private var asked = false

        private val swing = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                // The mark this hand belongs to may have been taken down mid-gesture - the
                // reader changed a setting, the service re-showed it - and a hand whose view
                // has gone never sees the finger lift. Its loop then runs for the life of the
                // app, asking what is under the point the circle was last at: a card opened
                // by itself whenever the app moved a word under that spot, over the keyboard,
                // coming and going as the reader typed.
                if (!active || !view.isAttachedToWindow) {
                    active = false
                    return
                }
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
            if (asked) {
                // Where the hand is and where it holds the circle, apart from where the circle
                // has swung to: the carry is decided by the first two, and a check reading the
                // swinging circle on a slow machine reads the leash's lag instead.
                if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Phonetix",
                        "LENSCARRY finger=${fingerX.roundToInt()},${fingerY.roundToInt()} " +
                            "want=${wantX.roundToInt()},${wantY.roundToInt()}",
                    )
                }
                // Nothing is asked about while the target holds the mark: the reader is
                // putting it away, and a card opening over the foot of the page is in the way.
                if (drop?.holding != true) hoverAt(ballX.roundToInt(), ballY.roundToInt())
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchedDownAt = android.os.SystemClock.uptimeMillis()
                    downX = event.rawX
                    downY = event.rawY
                    holding = true
                    dragging = false
                    formed = false
                    active = true
                    held = false
                    sweeping = false
                    swept.clear()
                    val waits = restingAt(view.width)
                    homeX = waits.x + view.width / 2f
                    homeY = waits.y + view.width / 2f
                    if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Phonetix",
                            "LENSHOME ${homeX.roundToInt()},${homeY.roundToInt()} " +
                                "down=${event.rawX.roundToInt()},${event.rawY.roundToInt()}",
                        )
                    }
                    view.active = true
                    // The target first, so that the circle and its thread, put up after it,
                    // are drawn over it rather than under it. It stays down until the finger
                    // nears the foot of the screen.
                    showDrop()
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
                    // Taken away by the system rather than let go - a screenshot, a call, the
                    // shade pulled over it - nothing is being asked and nothing is put away:
                    // the mark is simply back where it waits, in sight. Left to the thread's
                    // dissolve, it stayed masked when the dissolve never came, a mark that took
                    // touches and could not be seen.
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        cancelled()
                        return true
                    }
                    holding = false
                    active = false
                    view.active = false
                    main.removeCallbacks(hold)
                    Choreographer.getInstance().removeFrameCallback(swing)
                    highlight?.mark(null)
                    highlight?.gather(emptyList())
                    hovered = null
                    // The card goes with the finger: it is read while the button is held, and
                    // nothing on it is there to be touched afterwards.
                    main.removeCallbacks(askTheRun)
                    sweeping = false
                    swept.clear()
                    onWord(null)
                    // No hand on the screen any more: a card opened by a press after this
                    // would otherwise still be dodging a finger that had gone.
                    onHand(0, 0)
                    // A drag that passed over nothing is the fault a reader reports as "it
                    // does nothing", and what would explain it - which words this believed
                    // were on screen, and where - is gone by the time anyone can be asked. So
                    // it is written down as it happens.
                    if (dragging && !tookAnything && drop?.holding != true) {
                        onFoundNothing?.invoke()
                    }
                    tookAnything = false
                    val target = drop
                    if (dragging && target != null && target.holding) {
                        putAway(target)
                        return true
                    }
                    if (target?.present == true) {
                        target.present(false)
                        main.postDelayed({ if (!holding) hideDrop() }, DropTargetView.FALL_MS + 40)
                    } else {
                        hideDrop()
                    }
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
                        val thread = mist
                        if (thread == null) {
                            view.masked = false
                            hideLayer()
                        } else {
                            thread.dissolve(home.x.toFloat(), home.y.toFloat())
                            // And back in any case, should the dissolve never finish: its
                            // frames stop when its window is taken away under it.
                            main.postDelayed({
                                if (!holding && view.masked) {
                                    view.masked = false
                                    hideLayer()
                                }
                            }, UNMASK_AT_LATEST_MS)
                        }
                        ballVx = 0f
                        ballVy = 0f
                    } else {
                        hideLayer()
                        // Nothing was dragged, so nothing was being asked about a word: this
                        // is the press that asks about the whole screen. A press held long
                        // enough to arm a sweep and then let go asked for nothing, and
                        // replacing the page under it would be the opposite of what the
                        // reader had just decided not to do.
                        if (!held) onTap() else onHold()
                    }
                    return true
                }
            }
            return false
        }

        private fun cancelled() {
            holding = false
            active = false
            view.active = false
            main.removeCallbacks(hold)
            Choreographer.getInstance().removeFrameCallback(swing)
            sweeping = false
            swept.clear()
            hovered = null
            tookAnything = false
            onWord(null)
            onHand(0, 0)
            hideLayer()
            hideDrop()
            val size = view.width
            val waits = restingAt(size)
            markX = waits.x
            markY = waits.y
            runCatching { wm.updateViewLayout(view, markParams(size)) }
            view.masked = false
        }

        /**
         * Let go on the target: the thread falls into it, the two shrink away together, and
         * Phonetix is put away. The mark keeps the place it waited in before the drag, which
         * is where it is when the reader turns Phonetix on again.
         */
        private fun putAway(target: DropTargetView) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val size = view.width
            val waited = Point((homeX - size / 2f).roundToInt(), (homeY - size / 2f).roundToInt())
            val centre = dropCentre()
            mist?.onDissolved = { main.post { hideLayer() } }
            mist?.dissolve(centre.x, centre.y)
            ballVx = 0f
            ballVy = 0f
            target.swallow {
                main.post {
                    hideDrop()
                    // Out of sight before it is unmasked, so it does not fade back in where it
                    // was let go; it is put back where it waited, for the next time it is up.
                    view.visibility = View.GONE
                    view.masked = false
                    markX = waited.x
                    markY = waited.y
                    runCatching { wm.updateViewLayout(view, markParams(size)) }
                    if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                        android.util.Log.d("Phonetix", "LENSPUTAWAY")
                    }
                    onPutAway()
                }
            }
        }

        /**
         * Where the circle goes when the finger leaves: back to the edge it lives on, so what
         * was being read is not left with a disc sitting in the middle of it.
         */
        private fun park(): Point {
            val size = view.width
            val waits = restingAt(size)
            val target = waits.x
            val from = markX
            val restY = waits.y
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

        /**
         * The target at the foot of the screen: up while the finger is in the bottom of the
         * screen, holding the mark while the finger is on it. Held, the mark and the circle
         * are drawn into it, so what letting go will do is in sight before it is done.
         */
        private fun nearTheFoot(event: MotionEvent, size: Int) {
            val edges = screen()
            // Low on the screen, or lower than where the mark waited: the mark usually waits
            // low on the side already, and heading down from there is heading for the target,
            // which has to be in sight before the finger is on it.
            val low = event.rawY > edges.height() * (1f - FOOT_ZONE) ||
                event.rawY > homeY + dp(HEADING_DOWN_DP)
            val target = drop ?: return
            val centre = dropCentre()
            val off = hypot(event.rawX - centre.x, event.rawY - centre.y)
            // Taken nearer than it is let go, so a finger resting at the edge of it does not
            // make it snatch and drop the mark over and over.
            val over = if (target.holding) off < dp(LEAVE_DP) else off < dp(TAKE_DP)
            target.present(low || over)
            if (over != target.holding) {
                target.take(over)
                view.performHapticFeedback(
                    if (over) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.CLOCK_TICK,
                )
                if (over) {
                    hovered = null
                    highlight?.mark(null)
                    onWord(null)
                }
            }
            if (over) {
                markX = (centre.x - size / 2f).roundToInt()
                markY = (centre.y - size / 2f).roundToInt()
                runCatching { wm.updateViewLayout(view, markParams(size)) }
                wantX = centre.x
                wantY = centre.y
            }
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
            // The circle is carried away from where the hand comes onto the screen.
            //
            // The point it is measured from is where the mark waits - the corner the hand
            // comes in at, or wherever the reader put it. Everything the thumb covers is
            // between that place and whatever it is pointing at, so carrying the circle
            // further out along that line is what keeps the hand off the word.
            //
            // It grows with the distance from there: nothing at all when the finger is on
            // it - so that place itself can be pointed at - and the full carry a fifth of a
            // screen away and beyond. That makes the whole screen reachable: the circle is
            // always further out than the finger, so the far edges come within reach, and the
            // near ones are had by bringing the hand back to where it rests.
            //
            // A carry that is the same everywhere leaves a band as deep as itself along the
            // edge it points away from, which is why the last strip of a page could not be
            // pointed at at all. Turning the carry to face the way the hand is moving was
            // tried and is worse: a nudge towards a word swings the circle a finger's length
            // past it, so nothing can be aimed at precisely. Here the aim is one for one
            // outside the growing part and half as much again inside it.
            val edges = screen()
            val awayX = event.rawX - homeX
            val awayY = event.rawY - homeY
            val away = kotlin.math.hypot(awayX, awayY)
            val grows = minOf(edges.width(), edges.height()) * GROWS_WITHIN
            val carry = lift * (away / grows).coerceIn(0f, 1f)
            wantX = if (away > 0f) event.rawX + awayX / away * carry else event.rawX
            wantY = if (away > 0f) event.rawY + awayY / away * carry else event.rawY
            wantX = wantX.coerceIn(0f, edges.width().toFloat())
            wantY = wantY.coerceIn(0f, edges.height().toFloat())
            fingerX = event.rawX
            fingerY = event.rawY
            nearTheFoot(event, size)
            onHand(event.rawX.roundToInt(), event.rawY.roundToInt())
            if (!formed) {
                formed = true
                // The icon stays in sight under the finger for the whole drag, and the thread
                // is drawn out of it.
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

    /**
     * Into the cutout, so the circle can be carried to the very top of the screen.
     *
     * "Always" exists from Android 11; before that the nearest is the short edges, which is
     * where a phone's cutout is.
     */
    private fun intoTheCutout(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    private companion object {
        /** How long a screen has to be in front before the circle is taken down for it. */
        const val SETTLE_MS = 350L

        /** How much of the bottom the system's own gesture strip takes, in dp. */
        const val FOOT_DP = 56f

        /** Where the mark waits, as a share of the screen's height: down by the hand, without
         *  being in the corner it comes in at. */
        const val HOME_DOWN = 0.8f

        /** How far from that corner the carry reaches its full length, as a share of the
         *  screen. */
        const val GROWS_WITHIN = 0.2f

        /** The shortest gap between two ticks under the thumb. */
        const val TICK_APART_MS = 90L

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

        /** How much of the screen, from the foot, brings up the target the mark is put away
         *  on. */
        const val FOOT_ZONE = 0.3f

        /** How far below where the mark waited the finger has to go for the target to rise,
         *  in dp. */
        const val HEADING_DOWN_DP = 48f

        /** The longest the mark stays hidden behind its thread once it is let go. */
        const val UNMASK_AT_LATEST_MS = 1500L

        /** How near the target's middle the finger has to come for it to take the mark, and
         *  how far it has to go again to be let go, in dp. */
        const val TAKE_DP = 64f
        const val LEAVE_DP = 88f

        /** How long a swept run has to stop growing before it is asked about. */
        const val RUN_SETTLES_MS = 250L

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
