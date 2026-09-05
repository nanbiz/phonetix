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

    /**
     * While the screen is moving the whole set rides on one layer instead of a window each:
     * carrying a dozen windows would be a dozen calls to the window manager every frame,
     * and the layer moves by shifting a canvas. The small windows come back when it stops,
     * because those are what can be tapped.
     */
    fun beginMotion() {
        if (motion.isRunning) return
        for (c in chips) if (c.visibility != View.GONE) c.visibility = View.GONE
        motion.start(lastRendered)
    }

    fun motionMeasured(boxes: List<WordBox>) {
        lastRendered = boxes
        motion.measured(boxes)
    }

    fun endMotion(boxes: List<WordBox>) {
        motion.stop()
        render(boxes)
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
        for (c in chips) if (c.visibility != View.GONE) c.visibility = View.GONE
    }

    fun render(boxes: List<WordBox>) {
        // Painting the small windows means the motion is over, whether it ended by settling
        // or because the screen changed under it. Leaving the layer up would hide every one
        // of them, and nothing would be tappable again.
        if (motion.isRunning) motion.stop()
        lastRendered = boxes
        val wanted = if (boxes.size > MAX_CHIPS) boxes.subList(0, MAX_CHIPS) else boxes
        while (chips.size < wanted.size) if (!addChip()) break
        for (i in wanted.indices) {
            val chip = chips.getOrNull(i) ?: break
            val box = wanted[i]
            chip.bind(box, reveal)
            // Changing what a window says takes effect on the next draw; moving it has to
            // go through the window manager and lands a frame later. Do both to a visible
            // window and it paints the new word at the old word's place for that frame,
            // which is a transcription flashing somewhere it has no business being. So a
            // window that has to move is hidden first and shown again only once the move
            // has actually been applied.
            if (place(chip, box.rect)) {
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
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Touchable, so a transcription can be tapped, but never focusable: the app
        // underneath keeps the keyboard, and every tap outside these small windows.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private companion object {
        const val MAX_CHIPS = 48
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

    fun bind(next: WordBox, state: RevealState) {
        val changed = box?.ipa != next.ipa || box?.word != next.word 
        box = next
        if (changed) invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
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
                    passThrough(true)
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && !dragging) {
            val b = box ?: return true
            // A tap opens the card, the way hovering opens the tooltip in the browser, and
            // puts the original word back underneath it so the reader sees both at once.
            reveal.toggle(b.word, REVEAL_MS)
            removeCallbacks(revert)
            postDelayed(revert, REVEAL_MS + 50)
            onChanged()
            onTapped(b)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            // Takeable again once the gesture is over.
            if (dragging) postDelayed({ passThrough(false); dragging = false }, 120)
        }
        return true
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
