package io.github.nanbiz.phonetix.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.nanbiz.phonetix.core.ChipStyle
import io.github.nanbiz.phonetix.core.WordBox

/**
 * The window that paints transcriptions over whatever app is in front.
 *
 * It is a single full-screen view rather than one window per word: the words change on
 * every scroll, and adding and removing dozens of windows a second would cost far more
 * than redrawing one canvas. The window never takes touches, so everything underneath
 * still behaves exactly as it would without it.
 */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: OverlayView? = null

    fun render(boxes: List<WordBox>, style: ChipStyle) {
        if (boxes.isEmpty() && view == null) return
        ensureView().setBoxes(boxes, style)
    }

    fun clear() {
        view?.setBoxes(emptyList(), ChipStyle.SOLID)
    }

    fun destroy() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    @SuppressLint("InflateParams")
    private fun ensureView(): OverlayView {
        view?.let { return it }
        val v = OverlayView(context)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Never focusable, never touchable: the overlay is decoration, and every tap,
            // swipe and keystroke belongs to the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { wm.addView(v, lp) }
            .onFailure { android.util.Log.e("Phonetix", "overlay addView failed", it); return v }
        view = v
        return v
    }
}

/** Draws the chips. Screen coordinates in, pixels out. */
class OverlayView(context: Context) : View(context) {

    private var boxes: List<WordBox> = emptyList()
    private var style: ChipStyle = ChipStyle.SOLID
    private val loc = IntArray(2)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }

    fun setBoxes(next: List<WordBox>, nextStyle: ChipStyle) {
        boxes = next
        style = nextStyle
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) return
        // The window is laid out without limits, so ask where it actually sits and undo
        // that offset: the rectangles arrive in screen coordinates.
        getLocationOnScreen(loc)
        val dx = -loc[0].toFloat()
        val dy = -loc[1].toFloat()

        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        val chip = when (style) {
            ChipStyle.SOLID -> if (dark) Color.rgb(0x1B, 0x14, 0x10) else Color.rgb(0xF7, 0xEF, 0xDD)
            ChipStyle.SOFT -> if (dark) Color.argb(0xD0, 0x1B, 0x14, 0x10) else Color.argb(0xD8, 0xF7, 0xEF, 0xDD)
            ChipStyle.UNDERLAY -> Color.TRANSPARENT
        }
        val ink = when (style) {
            ChipStyle.UNDERLAY -> if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0xB4, 0x53, 0x09)
            else -> if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0x2B, 0x21, 0x17)
        }

        val out = RectF()
        for (b in boxes) {
            val h = b.rect.height()
            if (h <= 0f) continue
            // Size the transcription to the word's own line, then shrink it if the IPA
            // runs wider than the word so it never spills onto the neighbours.
            var size = h * 0.78f
            text.textSize = size
            val want = text.measureText(b.ipa)
            val room = b.rect.width() + h * 0.6f
            if (want > room && want > 0f) {
                size *= room / want
                text.textSize = size
            }

            val pad = h * 0.12f
            val w = text.measureText(b.ipa)
            val cx = b.rect.centerX() + dx
            out.set(cx - w / 2f - pad, b.rect.top + dy - pad * 0.4f,
                    cx + w / 2f + pad, b.rect.bottom + dy + pad * 0.4f)

            if (chip != Color.TRANSPARENT) {
                bg.color = chip
                canvas.drawRoundRect(out, h * 0.22f, h * 0.22f, bg)
            } else {
                // Underlay: no cover, just a soft rule under the word so the original
                // stays readable and the transcription rides beneath it.
                bg.color = if (dark) Color.argb(0x55, 0xFB, 0xBF, 0x24) else Color.argb(0x44, 0xB4, 0x53, 0x09)
                canvas.drawRoundRect(out, h * 0.22f, h * 0.22f, bg)
            }

            text.color = ink
            val fm = text.fontMetrics
            val baseline = out.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(b.ipa, cx, baseline, text)
        }
    }
}
