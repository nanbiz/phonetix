package io.github.nanbiz.phonetix.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.github.nanbiz.phonetix.core.SettingsStore
import io.github.nanbiz.phonetix.core.WordBox
import kotlin.math.roundToInt

/**
 * The transcriptions drawn over the app in front, and the touches they take.
 *
 * Two different things, and one window each rather than one window per word. The whole
 * screenful is painted on a single layer: a window per word is a hard ceiling - a page with
 * more words than the platform will give windows was transcribed in part and the rest left
 * bare - and every pass moved and repainted each of them, a call into the window manager
 * apiece. A hundred and thirty of those exhausted the graphics buffers of a device outright.
 *
 * Touches are taken by an invisible window over each line of text, not each word. A line is
 * a dozen words, so a screenful is a dozen windows instead of a hundred, and a touch inside
 * one is matched to the word nearest it - which also means a tap answers a word the bar did
 * not pick, and answers one while nothing at all is being drawn.
 */
class OverlayController(
    private val context: Context,
    private val onWordTapped: (WordBox) -> Unit,
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val rows = ArrayList<TouchRow>(MAX_ROWS)
    private val motion = MotionLayer(context)
    private var lastRendered: List<WordBox> = emptyList()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * The windows themselves, once there is no hurry about it.
     *
     * A window's visibility is a call into the window manager, and there is a window per
     * word. Forty-nine of those at once, while the platform was animating another app into
     * the front, held the main thread for 1.4 seconds - and the main thread is what draws,
     * so the words of the app before stayed painted over the new app for the whole of it.
     * [hideNow] therefore fades them, which is a draw and nothing more, and the windows go
     * down here, after the transition that made the calls expensive is over.
     */
    /**
     * The whole screenful on one window, for when the words do not have to be touchable.
     *
     * A window per word is what makes a word tappable, and it is also a hard ceiling: only so
     * many windows can be up at once, so a page with more words than that was transcribed in
     * part and the rest left bare - ninety-six of a hundred and forty-four on a page a reader
     * was reading. Every pass also moved and repainted all of them, one call into the window
     * manager each. Where the reader has not asked to hold a word for its card, none of that
     * buys anything, so the page is drawn on one canvas instead: no ceiling, one draw.
     */
    private var still: StillLayer? = null

    private val putAway = Runnable {
        for (c in rows) if (c.visibility != View.GONE) c.visibility = View.GONE
    }

    /**
     * Nothing is painted over a word, and every word is still known.
     *
     * The replacement mode the reader can choose is off: they want the page as its app wrote
     * it and the mark to answer a word they point at. Asked here, of the one place that
     * paints, because there are four ways a word reaches the screen - a full read, the layer
     * a scroll rides on, the end of that scroll, and the reveal - and a guard on one of them
     * left the other three painting.
     */
    private val silent: Boolean get() = SettingsStore.current.quiet

    /** The words this believes are on screen, which is what the mark asks about. */
    fun onScreen(): List<WordBox> = lastRendered

    /** What is on screen as far as this believes, for [StateDump]: the mark asks this list
     *  what it is over, so a card about the wrong word starts here. */
    fun state(): org.json.JSONObject = org.json.JSONObject()
        .put("silent", silent)
        .put("inMotion", motion.isRunning)
        .put("chips", rows.size)
        // What a reader can see, which is not what is up: the windows that take touches
        // paint nothing at all, and with the overlay off they are all there is.
        .put("chipsShown", still?.drawn() ?: 0)
        .put("touchable", rows.count { it.visibility == View.VISIBLE })
        .put("words", lastRendered.size)
        .put("boxes", StateDump.boxes(lastRendered))

    /**
     * The word under a point on the screen, or nothing when the point is on no word.
     *
     * What the lens is dragged over: it does not take the app's touches, so it has to ask
     * what it is passing rather than being told by a tap.
     *
     * [held] is the word the lens already sits on, if any. It keeps the lens until the point
     * is plainly on another word or off the text (see [Pointing.settle]); it is found in the
     * newest boxes by its word and by where it was, since the screen is read again while the
     * lens rests on it and the box arrives a pixel or two from where it was.
     */
    fun wordAt(x: Float, y: Float, held: WordBox? = null): WordBox? {
        val words = lastRendered
        val boxes = words.map { Pointing.Box(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom) }
        val at = if (held == null) {
            Pointing.nearest(boxes, x, y)
        } else {
            val kept = words.indexOfFirst {
                it.word == held.word && android.graphics.RectF.intersects(it.rect, held.rect)
            }
            Pointing.settle(boxes, kept, x, y)
        }
        return words.getOrNull(at)
    }

    /**
     * While the screen is moving the words ride on the motion layer, which follows the page
     * by shifting a canvas rather than by moving windows.
     *
     * The rows that take touches go down with them: they sit where the words were, and a word
     * that has scrolled away is not the word a touch there would now be about. They come back
     * when the page settles, a moment later and through [putAway] rather than here, because
     * a window's visibility is a call into the window manager on the thread that draws.
     */
    fun beginMotion() {
        if (silent || motion.isRunning) return
        main.removeCallbacks(putAway)
        takeTheLayerDown()
        main.postDelayed(putAway, PUT_AWAY_MS)
        motion.start(lastRendered)
    }

    /**
     * @param at when the positions were actually read, which is not when they arrive here
     * @param speed how fast the page was going when they were read, in pixels a millisecond,
     *   measured by the read itself rather than worked out again from these positions
     */
    fun motionMeasured(boxes: List<WordBox>, at: Long, speed: Float, movedSince: Boolean) {
        lastRendered = boxes
        if (silent) return
        motion.measured(boxes, at, speed, movedSince)
    }

    /** The page says it has moved by this much since it last said so. */
    fun told(dy: Float, exact: Boolean, at: Long) = motion.told(dy, exact, at)

    fun endMotion(boxes: List<WordBox>) {
        motion.stop()
        render(boxes)
    }

    /** Where the words are, without painting anything over them: see [silent]. */
    private fun keep(boxes: List<WordBox>) {
        if (motion.isRunning) motion.stop()
        lastRendered = boxes
        hideNow()
    }

    val inMotion: Boolean get() = motion.isRunning

    /**
     * Which word is currently showing itself instead of its transcription.
     *
     * Held here, against the word, rather than in the view that was tapped: the screen is
     * re-read constantly, and each read hands the pooled windows a fresh list that may be
     * in a different order, so a flag living in one view would be wiped by the next pass a
     * moment after the tap.
     */
    private val reveal = RevealState()

    /**
     * Everything off the screen, now.
     *
     * The layer has to come down with the windows. It draws the whole set by itself, so
     * hiding only the windows left the last transcriptions painted over whatever came next
     * - they survived the overlay being switched off, and the app they belonged to being
     * closed, because nothing was left to take them down.
     */
    fun hideNow() {
        motion.stop()
        takeTheLayerDown()
        // The rows go down after the moment that made taking them down expensive - an app
        // coming to the front, most often - rather than in the middle of it. They draw
        // nothing, so leaving them up for that moment shows the reader nothing at all; what
        // it would cost is a touch landing on a row belonging to the app before.
        main.removeCallbacks(putAway)
        main.postDelayed(putAway, PUT_AWAY_MS)
    }

    /**
     * Know where the words are without painting anything over them.
     *
     * What the mark is dragged over is this list, so a reader who has asked for nothing to be
     * replaced still has every word to ask about - the page is simply left as its app wrote
     * it.
     */
    fun known(boxes: List<WordBox>) {
        if (motion.isRunning) motion.stop()
        lastRendered = boxes
        takeTheLayerDown()
        // Nothing is painted, and a word can still be asked about by touching it: the rows
        // are invisible either way, so the page is the app's own and a tap on it still
        // answers.
        putRows(boxes)
    }

    /**
     * Keep the transcriptions off the screen, and keep them off.
     *
     * The colours of a line are read off a photograph of the screen taken with our own paint
     * down, because our paint is the wrong answer to what colour the app's text is. Taking it
     * down is not enough on its own: a word is drawn the moment it is read now, so an ordinary
     * read landing while the camera was open painted the page again and the photograph came
     * back with our own fallback gold in it - which was then read as the app's ink.
     */
    /** Take the transcriptions off the screen so it can be photographed without them. */
    fun holdDown(on: Boolean) {
        when {
            on -> hideNow()
            lastRendered.isEmpty() -> Unit
            // Nothing was painted to put back, and the rows that take touches still went
            // down with everything else: without this a screen read while the overlay was
            // off ended with no way to ask about any word on it.
            silent -> known(lastRendered)
            else -> render(lastRendered)
        }
    }

    fun render(boxes: List<WordBox>) {
        // Not while this screen is being photographed for its colours: see [holdDown]. What is
        // known is kept either way, so the mark can still answer any of it.
        main.removeCallbacks(putAway)
        if (silent) {
            known(boxes)
            return
        }
        // Painting means the motion is over, whether it ended by settling or because the
        // screen changed under it.
        if (motion.isRunning) motion.stop()
        lastRendered = boxes
        drawAtOnce(boxes)
        // How many transcriptions are on screen, as opposed to how many were planned. The
        // two part company whenever something else owns the screen - the replaced page, a
        // card, an app we do not read - and only this says which.
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "SHOWING ${boxes.size}")
        }
        putRows(boxes)
    }

    /**
     * Put an invisible window over each line of text, and take the spare ones down.
     *
     * A line rather than a word: a screenful of words is more windows than a device will
     * give, and every one of them is a call into the window manager on every pass, while the
     * lines of a screen are a dozen. What a touch inside one is about is worked out from
     * where it landed, so the words need no window of their own to be asked about.
     */
    private fun putRows(boxes: List<WordBox>) {
        // Whatever was going to take the rows down is out of date: these are the rows of the
        // screen in front now. Without this a read that landed inside the moment [hideNow]
        // allows for a transition had its rows taken down again by that transition's own
        // delayed call, and the page ended up with nothing to touch.
        main.removeCallbacks(putAway)
        // None: a word is asked about with the side button, and the page underneath keeps
        // every touch.
        val wanted = emptyList<RectF>()
        while (rows.size < wanted.size) if (!addRow()) break
        for (i in wanted.indices) {
            val row = rows.getOrNull(i) ?: break
            place(row, wanted[i])
            if (row.visibility != View.VISIBLE) row.visibility = View.VISIBLE
        }
        for (i in wanted.size until rows.size) {
            if (rows[i].visibility != View.GONE) rows[i].visibility = View.GONE
        }
    }

    /**
     * The words grouped into the lines they sit on, as one rectangle each.
     *
     * By where they are rather than by what the app called a line: the boxes are what is
     * actually on the screen, and two of them on the same row of pixels are one line to a
     * finger whatever the tree they came out of said.
     */
    private fun lines(boxes: List<WordBox>): List<RectF> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val out = ArrayList<RectF>(16)
        var run: RectF? = null
        var height = 0f
        for (b in sorted) {
            val r = b.rect
            val same = run != null && kotlin.math.abs(r.centerY() - run.centerY()) <
                maxOf(height, r.height()) * SAME_LINE
            if (same && run != null) {
                run.union(r)
            } else {
                run?.let { out.add(it) }
                run = RectF(r)
                height = r.height()
            }
        }
        run?.let { out.add(it) }
        return out
    }

    /**
     * Put the whole screenful up at once, on the one window.
     *
     * The small windows come down with it, because a word drawn twice is a word drawn twice.
     */
    private fun drawAtOnce(boxes: List<WordBox>) {
        if (boxes.isEmpty()) {
            takeTheLayerDown()
            return
        }
        val view = still ?: StillLayer(context, reveal).also { made ->
            OverlayMute.apply(made)
            val lp = params(
                0, 0,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Never takes a touch. It covers the whole screen, and a full-screen window that
            // takes touches takes the reader's scrolling with it.
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (runCatching { wm.addView(made, lp) }.isFailure) return
            still = made
        }
        view.show(boxes)
    }

    /**
     * Stop drawing the words, without touching the window they are drawn on.
     *
     * A window's visibility is a relayout, which is a call into the window manager on the
     * thread that draws, and the platform put up "Phonetix isn't responding" inside one. The
     * layer has nothing to say when it holds no words, so emptying it is a draw and costs
     * nothing but the frame it happens in.
     */
    private fun takeTheLayerDown() {
        still?.show(emptyList())
    }

    fun clear() = hideNow()

    /**
     * Take touches, or stop taking them, because the reader has said which they want.
     *
     * The rows exist only to take touches, so the answer is whether they are there at all.
     */
    fun applyTouchability() {
        putRows(lastRendered)
    }

    /** Draw the page again, which is how a word lifted off it goes back on. */
    private fun refresh() {
        still?.invalidate()
    }

    fun destroy() {
        motion.stop()
        still?.let { v -> runCatching { wm.removeView(v) } }
        still = null
        for (c in rows) runCatching { wm.removeView(c) }
        rows.clear()
    }

    private fun addRow(): Boolean {
        val v = TouchRow(context, reveal, { x, y -> wordAt(x, y) }, onWordTapped) { refresh() }
        OverlayMute.apply(v)
        v.visibility = View.GONE
        val ok = runCatching { wm.addView(v, params(0, 0, 1, 1)) }.isSuccess
        if (!ok) return false
        rows.add(v)
        return true
    }

    /** Applies the window's geometry; true when it actually had to move. */
    private fun place(row: TouchRow, rect: RectF): Boolean {
        // A hair of bleed on each side, so a tap that lands on the very edge of a word is
        // still a tap on the line it belongs to.
        val x = (rect.left - BLEED).roundToInt()
        val y = (rect.top - BLEED).roundToInt()
        val w = (rect.width() + BLEED * 2).roundToInt().coerceAtLeast(1)
        val h = (rect.height() + BLEED * 2).roundToInt().coerceAtLeast(1)
        val lp = row.layoutParams as? WindowManager.LayoutParams ?: return false
        if (lp.x == x && lp.y == y && lp.width == w && lp.height == h) return false
        lp.x = x; lp.y = y; lp.width = w; lp.height = h
        runCatching { wm.updateViewLayout(row, lp) }
        return true
    }

    @SuppressLint("WrongConstant")
    private fun params(x: Int, y: Int, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, x, y,
        // An accessibility overlay, not an application one. A window put up with
        // SYSTEM_ALERT_WINDOW is hidden by the platform over any screen that asks for it -
        // the settings app asks, and so does every permission dialog, to stop a window from
        // covering what the reader is agreeing to. Over those screens the windows were still
        // there, still visible, still in the right places, and nothing was painted: a real
        // app that showed no transcriptions at all while the log said it had drawn eighteen.
        // This type belongs to the service that is already reading the screen, is exempt
        // from that hiding, and needs no permission of its own.
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Never focusable: the app underneath keeps the keyboard and every touch outside
        // these small windows.
        //
        // And not touchable either, which is what lets a reader scroll: these windows lie over
        // the words themselves, and a window that has taken a gesture keeps it.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        overScreen(this)
    }

    private companion object {
        /** How long the rows are left up for, past the transition that made taking them down
         *  expensive. */
        const val PUT_AWAY_MS = 700L

        /** How many lines of a screen can take touches. A screenful of text is a dozen or
         *  two; the cap is what keeps a page that reports hundreds of stray boxes from
         *  asking the platform for hundreds of windows. */
        const val MAX_ROWS = 32

        /** How far apart two words' middles may be, as a share of a line's height, and still
         *  be the same line to a finger. */
        const val SAME_LINE = 0.6f
        const val BLEED = 1.5f
    }
}

