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
    /** The scrolling list of symbols, refilled in place when a row opens or closes. */
    private var list: LinearLayout? = null
    private var scroller: ScrollView? = null
    private var expanded: String? = null
    private var shown: WordBox? = null

    /**
     * Views of ours that must not report themselves to accessibility.
     *
     * The card lives in an overlay window of the same process as the service, and a service
     * hears the events of every app including its own. The list scrolling therefore arrived
     * as a scroll of the screen, which takes the transcriptions down and the card with them:
     * the card could not be scrolled at all, because scrolling it was what closed it.
     */
    private val mute = object : View.AccessibilityDelegate() {
        override fun sendAccessibilityEvent(host: View, eventType: Int) {}
        override fun sendAccessibilityEventUnchecked(
            host: View,
            event: android.view.accessibility.AccessibilityEvent,
        ) {}
    }

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
        list = null
        scroller = null
        shown = null
        expanded = null
    }

    private fun render(box: WordBox) {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null

        val card = build(box)
        card.accessibilityDelegate = mute
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
            // Below the word to start with; where it really ends up is settled once the
            // card has been measured, since how tall it is depends on how many symbols the
            // word has.
            y = (box.rect.bottom + dp(10)).roundToInt()
        }
        card.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hide()
            false
        }
        runCatching { wm.addView(card, lp) }
            .onSuccess {
                view = card
                card.post {
                    place(card, lp, box)
                    // What the card actually laid out, once it has been measured and moved
                    // to where it will stay: an overlay window is invisible to uiautomator,
                    // so without this a test can only know that the card was asked for,
                    // never that it drew anything or where its buttons ended up. Reported
                    // after the move, because a position read before it is the position the
                    // card no longer has.
                    if (BuildConfig.DEBUG) card.post { describe(card) }
                }
            }
            .onFailure { android.util.Log.w("Phonetix", "tooltip addView failed", it) }
    }

    /**
     * Put the measured card where it fits: below the word, above it, or as far up as it can
     * go when it is taller than either gap.
     *
     * Guessing the height beforehand put a long card half off the bottom of the screen, with
     * the buttons that close it out of reach.
     */
    private fun place(card: View, lp: WindowManager.LayoutParams, box: WordBox) {
        val metrics = context.resources.displayMetrics
        val margin = dp(8).roundToInt()
        val height = card.height
        val below = (box.rect.bottom + dp(10)).roundToInt()
        val above = (box.rect.top - dp(10)).roundToInt() - height
        val y = when {
            below + height + margin <= metrics.heightPixels -> below
            above >= margin -> above
            else -> (metrics.heightPixels - height - margin).coerceAtLeast(margin)
        }
        if (y == lp.y) return
        lp.y = y
        runCatching { wm.updateViewLayout(card, lp) }
    }

    /** Every piece of text the card put on screen, with where it ended up. */
    private fun describe(root: View) {
        val out = StringBuilder("CARD ")
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        out.append("card@").append(at[0]).append(',').append(at[1]).append(',')
            .append(root.width).append(',').append(root.height).append(' ')
        scroller?.let { sv ->
            // How much of the list is out of sight, so a test knows whether there is
            // anything to scroll before it insists that scrolling moved something.
            val room = (sv.getChildAt(0)?.height ?: 0) - sv.height
            out.append("scrollable=").append(room.coerceAtLeast(0)).append(' ')
        }
        fun walk(v: View) {
            if (v is TextView && v.text.isNotEmpty()) {
                v.getLocationOnScreen(at)
                out.append('[').append(v.text.toString().replace(' ', '\u00b7')).append('@')
                    .append(at[0]).append(',').append(at[1]).append(',')
                    .append(v.width).append(',').append(v.height).append(']')
            }
            if (v is ImageView && v.drawable != null) {
                v.getLocationOnScreen(at)
                out.append("[image@").append(at[0]).append(',').append(at[1]).append(',')
                    .append(v.width).append(',').append(v.height).append(']')
            }
            if (v is LinearLayout) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            if (v is ScrollView) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        android.util.Log.d("Phonetix", out.toString())
    }

    private fun dp(v: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    )

    /** The card's own colours, taken from the surface the word was sitting on. */
    private class Palette(
        val surface: Int,
        val onSurface: Int,
        val muted: Int,
        val accent: Int,
        val line: Int,
    )

    private fun palette(box: WordBox): Palette {
        val dark = box.background == 0 || isDark(box.background)
        return Palette(
            surface = if (dark) Color.rgb(0x24, 0x1C, 0x17) else Color.rgb(0xFF, 0xFB, 0xF2),
            onSurface = if (dark) Color.rgb(0xF5, 0xED, 0xE0) else Color.rgb(0x2B, 0x21, 0x17),
            muted = if (dark) Color.rgb(0xC9, 0xB7, 0x9C) else Color.rgb(0x6B, 0x5B, 0x45),
            accent = if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0xB4, 0x53, 0x09),
            line = if (dark) Color.rgb(0x3A, 0x2E, 0x25) else Color.rgb(0xE3, 0xD7, 0xBE),
        )
    }

    private fun build(box: WordBox): View {
        val p = palette(box)
        val surface = p.surface
        val onSurface = p.onSurface
        val muted = p.muted
        val accent = p.accent
        val line = p.line

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

        // One row per symbol, and the row opens into the detail the extension shows. The
        // list grows to what it holds and stops at a fraction of the screen, so a word of
        // three sounds is a short card rather than a tall one with a gap in it, and a long
        // one scrolls instead of running off the bottom.
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        list = rows
        val scroll = BoundedScrollView(
            context, (context.resources.displayMetrics.heightPixels * 0.42f).roundToInt(),
        ).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            // How far the list has actually been scrolled. An overlay window is invisible to
            // uiautomator, so this is the only way a test can tell a card that scrolls under
            // the finger from one that does not.
            if (BuildConfig.DEBUG) {
                setOnScrollChangeListener { _, _, y, _, _ ->
                    android.util.Log.d("Phonetix", "CARDSCROLL y=$y")
                }
            }
            // The list is what scrolls, so it is the view whose events would otherwise be
            // heard by the service as the screen moving.
            accessibilityDelegate = mute
            addView(rows)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        scroller = scroll
        fill(box, p)
        root.addView(scroll)

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

    /**
     * Fill the list of symbols, keeping the card and where it is scrolled to.
     *
     * Opening a row used to build the whole card again and put up a new window for it, which
     * threw away the scroll position, fetched the diagram a second time, and moved the card
     * out from under the finger that had just tapped it.
     */
    private fun fill(box: WordBox, p: Palette) {
        val rows = list ?: return
        val at = scroller?.scrollY ?: 0
        rows.removeAllViews()
        for (s in IpaSymbols.explain(box.full)) {
            rows.addView(symbolRow(s, p.onSurface, p.muted, p.accent, p.surface, p.line))
        }
        scroller?.let { sv ->
            sv.post {
                val room = (sv.getChildAt(0)?.height ?: 0) - sv.height
                sv.scrollTo(0, at.coerceIn(0, room.coerceAtLeast(0)))
            }
        }
        if (BuildConfig.DEBUG) view?.post { view?.let { card -> card.post { describe(card) } } }
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
            shown?.let { box ->
                fill(box, palette(box))
                // The card is taller or shorter than it was, so where it sits is settled
                // again rather than left half off the screen.
                val card = view
                if (card != null) {
                    card.post {
                        val lp = card.layoutParams as? WindowManager.LayoutParams ?: return@post
                        place(card, lp, box)
                    }
                }
            }
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

/**
 * A scrolling list that takes the height it needs, up to a limit.
 *
 * A ScrollView given a fixed height leaves a gap under a short list; given a free one it
 * grows past the bottom of the screen and takes the card's own buttons with it.
 */
private class BoundedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
        )
    }
}
