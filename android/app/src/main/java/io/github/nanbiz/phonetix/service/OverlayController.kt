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
import android.view.WindowManager
import io.github.nanbiz.phonetix.core.SettingsStore
import io.github.nanbiz.phonetix.core.WordBox
import kotlin.math.roundToInt

/**
 * The transcriptions drawn over the app in front, one small window per word.
 *
 * A window per word rather than one sheet over the screen, for two reasons. It can be
 * touched: a full-screen sheet would either swallow every tap meant for the app beneath or,
 * made untouchable, refuse the taps meant for the transcriptions themselves, while a window
 * that covers only a word takes the taps on that word and leaves the rest of the screen
 * alone. And it covers exactly the word: the window is the word's own rectangle, so nothing
 * of the original shows around the edges.
 *
 * The windows are pooled and moved rather than added and removed, because the words shift
 * on every scroll and window churn is the expensive part.
 */
class OverlayController(
    private val context: Context,
    private val onWordTapped: (WordBox) -> Unit,
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val chips = ArrayList<ChipView>(MAX_CHIPS)
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
    private val putAway = Runnable {
        for (c in chips) {
            if (c.visibility == View.GONE) continue
            c.moveToken++
            c.visibility = View.GONE
        }
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
    private val silent: Boolean get() = SettingsStore.current.layer == "off"

    /** The words this believes are on screen, which is what the mark asks about. */
    fun onScreen(): List<WordBox> = lastRendered

    /** What is on screen as far as this believes, for [StateDump]: the mark asks this list
     *  what it is over, so a card about the wrong word starts here. */
    fun state(): org.json.JSONObject = org.json.JSONObject()
        .put("silent", silent)
        .put("inMotion", motion.isRunning)
        .put("chips", chips.size)
        // Visible and solid: a window faded by [hideNow] is still up for a moment afterwards,
        // and what this reports is what a reader can see.
        .put("chipsShown", chips.count { it.visibility == View.VISIBLE && it.alpha > 0f })
        .put("words", lastRendered.size)
        .put("boxes", StateDump.boxes(lastRendered))

    /**
     * The word under a point on the screen, or nothing when the point is on no word.
     *
     * What the lens is dragged over: it does not take the app's touches, so it has to ask
     * what it is passing rather than being told by a tap.
     */
    fun wordAt(x: Float, y: Float): WordBox? {
        val words = lastRendered
        val at = Pointing.nearest(
            words.map { Pointing.Box(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom) },
            x,
            y,
        )
        return words.getOrNull(at)
    }

    /**
     * While the screen is moving the whole set rides on one layer instead of a window each:
     * carrying a dozen windows would be a dozen calls to the window manager every frame,
     * and the layer moves by shifting a canvas. The small windows come back when it stops,
     * because those are what can be tapped.
     */
    fun beginMotion() {
        if (silent || motion.isRunning) return
        main.removeCallbacks(putAway)
        // Faded rather than taken down, for the reason [putAway] gives: there is a window
        // per word, and changing their visibility is a call into the window manager each.
        // Measured at the start of a scroll, ninety-six of them held the main thread - which
        // is what draws - for 733ms, so the words stood still through the first half second
        // of the movement they were meant to be riding. Fading is a draw and nothing more:
        // 274ms for eighty. They are solid again in [render], which is what ends the motion.
        for (c in chips) if (c.visibility != View.GONE && c.alpha != 0f) c.alpha = 0f
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
        var faded = false
        for (c in chips) {
            if (c.visibility == View.GONE || c.alpha == 0f) continue
            c.moveToken++
            c.alpha = 0f
            faded = true
        }
        // Off the screen in this frame, and out of the window manager shortly after. See
        // [putAway] for why the two are not the same thing. Always later, never here: a
        // second hide arriving during the same transition would otherwise make exactly the
        // call this exists to keep out of it.
        if (!faded && chips.none { it.visibility != View.GONE }) return
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
    fun known(boxes: List<WordBox>) = keep(boxes)

    fun render(boxes: List<WordBox>) {
        main.removeCallbacks(putAway)
        if (silent) {
            keep(boxes)
            return
        }
        // Painting the small windows means the motion is over, whether it ended by settling
        // or because the screen changed under it. Leaving the layer up would hide every one
        // of them, and nothing would be tappable again.
        if (motion.isRunning) motion.stop()
        lastRendered = boxes
        val wanted = if (boxes.size > MAX_CHIPS) boxes.subList(0, MAX_CHIPS) else boxes
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG && boxes.size > wanted.size) {
            android.util.Log.d(
                "Phonetix",
                "RENDER only ${wanted.size} of ${boxes.size}; the rest have no window",
            )
        }
        // How many transcriptions are on screen, as opposed to how many were planned. The
        // two part company whenever something else owns the screen - the replaced page, a
        // card, an app we do not read - and only this says which.
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "SHOWING ${wanted.size}")
        }
        while (chips.size < wanted.size) if (!addChip()) break
        for (i in wanted.indices) {
            val chip = chips.getOrNull(i) ?: break
            val box = wanted[i]
            val says = chip.bind(box, reveal)
            // Changing what a window says takes effect on the next draw; moving it has to
            // go through the window manager and lands a frame later. Do both to a visible
            // window and it paints the new word at the old word's place for that frame,
            // which is a transcription flashing somewhere it has no business being. So a
            // window that has to move is hidden first and shown again only once the move
            // has actually been applied.
            //
            // Only a window that is going to say something different, though. One that is
            // simply following its word says the same thing at the old place as at the new,
            // so a frame at the old place is a frame of it being a pixel or two behind
            // rather than a frame of it being wrong - and hiding it instead is a frame of
            // nothing at all. Photographed while the page moved with the words back on these
            // windows, that is most of the screen bare: every word moves every pass, so
            // every window spent every other frame invisible.
            if (chip.alpha != 1f) chip.alpha = 1f
            if (place(chip, box.rect) && says) {
                chip.visibility = View.INVISIBLE
                val token = ++chip.moveToken
                chip.post { if (chip.moveToken == token) chip.visibility = View.VISIBLE }
            } else if (chip.visibility != View.VISIBLE) {
                chip.visibility = View.VISIBLE
            }
        }
        for (i in wanted.size until chips.size) {
            if (chips[i].visibility != View.GONE) {
                chips[i].moveToken++
                chips[i].visibility = View.GONE
            }
        }
    }

    fun clear() = hideNow()

    /**
     * Take touches, or stop taking them, because the reader has said which they want.
     *
     * The flag is fixed when a window is added, and these are pooled and kept for the life of
     * the service, so a setting changed while they are up reaches nothing without this.
     */
    fun applyTouchability() {
        val take = SettingsStore.current.touchWords
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        for (c in chips) {
            val lp = c.layoutParams as? WindowManager.LayoutParams ?: continue
            val next = if (take) lp.flags and flag.inv() else lp.flags or flag
            if (next == lp.flags) continue
            lp.flags = next
            runCatching { wm.updateViewLayout(c, lp) }
        }
    }

    /** Repaint every chip, so revealing one word also un-reveals the last one. */
    private fun refresh() {
        for (c in chips) if (c.visibility == View.VISIBLE) c.invalidate()
    }

    fun destroy() {
        motion.stop()
        for (c in chips) runCatching { wm.removeView(c) }
        chips.clear()
    }

    private fun addChip(): Boolean {
        val v = ChipView(context, reveal, onWordTapped) { refresh() }
        OverlayMute.apply(v)
        v.visibility = View.GONE
        val ok = runCatching { wm.addView(v, params(0, 0, 1, 1)) }.isSuccess
        if (!ok) return false
        chips.add(v)
        return true
    }

    /** Applies the window's geometry; true when it actually had to move. */
    private fun place(chip: ChipView, rect: RectF): Boolean {
        // A hair of bleed on each side so an antialiased edge of the original word cannot
        // peek out from under its replacement, while the width still matches the word.
        val x = (rect.left - BLEED).roundToInt()
        val y = (rect.top - BLEED).roundToInt()
        val w = (rect.width() + BLEED * 2).roundToInt().coerceAtLeast(1)
        val h = (rect.height() + BLEED * 2).roundToInt().coerceAtLeast(1)
        val lp = chip.layoutParams as? WindowManager.LayoutParams ?: return false
        if (lp.x == x && lp.y == y && lp.width == w && lp.height == h) return false
        lp.x = x; lp.y = y; lp.width = w; lp.height = h
        runCatching { wm.updateViewLayout(chip, lp) }
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
        // And by default not touchable either, which is what lets a reader scroll. These
        // windows lie over the words themselves, so a finger that comes down on one comes
        // down on it and not on the app - and a window that has taken a gesture keeps it,
        // whatever it does with its flags afterwards, so that whole swipe is lost and the
        // page stands still. On a page of text most swipes start on a word. A reader who
        // wants to open cards by pressing a word turns them touchable and takes that back.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (SettingsStore.current.touchWords) 0
            else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private companion object {
        /** How long the faded windows are left up for, past the transition that made taking
         *  them down expensive. */
        const val PUT_AWAY_MS = 700L
        const val MAX_CHIPS = 96
        const val BLEED = 1.5f
    }
}

