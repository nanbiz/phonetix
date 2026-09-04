package io.github.nanbiz.phonetix.service

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.nanbiz.phonetix.BuildConfig
import io.github.nanbiz.phonetix.core.IpaSymbols
import io.github.nanbiz.phonetix.core.SymbolInfo
import io.github.nanbiz.phonetix.core.WordBox
import io.github.nanbiz.phonetix.core.wikimediaFileUrl
import io.github.nanbiz.phonetix.core.wikimediaThumbUrl
import java.net.URL
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * The card a tap opens: everything the extension's tooltip offers about a word.
 *
 * The word and its whole transcription, the word spoken aloud, and every symbol in it named,
 * exemplified, sounded and drawn. There is no hovering on a phone, so a tap does what a
 * hover does in the browser. The names, articles, recordings and diagrams all come from the
 * extension's own table, generated into the app rather than written again, so the two say
 * the same things about a sound.
 */
class TooltipController(
    private val context: Context,
    private val speaker: Speaker,
    private val io: Executor,
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var expanded: String? = null
    private var shown: WordBox? = null

    fun show(box: WordBox) {
        IpaSymbols.ensureLoaded(context)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "TOOLTIP open word=${box.word} ipa=${box.full} " +
                    "symbols=${IpaSymbols.explain(box.full).size}",
            )
        }
        expanded = null
        shown = box
        render(box)
    }

    fun hide() {
        if (BuildConfig.DEBUG && view != null) {
            android.util.Log.d("Phonetix", "TOOLTIP closed")
        }
        speaker.stop()
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        shown = null
        expanded = null
    }

    private fun render(box: WordBox) {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null

        val card = build(box)
        val metrics = context.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, so the app underneath keeps its keyboard and its state; the
            // outside touch only tells the card to close.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            width = (metrics.widthPixels * 0.88f).roundToInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = ((metrics.widthPixels - width) / 2f).roundToInt()
            // Below the word when there is room, above it when there is not, so the card
            // never covers the word it is explaining.
            val room = metrics.heightPixels - box.rect.bottom
            y = if (room > metrics.heightPixels * 0.45f) (box.rect.bottom + dp(10)).roundToInt()
            else (box.rect.top - metrics.heightPixels * 0.42f).coerceAtLeast(dp(28)).roundToInt()
        }
        card.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hide()
            false
        }
        runCatching { wm.addView(card, lp) }
            .onSuccess { view = card }
            .onFailure { android.util.Log.w("Phonetix", "tooltip addView failed", it) }
    }

    private fun dp(v: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    )

    private fun build(box: WordBox): View {
        val dark = box.background == 0 || isDark(box.background)
        val surface = if (dark) Color.rgb(0x24, 0x1C, 0x17) else Color.rgb(0xFF, 0xFB, 0xF2)
        val onSurface = if (dark) Color.rgb(0xF5, 0xED, 0xE0) else Color.rgb(0x2B, 0x21, 0x17)
        val muted = if (dark) Color.rgb(0xC9, 0xB7, 0x9C) else Color.rgb(0x6B, 0x5B, 0x45)
        val accent = if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0xB4, 0x53, 0x09)
        val line = if (dark) Color.rgb(0x3A, 0x2E, 0x25) else Color.rgb(0xE3, 0xD7, 0xBE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(18)
                setStroke(dp(1).roundToInt(), line)
            }
            setPadding(dp(18).roundToInt(), dp(14).roundToInt(), dp(18).roundToInt(), dp(14).roundToInt())
            elevation = dp(10)
        }

        // Header: the word, and the phone saying it.
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = box.word
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                })
                addView(TextView(context).apply {
                    text = box.full
                    setTextColor(accent)
                    typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                })
            })
            if (speaker.canSpeak) {
                addView(pill("Say it", accent, surface, line) { speaker.say(box.word) })
            }
        })

        root.addView(divider(line))

        // One row per symbol, and the row opens into the detail the extension shows.
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (s in IpaSymbols.explain(box.full)) {
            list.addView(symbolRow(s, onSurface, muted, accent, surface, line))
        }
        root.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.34f).roundToInt(),
            )
        })

        root.addView(divider(line))
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Wiktionary", accent, surface, line) {
                open("https://en.wiktionary.org/wiki/${Uri.encode(box.word.lowercase())}")
            })
            addView(pill("Close", muted, surface, line) { hide() })
        })
        return root
    }

    private fun symbolRow(
        s: SymbolInfo,
        onSurface: Int,
        muted: Int,
        accent: Int,
        surface: Int,
        line: Int,
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8).roundToInt(), 0, dp(2).roundToInt())
        }
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply {
                text = s.token
                setTextColor(accent)
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                width = dp(38).roundToInt()
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
            if (s.audio != null) {
                addView(pill("♪", accent, surface, line) { speaker.play(wikimediaFileUrl(s.audio)) })
            }
        })

        // Tapping a symbol opens what the extension shows when you hover one: how the sound
        // is made, and where to read more.
        row.setOnClickListener {
            expanded = if (expanded == s.token) null else s.token
            shown?.let { render(it) }
        }

        if (expanded == s.token) {
            if (s.diagram != null) {
                val image = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(150).roundToInt(),
                    )
                    adjustViewBounds = true
                    setPadding(0, dp(6).roundToInt(), 0, 0)
                }
                row.addView(image)
                loadDiagram(s.diagram, image)
            }
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6).roundToInt(), 0, 0)
                if (s.wiki != null) {
                    addView(pill("Read about it", accent, surface, line) {
                        open("https://en.wikipedia.org/wiki/${s.wiki}")
                    })
                }
                if (s.seeing != null) {
                    // The same sound filmed in a real mouth, which is the one thing a
                    // sagittal drawing cannot show.
                    addView(pill("See it said", accent, surface, line) {
                        open("https://www.seeingspeech.ac.uk/ipa-charts/${s.seeing}")
                    })
                }
            })
            // Each word of the description is a fact of its own, and each has an article.
            val terms = s.name.split(' ', ',').mapNotNull { w ->
                val clean = w.trim(',', '(', ')')
                IpaSymbols.termArticle(clean)?.let { clean to it }
            }.distinctBy { it.first }
            if (terms.isNotEmpty()) {
                row.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4).roundToInt(), 0, 0)
                    for ((term, article) in terms.take(4)) {
                        addView(pill(term, muted, surface, line) {
                            open("https://en.wikipedia.org/wiki/$article")
                        })
                    }
                })
            }
        }
        return row
    }

    /** Sagittal sections are SVGs, so Wikimedia is asked to raster one at the right width. */
    private fun loadDiagram(file: String, into: ImageView) {
        val width = (context.resources.displayMetrics.widthPixels * 0.7f).roundToInt()
        io.execute {
            val bmp = runCatching {
                URL(wikimediaThumbUrl(file, width)).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) main.post { into.setImageBitmap(bmp) }
        }
    }

    private fun pill(label: String, fg: Int, surface: Int, line: Int, onTap: () -> Unit): View =
        TextView(context).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12).roundToInt(), dp(6).roundToInt(), dp(12).roundToInt(), dp(6).roundToInt())
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(14)
                setStroke(dp(1).roundToInt(), line)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8).roundToInt()
            layoutParams = lp
            setOnClickListener { onTap() }
        }

    private fun divider(color: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1).roundToInt(),
        ).apply { topMargin = dp(10).roundToInt(); bottomMargin = dp(4).roundToInt() }
        setBackgroundColor(color)
    }

    private fun open(url: String) {
        hide()
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun isDark(c: Int): Boolean =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) < 140f
}