/**
 * One word's replacement. Opaque, exactly the size of the word it covers, and tappable:
 * a tap puts the original word back for a moment, which is the phone's answer to hovering
 * a word in the browser.
 */
/**
 * Which words are showing themselves rather than their transcriptions, and until when.
 *
 * A press held on any word lifts the whole overlay for a moment: what the reader wants then
 * is the page as it was written, and lifting the one word under a fingertip leaves them
 * reading around their own hand.
 */
class RevealState {
    var all: Boolean = false
    var until: Long = 0L

    /** Whether the overlay is off the page right now. */
    fun lifted(): Boolean = all && android.os.SystemClock.uptimeMillis() < until

    fun isRevealed(w: String): Boolean = lifted()

    /** Lift the overlay off the page, or put it back if it is already off. */
    fun peek(holdMs: Long) {
        if (all && android.os.SystemClock.uptimeMillis() < until) { all = false; until = 0L }
        else { all = true; until = android.os.SystemClock.uptimeMillis() + holdMs }
    }
}

/**
 * One line of the page, taking the touches that land on it.
 *
 * Invisible: the words are painted by the layer, and this is here for the finger. A tap asks
 * about the word nearest where it landed; a press held takes the whole overlay off the page,
 * which is what a reader wants when they want to see what was written rather than what we
 * put there; and a finger that moves is scrolling, so the row steps out of the way and lets
 * the rest of that gesture reach the app.
 */