/**
 * One word's replacement. Opaque, exactly the size of the word it covers, and tappable:
 * a tap puts the original word back for a moment, which is the phone's answer to hovering
 * a word in the browser.
 */
/** Which word is showing itself rather than its transcription, and until when. */
class RevealState {
    var word: String? = null
    var until: Long = 0L

    fun isRevealed(w: String): Boolean =
        word == w && android.os.SystemClock.uptimeMillis() < until

    fun toggle(w: String, holdMs: Long) {
        if (isRevealed(w)) { word = null; until = 0L }
        else { word = w; until = android.os.SystemClock.uptimeMillis() + holdMs }
    }
}

class ChipView(
    context: Context,
    private val reveal: RevealState,
    private val onTapped: (WordBox) -> Unit,
    private val onChanged: () -> Unit,
) : View(context) {

    /** Bumped whenever the window is moved or retired, so a pending show for an older
     *  position does not reveal a window that has since been moved again. */
    var moveToken = 0

    private var box: WordBox? = null
    private val revert = Runnable { onChanged() }
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val painter = ChipPainter()
    private val dest = RectF()

    /** @return whether this window now says something different from what it said */
    fun bind(next: WordBox, state: RevealState): Boolean {
        val changed = box?.ipa != next.ipa || box?.word != next.word ||
            box?.background != next.background || box?.ink != next.ink
        box = next
        if (changed) invalidate()
        return changed
    }

    /** Opens the card, if the finger stays put long enough to mean it. */
    private val held = Runnable {
        val b = box ?: return@Runnable
        heldDown = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showTheWord(b)
        onTapped(b)
    }
    private var heldDown = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                heldDown = false
                // The card waits for a press held, so that reading a page never opens one by
                // accident: these windows cover the words themselves, and every touch that
                // lands on text lands on one of them.
                removeCallbacks(held)
                postDelayed(held, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    (kotlin.math.abs(event.rawX - downX) > slop ||
                        kotlin.math.abs(event.rawY - downY) > slop)
                ) {
                    // The finger is scrolling, not tapping. These windows sit on top of the
                    // app, so holding on to the gesture would stop the app scrolling at all
                    // wherever a transcription happens to be - which is most of a page of
                    // text. Step out of the way and let the rest of the gesture reach it.
                    dragging = true
                    removeCallbacks(held)
                    passThrough(true)
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && !dragging && !heldDown) {
            removeCallbacks(held)
            val b = box ?: return true
            // A tap shows the word that is underneath, which is the quick question - what
            // did that say? - and leaves the card to a press held.
            showTheWord(b)
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

    /** Put the original word back for a moment, so the reader sees both. */
    private fun showTheWord(b: WordBox) {
        reveal.toggle(b.word, REVEAL_MS)
        removeCallbacks(revert)
        postDelayed(revert, REVEAL_MS + 50)
        onChanged()
    }

    /** Let touches through to the app underneath, or take them again. */
    private fun passThrough(on: Boolean) {
        val lp = layoutParams as? WindowManager.LayoutParams ?: return
        // Nothing to hand back when they were never taking touches to begin with.
        if (!SettingsStore.current.touchWords) return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (on) lp.flags or flag else lp.flags and flag.inv()
        if (next == lp.flags) return
        lp.flags = next
        runCatching {
            (context.getSystemService(WindowManager::class.java)).updateViewLayout(this, lp)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = box ?: return
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        dest.set(0f, 0f, width.toFloat(), height.toFloat())
        painter.draw(canvas, dest, b, dark, reveal.isRevealed(b.word))
    }

    private companion object {
        const val REVEAL_MS = 2500L
    }
}
