package io.github.nanbiz.phonetix.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nanbiz.phonetix.core.IpaSymbols
import io.github.nanbiz.phonetix.core.WordBox
import kotlin.math.roundToInt

/**
 * The card that opens when a transcription is tapped: the word, its full transcription, and
 * what each symbol in it is called.
 *
 * This is the browser tooltip's job on a phone. There is no hovering here, so a tap does
 * what a hover does there, and the same rule applies: the text over a word is stripped to
 * what a reader wants mid-sentence, while this shows the whole transcription. The symbol
 * names come from the extension's own table, generated into the app rather than rewritten.
 */
class TooltipController(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show(box: WordBox) {
        hide()
        IpaSymbols.ensureLoaded(context)
        val card = build(box)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, so the app underneath keeps its keyboard and its state; the
            // outside touch only tells us to close, it still reaches the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = context.resources.displayMetrics
            width = (metrics.widthPixels * 0.86f).roundToInt()
            x = ((metrics.widthPixels - width) / 2f).roundToInt()
            // Below the word when there is room beneath it, above it when there is not, so
            // the card never covers the word it is explaining.
            val below = box.rect.bottom + dp(10)
            y = if (below + dp(190) < metrics.heightPixels) below.roundToInt()
            else (box.rect.top - dp(190)).coerceAtLeast(dp(24)).roundToInt()
        }
        card.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hide()
            false
        }
        runCatching { wm.addView(card, lp) }.onSuccess { view = card }
    }

    fun hide() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
    }

    private fun dp(v: Int): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
        )

    private fun build(box: WordBox): View {
        val dark = box.background == 0 || isDark(box.background)
        val surface = if (dark) Color.rgb(0x24, 0x1C, 0x17) else Color.rgb(0xFF, 0xFB, 0xF2)
        val onSurface = if (dark) Color.rgb(0xF5, 0xED, 0xE0) else Color.rgb(0x2B, 0x21, 0x17)
        val muted = if (dark) Color.rgb(0xC9, 0xB7, 0x9C) else Color.rgb(0x6B, 0x5B, 0x45)
        val accent = if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0xB4, 0x53, 0x09)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(18)
                setStroke(dp(1).roundToInt(), if (dark) Color.rgb(0x3A, 0x2E, 0x25) else Color.rgb(0xE3, 0xD7, 0xBE))
            }
            setPadding(dp(18).roundToInt(), dp(16).roundToInt(), dp(18).roundToInt(), dp(16).roundToInt())
            elevation = dp(8)
        }

        root.addView(TextView(context).apply {
            text = box.word
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        root.addView(TextView(context).apply {
            text = box.full
            setTextColor(accent)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        })

        val symbols = IpaSymbols.explain(box.full)
        if (symbols.isEmpty()) {
            root.addView(TextView(context).apply {
                text = "No symbol descriptions for this transcription."
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(10).roundToInt(), 0, 0)
            })
            return root
        }

        for (s in symbols) {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7).roundToInt(), 0, 0)
                addView(TextView(context).apply {
                    text = s.token
                    setTextColor(accent)
                    typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    width = dp(38).roundToInt()
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = s.name
                        setTextColor(onSurface)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    })
                    if (s.example.isNotEmpty()) {
                        addView(TextView(context).apply {
                            text = s.example
                            setTextColor(muted)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        })
                    }
                })
            })
        }
        return root
    }

    private fun isDark(c: Int): Boolean =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) < 140f
}