class TouchRow(
    context: Context,
    private val reveal: RevealState,
    private val wordAt: (Float, Float) -> WordBox?,
    private val onTapped: (WordBox) -> Unit,
    private val onChanged: () -> Unit,
) : View(context) {

    private val revert = Runnable { onChanged() }
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var heldDown = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /** Lifts the overlay off the page, if the finger stays put long enough to mean it. */
    private val held = Runnable {
        heldDown = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showThePage()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                heldDown = false
                removeCallbacks(held)
                postDelayed(held, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    (kotlin.math.abs(event.rawX - downX) > slop ||
                        kotlin.math.abs(event.rawY - downY) > slop)
                ) {
                    // The finger is scrolling, not tapping. These windows lie over the text,
                    // so holding on to the gesture would stop the app scrolling wherever
                    // there is a line of it - which is most of a page. Step out of the way
                    // and let the rest of the gesture reach it.
                    dragging = true
                    removeCallbacks(held)
                    passThrough(true)
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && !dragging && !heldDown) {
            removeCallbacks(held)
            // The word nearest where the finger landed, which is what the reader meant: the
            // window is a line, and a line is a dozen words.
            wordAt(event.rawX, event.rawY)?.let(onTapped)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            removeCallbacks(held)
            // Takeable again once the gesture is over.
            if (dragging) postDelayed({ passThrough(false); dragging = false }, 120)
        }
        return true
    }

    /** Take the overlay off the page for a moment, so the reader sees what was written. */
    private fun showThePage() {
        reveal.peek(REVEAL_MS)
        removeCallbacks(revert)
        postDelayed(revert, REVEAL_MS + 50)
        onChanged()
    }

    /** Let touches through to the app underneath, or take them again. */
    private fun passThrough(on: Boolean) {
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (on) lp.flags or flag else lp.flags and flag.inv()
        if (next == lp.flags) return
        lp.flags = next
        runCatching {
            (context.getSystemService(WindowManager::class.java)).updateViewLayout(this, lp)
        }
    }

    private companion object {
        /** How long the page stays as its app wrote it after a press held on it. */
        const val REVEAL_MS = 2500L
    }
}
