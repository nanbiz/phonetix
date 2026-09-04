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
import io.github.nanbiz.phonetix.core.ChipStyle
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
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val chips = ArrayList<ChipView>(MAX_CHIPS)

    /**
     * Which word is currently showing itself instead of its transcription.
     *
     * Held here, against the word, rather than in the view that was tapped: the screen is
     * re-read constantly, and each read hands the pooled windows a fresh list that may be
     * in a different order, so a flag living in one view would be wiped by the next pass a
     * moment after the tap.
     */
    private val reveal = RevealState()

    fun hideNow() {
        for (c in chips) if (c.visibility != View.GONE) c.visibility = View.GONE
    }

    fun render(boxes: List<WordBox>, style: ChipStyle) {
        val wanted = if (boxes.size > MAX_CHIPS) boxes.subList(0, MAX_CHIPS) else boxes
        while (chips.size < wanted.size) if (!addChip()) break
        for (i in wanted.indices) {
            val chip = chips.getOrNull(i) ?: break
            chip.bind(wanted[i], style, reveal)
            place(chip, wanted[i].rect)
            if (chip.visibility != View.VISIBLE) chip.visibility = View.VISIBLE
        }
        for (i in wanted.size until chips.size) {
            if (chips[i].visibility != View.GONE) chips[i].visibility = View.GONE
        }
    }

    fun clear() = hideNow()

    /** Repaint every chip, so revealing one word also un-reveals the last one. */
    private fun refresh() {
        for (c in chips) if (c.visibility == View.VISIBLE) c.invalidate()
    }

    fun destroy() {
        for (c in chips) runCatching { wm.removeView(c) }
        chips.clear()
    }

    private fun addChip(): Boolean {
        val v = ChipView(context, reveal) { refresh() }
        v.visibility = View.GONE
        val ok = runCatching { wm.addView(v, params(0, 0, 1, 1)) }.isSuccess
        if (!ok) return false
        chips.add(v)
        return true
    }

    private fun place(chip: ChipView, rect: RectF) {
        // A hair of bleed on each side so an antialiased edge of the original word cannot
        // peek out from under its replacement, while the width still matches the word.
        val x = (rect.left - BLEED).roundToInt()
        val y = (rect.top - BLEED).roundToInt()
        val w = (rect.width() + BLEED * 2).roundToInt().coerceAtLeast(1)
        val h = (rect.height() + BLEED * 2).roundToInt().coerceAtLeast(1)
        val lp = chip.layoutParams as? WindowManager.LayoutParams ?: return
        if (lp.x == x && lp.y == y && lp.width == w && lp.height == h) return
        lp.x = x; lp.y = y; lp.width = w; lp.height = h
        runCatching { wm.updateViewLayout(chip, lp) }
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
    private val onChanged: () -> Unit,
) : View(context) {

    private var box: WordBox? = null
    private var style: ChipStyle = ChipStyle.SOLID
    private val revert = Runnable { onChanged() }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }

    fun bind(next: WordBox, nextStyle: ChipStyle, state: RevealState) {
        val changed = box?.ipa != next.ipa || box?.word != next.word || style != nextStyle
        box = next
        style = nextStyle
        if (changed) invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val w = box?.word ?: return true
            reveal.toggle(w, REVEAL_MS)
            removeCallbacks(revert)
            postDelayed(revert, REVEAL_MS + 50)
            onChanged()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = box ?: return
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        // The word's own colours, read off the screen, are what make a replacement look
        // like the text it stands in for; the palette below is only for when they could not
        // be read - a capture that has not landed yet, or a word with too little contrast to
        // tell ink from surface.
        val sampled = b.background != 0 && b.ink != 0
        val chip = when {
            sampled -> b.background
            style == ChipStyle.SOFT -> if (dark) Color.argb(0xE6, 0x1B, 0x14, 0x10) else Color.argb(0xE6, 0xF7, 0xEF, 0xDD)
            else -> if (dark) Color.rgb(0x1B, 0x14, 0x10) else Color.rgb(0xF7, 0xEF, 0xDD)
        }
        val revealed = reveal.isRevealed(b.word)
        val fg = when {
            // Revealed, the original word is put back exactly as the app drew it.
            revealed && sampled -> b.ink
            revealed -> if (dark) Color.rgb(0xF5, 0xED, 0xE0) else Color.rgb(0x2B, 0x21, 0x17)
            // A transcription is tinted off the real ink so it reads as ours, while still
            // sitting in the app's own colour scheme rather than fighting it.
            sampled && style == ChipStyle.UNDERLAY -> b.ink
            sampled -> accent(b.ink, b.background)
            else -> if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0xB4, 0x53, 0x09)
        }

        // The whole window is painted, corner to corner: the window is the word's own box,
        // so covering it completely is what keeps the original from showing through.
        bg.color = chip
        val r = height * 0.18f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bg)

        android.util.Log.d("Phonetix", "draw word=${b.word} revealed=$revealed state=${reveal.word}/${reveal.until} now=${android.os.SystemClock.uptimeMillis()}")
        val label = if (revealed) b.word else b.ipa
        if (label.isEmpty()) return

        // The width is fixed - it is the space the original word occupied - so the type is
        // what gives. Size it to the line, then shrink until it fits that width, so a
        // replacement never pushes into the words on either side.
        var size = height * 0.80f
        ink.textSize = size
        val room = width - height * 0.12f
        val measured = ink.measureText(label)
        if (measured > room && measured > 0f) {
            size *= room / measured
            ink.textSize = size
        }

        ink.color = fg
        val fm = ink.fontMetrics
        canvas.drawText(label, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, ink)
    }

    /**
     * The transcription's colour: the word's own ink, pulled a little toward the
     * accent so it is recognisably a transcription, but never so far that it stops
     * contrasting with the surface it sits on.
     */
    private fun accent(ink: Int, background: Int): Int {
        val warm = if (isLight(background)) Color.rgb(0xB4, 0x53, 0x09) else Color.rgb(0xFB, 0xBF, 0x24)
        return Color.rgb(
            (Color.red(ink) + Color.red(warm)) / 2,
            (Color.green(ink) + Color.green(warm)) / 2,
            (Color.blue(ink) + Color.blue(warm)) / 2,
        )
    }

    private fun isLight(c: Int): Boolean =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) > 140f

    private companion object {
        const val REVEAL_MS = 2500L
    }
}
