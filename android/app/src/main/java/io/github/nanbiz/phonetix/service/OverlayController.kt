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

    fun hideNow() {
        for (c in chips) if (c.visibility != View.GONE) c.visibility = View.GONE
    }

    /**
     * Carry the transcriptions along with the content that moved under them.
     *
     * A scroll event reports how far the view travelled, which is exactly how far every
     * word on it travelled, so the windows can be moved to the right place immediately
     * instead of waiting for the screen to be read again. The next read corrects any drift;
     * this is what keeps a transcription on its word while the finger is still moving.
     */
    fun shiftBy(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        for (c in chips) {
            if (c.visibility != View.VISIBLE) continue
            val lp = c.layoutParams as? WindowManager.LayoutParams ?: continue
            lp.x += dx
            lp.y += dy
            runCatching { wm.updateViewLayout(c, lp) }
        }
    }

    fun render(boxes: List<WordBox>, style: ChipStyle) {
        val wanted = if (boxes.size > MAX_CHIPS) boxes.subList(0, MAX_CHIPS) else boxes
        while (chips.size < wanted.size) if (!addChip()) break
        for (i in wanted.indices) {
            val chip = chips.getOrNull(i) ?: break
            chip.bind(wanted[i], style)
            place(chip, wanted[i].rect)
            if (chip.visibility != View.VISIBLE) chip.visibility = View.VISIBLE
        }
        for (i in wanted.size until chips.size) {
            if (chips[i].visibility != View.GONE) chips[i].visibility = View.GONE
        }
    }

    fun clear() = hideNow()

    fun destroy() {
        for (c in chips) runCatching { wm.removeView(c) }
        chips.clear()
    }

    private fun addChip(): Boolean {
        val v = ChipView(context)
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
class ChipView(context: Context) : View(context) {

    private var box: WordBox? = null
    private var style: ChipStyle = ChipStyle.SOLID
    private var revealed = false
    private val revert = Runnable { revealed = false; invalidate() }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }

    fun bind(next: WordBox, nextStyle: ChipStyle) {
        val changed = box?.ipa != next.ipa || style != nextStyle
        box = next
        style = nextStyle
        if (changed) {
            revealed = false
            removeCallbacks(revert)
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            revealed = !revealed
            removeCallbacks(revert)
            if (revealed) postDelayed(revert, REVEAL_MS)
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = box ?: return
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val chip = when (style) {
            ChipStyle.SOLID -> if (dark) Color.rgb(0x1B, 0x14, 0x10) else Color.rgb(0xF7, 0xEF, 0xDD)
            ChipStyle.SOFT -> if (dark) Color.argb(0xE6, 0x1B, 0x14, 0x10) else Color.argb(0xE6, 0xF7, 0xEF, 0xDD)
            ChipStyle.UNDERLAY -> if (dark) Color.rgb(0x1B, 0x14, 0x10) else Color.rgb(0xF7, 0xEF, 0xDD)
        }
        val fg = when {
            revealed -> if (dark) Color.rgb(0xF5, 0xED, 0xE0) else Color.rgb(0x2B, 0x21, 0x17)
            else -> if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0xB4, 0x53, 0x09)
        }

        // The whole window is painted, corner to corner: the window is the word's own box,
        // so covering it completely is what keeps the original from showing through.
        bg.color = chip
        val r = height * 0.18f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bg)

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

    private companion object {
        const val REVEAL_MS = 2500L
    }
}
