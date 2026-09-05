package io.github.nanbiz.phonetix.debug

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The surfaces the overlay is tested against, and the ground truth for testing it.
 *
 * Every fault this overlay has had came from movement or from what surrounds a word, and
 * neither shows on a still screen. So this puts up a page whose scroll position is known to
 * the pixel at every instant - it reports it as it changes - which is what lets a test say
 * where a transcription should have been at the moment it was drawn, rather than only where
 * it ended up.
 *
 * The modes are the situations that broke it before: a plain page, a page with a bar the
 * text scrolls under, lines in colours the test knows, and a window the system refuses to
 * let anyone capture.
 *
 *   adb shell am start -n io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity \
 *       --es mode header --ei scrollTo 240
 *   adb shell am start -n io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity --ei fling -6000
 */
class DebugSurfaceActivity : Activity() {

    private lateinit var scroller: ScrollView
    /** An empty line whose text is toggled to make the window's content change. */
    private var marker: TextView? = null
    private var mode = "plain"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent?.getStringExtra(EXTRA_MODE) ?: "plain"
        // The tests need the overlay actually switched on, which is otherwise a decision
        // only the reader makes in the app.
        io.github.nanbiz.phonetix.core.SettingsStore.init(this)
        if (mode == "secure") {
            // A window nobody may capture: the sampler cannot read it, and the test checks
            // that this degrades rather than breaks.
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(BACKGROUND)

        scroller = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(content())
            // The page says where it is, every time it moves. This is the ground truth the
            // drift test measures against: without it a test can only compare endpoints,
            // and a transcription that lags through the whole of a fling and catches up at
            // the end would pass.
            viewTreeObserver.addOnScrollChangedListener {
                Log.d(TAG, "SCROLLY ${SystemClock.uptimeMillis()} $scrollY")
            }
        }
        root.addView(scroller, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        if (mode == "header") {
            // A bar the text scrolls under: it neither clips the list nor makes a word
            // invisible to accessibility, so a word behind it is the case that only paint
            // order can catch.
            root.addView(TextView(this).apply {
                // No real words: anything transcribable here is legitimately drawn inside the
                // bar, and the occlusion check could not tell that from a fault.
                text = "\u25B2 \u25B2 \u25B2"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(0x20, 0x20, 0x20))
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, pad(), 0, pad())
                tag = HEADER_TAG
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, headerHeight(),
            ))
        }

        setContentView(root)
        window.decorView.setBackgroundColor(BACKGROUND)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The layout is built from the mode in onCreate, so arriving with a different mode
        // has to build it again. Without this the activity quietly keeps the previous mode
        // and a test for the header runs against a page that has none - which is how the
        // occlusion check came back failing against a bar that was never on screen.
        val wanted = intent?.getStringExtra(EXTRA_MODE) ?: "plain"
        if (wanted != mode) {
            recreate()
            return
        }
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        // Applied here rather than only in onCreate: a relaunch with the same mode does not
        // create the activity again, so settings passed that way were silently dropped and
        // a test that changed the frequency measured the previous one.
        applySettings(intent)
        // Changing a setting alters nothing on screen, so nothing tells the service to look
        // again - and a test that changes the frequency and reads the result sees whatever
        // was there before, or nothing at all. A one-pixel nudge is a real content change
        // and makes the next read describe the new setting.
        announce()
        nudge()
        if (intent.hasExtra(EXTRA_SCROLL)) {
            val y = intent.getIntExtra(EXTRA_SCROLL, 0)
            // Jumped, not animated: the test wants the movement finished.
            scroller.post { scroller.scrollTo(0, y); Log.d(TAG, "SETTLED $y") }
        }
        if (intent.hasExtra(EXTRA_FLING)) {
            val v = intent.getIntExtra(EXTRA_FLING, 0)
            // A real fling, with the platform's own deceleration, which is the motion a
            // finger actually produces and nothing like a straight line.
            scroller.post { scroller.fling(v) }
        }
        if (intent.hasExtra(EXTRA_SMOOTH)) {
            val by = intent.getIntExtra(EXTRA_SMOOTH, 0)
            scroller.post { scroller.smoothScrollBy(0, by) }
        }
    }

    private fun applySettings(i: Intent) {
        // Announced after the fact, so a test can wait for a reading taken after the setting
        // landed rather than one taken just before it - which is a race it loses about half
        // the time, and reports as the setting doing nothing.

            if (i.hasExtra(EXTRA_ENABLE)) {
                io.github.nanbiz.phonetix.core.SettingsStore.setEnabled(i.getIntExtra(EXTRA_ENABLE, 1) != 0)
            }
            if (i.hasExtra(EXTRA_DENSITY)) {
                io.github.nanbiz.phonetix.core.SettingsStore.setDensity(i.getIntExtra(EXTRA_DENSITY, 12))
            }
            // Which apps the overlay is allowed on, so the per-app scope is testable
            // without driving the settings screen by hand.
            if (i.hasExtra(EXTRA_SCOPE)) {
                val all = i.getIntExtra(EXTRA_SCOPE, 1) != 0
                io.github.nanbiz.phonetix.core.SettingsStore.setAllApps(all)
                if (!all) {
                    // Nothing chosen: the overlay should appear nowhere.
                    for (p in io.github.nanbiz.phonetix.core.SettingsStore.current.apps.toList()) {
                        io.github.nanbiz.phonetix.core.SettingsStore.toggleApp(p)
                    }
                }
            }
            }

    private fun announce() {
        val s = io.github.nanbiz.phonetix.core.SettingsStore.current
        Log.d(TAG, "SETTINGS ${SystemClock.uptimeMillis()} enabled=${s.enabled} " +
            "density=${s.density} allApps=${s.allApps}")
    }

    /**
     * The smallest change that still counts as the window's *content* changing.
     *
     * Not a scroll. A scroll puts the overlay on its fast path, which reuses the words it
     * chose last time - so a test that changed the frequency and nudged by scrolling read
     * back the old frequency's words and concluded the setting did nothing.
     */
    private fun nudge() {
        scroller.postDelayed({
            val text = marker ?: return@postDelayed
            text.text = if (text.text.isEmpty()) "\u200b" else ""
        }, 60)
    }

    private fun content(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), if (mode == "header") headerHeight() else pad(), pad(), pad())
        if (mode == "colors") {
            // Three lines whose colours the test knows, to catch a sampler that averages
            // a whole node - or a whole screen - into one wrong colour.
            addView(line("Pronunciation arrives quietly", Color.WHITE, BACKGROUND))
            addView(line("Dictionary answers immediately", Color.rgb(0xFB, 0xBF, 0x24), BACKGROUND))
            addView(line("Paragraph teaches unfamiliar language", Color.rgb(0x66, 0xBB, 0x66), BACKGROUND))
            repeat(10) { addView(line("$it ${PARAGRAPH}", Color.WHITE, BACKGROUND)) }
        } else {
            repeat(14) { i -> addView(line("$i. $PARAGRAPH", Color.WHITE, BACKGROUND)) }
        }
        marker = TextView(this@DebugSurfaceActivity).also { addView(it) }
    }

    private fun line(text: String, ink: Int, bg: Int) = TextView(this).apply {
        this.text = text
        setTextColor(ink)
        setBackgroundColor(bg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(0, pad(), 0, pad())
        // What this line is really drawn in and where it ended up, so a test comparing the
        // overlay's colours against the app's own owes nothing to the overlay's account of
        // itself, and can tell one line's words from the next line's identical ones.
        post {
            val at = IntArray(2)
            getLocationOnScreen(at)
            android.util.Log.d(
                TAG,
                "SURFACE ink=#%06X bg=#%06X at=%d,%d,%d,%d text=%s".format(
                    ink and 0xFFFFFF, bg and 0xFFFFFF,
                    at[0], at[1], width, height, text,
                ),
            )
        }
    }

    private fun pad() = (16 * resources.displayMetrics.density).toInt()
    private fun headerHeight() = (72 * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "PhonetixTest"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SCROLL = "scrollTo"
        const val EXTRA_FLING = "fling"
        const val EXTRA_SMOOTH = "smoothBy"
        const val EXTRA_ENABLE = "enable"
        const val EXTRA_DENSITY = "density"
        const val EXTRA_SCOPE = "allApps"
        const val HEADER_TAG = "header"
        // Flat black and white on purpose: what the sampler should have read is then a
        // fact rather than an opinion.
        const val BACKGROUND = Color.BLACK
        const val PARAGRAPH =
            "Reading a paragraph teaches pronunciation quietly because every unfamiliar " +
                "word arrives already spoken and the dictionary answers immediately."
    }
}
