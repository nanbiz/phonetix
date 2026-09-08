package io.github.nanbiz.phonetix.debug

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
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
class DebugSurfaceActivity : androidx.activity.ComponentActivity() {

    private lateinit var scroller: ScrollView
    /** The recycling list, when that is the page being shown. */
    private var listView: ListView? = null
    private var recycler: androidx.recyclerview.widget.RecyclerView? = null

    /** How far the list an ordinary app is built from has been scrolled, since it keeps no
     *  such number of its own. */
    private var recyclerAt = 0
    /** The Compose list, once it has composed itself and said how to drive it. */
    private var composePage: ScrollMotion.Page? = null
    private var composeScope: kotlinx.coroutines.CoroutineScope? = null

    /** Where a list is scrolled to, in pixels, which it does not keep as a single number. */
    private fun listScroll(): Int {
        val list = listView ?: return 0
        val first = list.getChildAt(0) ?: return 0
        return list.firstVisiblePosition * first.height - first.top
    }

    /** The page under test, whichever kind it is. */
    private fun page(): ScrollMotion.Page {
        composePage?.let { return it }
        // A Compose page exists only once it has composed itself. Until then there is
        // nothing to drive, and saying so is better than driving the wrong thing.
        if (mode == "lazy" || mode == "chat") {
            val nothing = window.decorView
            return ScrollMotion.Page(nothing, { 0 }, { })
        }
        val list = listView
        if (list != null) {
            return ScrollMotion.Page(
                view = list,
                at = { listScroll() },
                moveTo = { y -> list.scrollListBy(y - listScroll()) },
            )
        }
        // A RecyclerView is neither of the two, and reaching past it for the scroll view a
        // recycling page does not have threw where the suite could not see it: the page died
        // on the intent that starts a movement, so every scroll check on a list judged a
        // screen with no app on it and reported that there was nothing to judge.
        val rows = recycler
        if (rows != null) {
            return ScrollMotion.Page(
                view = rows,
                at = { recyclerAt },
                moveTo = { y -> rows.scrollBy(0, y - recyclerAt) },
            )
        }
        return ScrollMotion.Page(
            view = scroller,
            at = { scroller.scrollY },
            moveTo = { y -> scroller.scrollTo(0, y) },
        )
    }
    /** An empty line whose text is toggled to make the window's content change. */
    private var marker: TextView? = null

    /** The line that is being written into, on the page that streams. */
    private var growing: TextView? = null
    private var written = 0
    private var living = false
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

        if (mode == "lazy" || mode == "chat") {
            // The kind of list a Compose app scrolls, which describes itself through
            // semantics rather than as a tree of views.
            val scope = kotlinx.coroutines.MainScope()
            composeScope = scope
            root.addView(
                // "chat" is the same list with a message in each row rather than a line,
                // which is the shape of the app a reader watches.
                LazyListPage.build(
                    this,
                    scope,
                    asMessages = mode == "chat",
                    growEvery = intent.getIntExtra("growEvery", 0),
                ) { p ->
                    composePage = p
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            setContentView(root)
            window.decorView.setBackgroundColor(BACKGROUND)
            handle(intent)
            return
        }

        if (mode == "recycler") {
            // The list an ordinary app is built from. A ListView reports its scrolling as row
            // indices and a Compose list as a pseudo-offset of its own; this one is the case
            // that has not been asked yet.
            val rows = intent.getIntExtra("rows", 60)
            val recycler = androidx.recyclerview.widget.RecyclerView(this).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@DebugSurfaceActivity)
                adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<Holder>() {
                    override fun onCreateViewHolder(parent: android.view.ViewGroup, kind: Int) =
                        Holder(line("", Color.WHITE, BACKGROUND))

                    override fun getItemCount() = rows

                    override fun onBindViewHolder(holder: Holder, position: Int) {
                        holder.line.text =
                            "$position ${TestWords.DISTINCT[position % TestWords.DISTINCT.size]}"
                    }
                }
                setBackgroundColor(BACKGROUND)
                // The same report the other pages give, so the suite reads one kind of ground
                // truth whichever page it is driving.
                addOnScrollListener(object :
                    androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                    override fun onScrolled(
                        view: androidx.recyclerview.widget.RecyclerView,
                        dx: Int,
                        dy: Int,
                    ) {
                        recyclerAt += dy
                        placedNow()
                        Log.d(TAG, "SCROLLY ${SystemClock.uptimeMillis()} $recyclerAt")
                    }
                })
            }
            this.recycler = recycler
            root.addView(recycler, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            setContentView(root)
            window.decorView.setBackgroundColor(BACKGROUND)
            handle(intent)
            return
        }
        if (mode == "list") {
            // A list that recycles its rows, which a ScrollView never does. Everything an app
            // in front of the reader actually scrolls works this way: the same view, and the
            // same accessibility node, is handed to a different line as the old one leaves
            // the screen. A page that keeps all its lines cannot show what that does to an
            // overlay carrying words by how far their line reports it has moved.
            val list = ListView(this).apply {
                setBackgroundColor(BACKGROUND)
                divider = null
                adapter = object : BaseAdapter() {
                    override fun getCount() = TestWords.DISTINCT.size * 3
                    override fun getItem(position: Int) =
                        TestWords.DISTINCT[position % TestWords.DISTINCT.size]
                    override fun getItemId(position: Int) = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                        val row = (convertView as? TextView) ?: line("", Color.WHITE, BACKGROUND)
                        // Numbered, so a row is never the same text as another row: two lines
                        // that read alike could be told apart by nothing but position, which
                        // is the thing being measured.
                        row.text = "$position ${getItem(position)}"
                        return row
                    }
                }
                // The same reports a ScrollView gives, so the suite reads one kind of ground
                // truth whichever page it is driving.
                viewTreeObserver.addOnScrollChangedListener {
                    Log.d(TAG, "SCROLLY ${SystemClock.uptimeMillis()} ${listScroll()}")
                }
            }
            listView = list
            root.addView(list, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            setContentView(root)
            window.decorView.setBackgroundColor(BACKGROUND)
            handle(intent)
            return
        }

        scroller = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(content())
            // The page says where it is, every time it moves. This is the ground truth the
            // drift test measures against: without it a test can only compare endpoints,
            // and a transcription that lags through the whole of a fling and catches up at
            // the end would pass.
            viewTreeObserver.addOnScrollChangedListener {
                Log.d(
                    TAG,
                    "SCROLLY ${SystemClock.uptimeMillis()} $scrollY " +
                        "view=${System.identityHashCode(this)}",
                )
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The layout is built from the mode in onCreate, so arriving with a different mode
        // has to build it again. Without this the activity quietly keeps the previous mode
        // and a test for the header runs against a page that has none - which is how the
        // occlusion check came back failing against a bar that was never on screen.
        val wanted = intent.getStringExtra(EXTRA_MODE) ?: "plain"
        if (wanted != mode) {
            recreate()
            return
        }
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        // Where every line of this page is, whenever a test asks. Answered after the page has
        // been told where to scroll to, so it describes the page the test is about to look at.
        if (intent.getIntExtra("placed", 0) != 0) {
            sayingWhereLinesAre = true
            window?.decorView?.postDelayed({ runCatching { placed() }.onFailure {
                Log.d(TAG, "PLACED failed: $it")
            } }, 900)
        }
        // Only from a shell or from this app. The releases people install are debug builds -
        // that is how this app is distributed - so this page and its intent extras are on
        // their phones, and those extras are the app's own settings: whether the overlay is
        // on, how often it transcribes, whether the words take touches. Another app could
        // have started it and quietly reconfigured this one. adb comes through the shell.
        val who = referrer?.host
        if (who != null && who != packageName && who !in SHELLS) {
            Log.d(TAG, "ignoring an intent from $who")
            finish()
            return
        }
        // Say again where every line is and what it is drawn in. These are reported when a
        // line is laid out, which happens once, so a page asked for a second time in the mode
        // it is already in said nothing at all about itself - and a test comparing the
        // overlay's colours against the page's own had nothing to compare them to.
        say()
        // Applied here rather than only in onCreate: a relaunch with the same mode does not
        // create the activity again, so settings passed that way were silently dropped and
        // a test that changed the frequency measured the previous one.
        applySettings(intent)
        // Changing a setting alters nothing on screen, so nothing tells the service to look
        // again - and a test that changes the frequency and reads the result sees whatever
        // was there before, or nothing at all. A one-pixel nudge is a real content change
        // and makes the next read describe the new setting.
        announce()
        // Not while a movement is being driven: the nudge changes the content, the list is
        // laid out again, and a list being laid out clamps its scroll position and reports
        // the jump - inside the very window the movement is measured in. The movement is a
        // change of its own and needs no help being noticed.
        if (!intent.hasExtra(EXTRA_MOTION)) nudge()
        if (intent.hasExtra(EXTRA_SCROLL)) {
            val y = intent.getIntExtra(EXTRA_SCROLL, 0)
            // Jumped, not animated: the test wants the movement finished. A Compose page has
            // to have composed itself before it can be told anything, so this waits for it.
            val y0 = y
            val target = page()
            target.view.postDelayed({
                val now = page()
                now.moveTo(y0)
                Log.d(TAG, "SETTLED $y0")
            }, if (composePage == null && (mode == "lazy" || mode == "chat")) 400 else 0)
        }
        if (intent.hasExtra(EXTRA_FLING) && listView == null) {
            val v = intent.getIntExtra(EXTRA_FLING, 0)
            // A real fling, with the platform's own deceleration, which is the motion a
            // finger actually produces and nothing like a straight line.
            val rows = recycler
            if (rows != null) rows.post { rows.fling(0, v) }
            else if (::scroller.isInitialized) scroller.post { scroller.fling(v) }
        }
        if (intent.hasExtra(EXTRA_LIVE)) {
            startLiving(intent.getIntExtra(EXTRA_LIVE, 4000))
        }
        if (intent.hasExtra(EXTRA_MOTION)) {
            // A movement with a shape to it, driven frame by frame, so the suite can hold
            // the overlay against something other than the one motion the platform makes.
            val profile = intent.getStringExtra(EXTRA_MOTION) ?: "minjerk"
            val distance = intent.getIntExtra(EXTRA_DISTANCE, 600)
            val duration = intent.getIntExtra(EXTRA_DURATION, 700)
            val strokes = intent.getIntExtra(EXTRA_STROKES, 1)
            val seed = intent.getIntExtra(EXTRA_SEED, 1)
            val page = page()
            page.view.post {
                ScrollMotion.run(page, profile, distance, duration, strokes, seed)
            }
        }
        if (intent.hasExtra(EXTRA_SMOOTH)) {
            val by = intent.getIntExtra(EXTRA_SMOOTH, 0)
            val list = listView
            val rows = recycler
            if (list != null) list.post { list.smoothScrollByOffset(by / 100) }
            else if (rows != null) rows.post { rows.smoothScrollBy(0, by) }
            else scroller.post { scroller.smoothScrollBy(0, by) }
        }
    }

    private fun applySettings(i: Intent) {
        // Announced after the fact, so a test can wait for a reading taken after the setting
        // landed rather than one taken just before it - which is a race it loses about half
        // the time, and reports as the setting doing nothing.

            // The marks a capture of the screen is read by, and whether the words themselves
            // take touches: both are things a test asks for and a reader never sees.
            if (i.hasExtra("gapMs")) {
                io.github.nanbiz.phonetix.service.PhonetixAccessibilityService.gapMs =
                    i.getIntExtra("gapMs", 16).toLong()
            }
            if (i.hasExtra("hurriedTenths")) {
                io.github.nanbiz.phonetix.service.PhonetixAccessibilityService.HURRIED_PX_PER_MS =
                    i.getIntExtra("hurriedTenths", 10) / 10f
            }
            if (i.hasExtra("anchors")) {
                io.github.nanbiz.phonetix.service.PhonetixAccessibilityService.ANCHORS =
                    i.getIntExtra("anchors", 3)
            }
            if (i.hasExtra("measureMoving")) {
                io.github.nanbiz.phonetix.service.PhonetixAccessibilityService.MEASURE_MOVING_MAX =
                    i.getIntExtra("measureMoving", 2)
            }
            if (i.hasExtra("anchorsWhenKnown")) {
                io.github.nanbiz.phonetix.service.PhonetixAccessibilityService.ANCHORS_WHEN_KNOWN =
                    i.getIntExtra("anchorsWhenKnown", 1)
            }
            if (i.hasExtra("coastGaps")) {
                io.github.nanbiz.phonetix.service.MotionLayer.coastGaps =
                    i.getIntExtra("coastGaps", 100) / 100f
            }
            if (i.hasExtra("fadeGaps")) {
                io.github.nanbiz.phonetix.service.MotionLayer.fadeGaps =
                    i.getIntExtra("fadeGaps", 200) / 100f
            }
            if (i.hasExtra("steadyGaps")) {
                io.github.nanbiz.phonetix.service.MotionLayer.steadyGaps =
                    i.getIntExtra("steadyGaps", 250) / 100f
            }
            if (i.hasExtra("predictFromFirst")) {
                io.github.nanbiz.phonetix.service.MotionLayer.predictFromFirst =
                    i.getIntExtra("predictFromFirst", 0) != 0
            }
            if (i.hasExtra("wrongByPx")) {
                io.github.nanbiz.phonetix.service.MotionLayer.wrongByPx =
                    i.getIntExtra("wrongByPx", 40).toFloat()
            }
            if (i.hasExtra("followableMs")) {
                io.github.nanbiz.phonetix.service.MotionLayer.followableMs =
                    i.getIntExtra("followableMs", 200).toLong()
            }
            if (i.hasExtra("putThemWrongBy")) {
                io.github.nanbiz.phonetix.service.PhonetixAccessibilityService.PUT_THEM_WRONG_BY =
                    i.getIntExtra("putThemWrongBy", 0).toFloat()
            }
            if (i.hasExtra("useSaidScroll")) {
                io.github.nanbiz.phonetix.service.PhonetixAccessibilityService.USE_SAID_SCROLL =
                    i.getIntExtra("useSaidScroll", 1) != 0
            }
            if (i.hasExtra("timeAsking")) {
                io.github.nanbiz.phonetix.service.PhonetixAccessibilityService.running
                    ?.timeTheAsking(i.getIntExtra("timeAsking", 10))
            }
            if (i.hasExtra("cameraRace")) {
                io.github.nanbiz.phonetix.service.PhonetixAccessibilityService.running
                    ?.raceTheCamera(
                        i.getIntExtra("cameraRace", 12),
                        i.getIntExtra("cameraGap", 0).toLong(),
                    )
            }
            if (i.hasExtra("staleMs")) {
                io.github.nanbiz.phonetix.service.MotionLayer.staleMs =
                    i.getIntExtra("staleMs", 300).toLong()
            }
            if (i.hasExtra("leadMs")) {
                io.github.nanbiz.phonetix.service.MotionLayer.leadMs =
                    i.getIntExtra("leadMs", 0).toLong()
            }
            // A dialog over the page: a window of this app's own, above the text but below
            // the transcriptions, which is a thing the overlay has to notice.
            if (i.getIntExtra("dialog", 0) != 0) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("A dialog")
                    .setMessage("It stands over the page and the page is still underneath it.")
                    .setPositiveButton("Fine") { d, _ -> d.dismiss() }
                    .show()
            }
            if (i.hasExtra("marks")) {
                DebugMarks.on = i.getIntExtra("marks", 0) != 0
                window?.decorView?.invalidate()
            }
            if (i.hasExtra("useButton")) {
                io.github.nanbiz.phonetix.core.SettingsStore.setUseButton(
                    i.getIntExtra("useButton", 1) != 0,
                )
            }
            if (i.hasExtra("touchWords")) {
                io.github.nanbiz.phonetix.core.SettingsStore.setTouchWords(
                    i.getIntExtra("touchWords", 0) != 0,
                )
            }
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
        // Only the page made of ordinary views has a marker to toggle; the others announce
        // themselves by being scrolled.
        if (!::scroller.isInitialized) return
        scroller.postDelayed({
            val text = marker ?: return@postDelayed
            text.text = if (text.text.isEmpty()) "\u200b" else ""
        }, 60)
    }

    /**
     * Write into the box and move it, and say where every line of it is as it happens.
     *
     * The words are added one at a time, which reflows the lines under them, and the whole
     * column is slid at the same time, which is a box making room for itself. What is
     * reported is where each line actually is on each frame - the only ground truth for
     * whether a transcription is on its word while both are moving.
     */
    private fun startLiving(durationMs: Int) {
        val box = growing ?: return
        if (living) return
        living = true
        written = 0
        val began = SystemClock.uptimeMillis()
        val column = box.parent as? View
        val words = TestWords.DISTINCT.joinToString(" ").split(" ")
        val frame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = SystemClock.uptimeMillis()
                val gone = (now - began).toFloat()
                if (gone > durationMs) {
                    living = false
                    Log.d(TAG, "LIVE done at=$now")
                    return
                }
                // A word every so often, and a slide that never stops: the two together are
                // what an answer being written looks like.
                val wanted = (gone / WORD_MS).toInt()
                if (wanted > written && written < words.size) {
                    written = wanted.coerceAtMost(words.size)
                    box.text = words.take(written).joinToString(" ")
                }
                column?.translationY = -(gone / durationMs) * SLIDE_PX * resources.displayMetrics.density
                report(now)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Log.d(TAG, "LIVE start at=${SystemClock.uptimeMillis()} duration=$durationMs")
        box.post { Choreographer.getInstance().postFrameCallback(frame) }
    }

    /**
     * Where every line of this page sits and what it says, once it has been laid out.
     *
     * Reported at the position the page is scrolled to when it is written, so a test can
     * work out where any line was at any moment from the scroll positions the page reports
     * anyway - and therefore which line a transcription was sitting on. Nothing else here
     * can tell a transcription on its own word from one left behind on somebody else's: both
     * are level with a line of text, which is all a photograph of a bar can see.
     */
    /** Whether a test has asked this page to say where its lines are. */
    private var sayingWhereLinesAre = false

    /**
     * Where each line of a recycling list is on the screen, now.
     *
     * A list that recycles hands a row to a different line as it scrolls, so what it said
     * about its layout once is about rows that hold other text by the time a test reads it -
     * and a check that matched a transcription against that report was matching it against
     * text no longer there. This is the same report the Compose list gives as it moves.
     */
    private fun placedNow() {
        val rows = recycler ?: return
        if (!sayingWhereLinesAre) return
        val at = IntArray(2)
        val now = SystemClock.uptimeMillis()
        for (i in 0 until rows.childCount) {
            val child = rows.getChildAt(i) as? TextView ?: continue
            val layout = child.layout ?: continue
            val text = child.text?.toString().orEmpty()
            child.getLocationOnScreen(at)
            for (n in 0 until layout.lineCount) {
                val top = at[1] + child.totalPaddingTop + layout.getLineTop(n)
                val height = layout.getLineBottom(n) - layout.getLineTop(n)
                val said = text.substring(layout.getLineStart(n), layout.getLineEnd(n)).trim()
                if (said.isEmpty()) continue
                Log.d(TAG, "PLACEDAT $now $top $height $said")
            }
        }
    }

    private fun placed() {
        val found = ArrayList<TextView>(32)
        collectLines(window?.decorView, found)
        if (found.isEmpty()) {
            Log.d(TAG, "PLACED nothing: no lines of text on this page")
            return
        }
        val at = IntArray(2)
        val now = SystemClock.uptimeMillis()
        // A list has no ScrollView behind it, and asking a page which kind it is by touching
        // the field is how this first came back empty.
        // Every kind of page keeps its scroll somewhere different, and asking the wrong one
        // throws rather than answers - which is how this came back empty for a whole page.
        val scrolled = when {
            recycler != null -> recyclerAt
            listView != null -> listScroll()
            ::scroller.isInitialized -> scroller.scrollY
            else -> 0
        }
        // In the document's own coordinates, so a test can work out where a line was at any
        // moment from the scroll positions this page reports anyway. A recycling list hands
        // the same row to a different line as it scrolls, so there is no fixed row to report:
        // what is reported is where each line of text is now, plus where the page is now.
        // A line of the screen, not a view: a paragraph wraps over several, and a
        // transcription two lines from its word is still inside the paragraph that holds it -
        // so reporting views cannot tell the two apart, and the app a reader complained about
        // is all wrapped paragraphs.
        var n = 0
        for (child in found) {
            child.getLocationOnScreen(at)
            val layout = child.layout
            val text = child.text?.toString().orEmpty()
            if (layout == null) {
                Log.d(TAG, "PLACED $now ${n++} ${at[1] + scrolled} ${child.height} $text")
                continue
            }
            for (i in 0 until layout.lineCount) {
                val top = at[1] + scrolled + child.totalPaddingTop + layout.getLineTop(i)
                val height = layout.getLineBottom(i) - layout.getLineTop(i)
                val said = text.substring(layout.getLineStart(i), layout.getLineEnd(i)).trim()
                if (said.isEmpty()) continue
                Log.d(TAG, "PLACED $now ${n++} $top $height $said")
            }
        }
    }

    /** Every line of text on the page, wherever it is and whatever holds it. */
    private fun collectLines(view: View?, into: MutableList<TextView>) {
        if (view is TextView && !view.text.isNullOrBlank()) {
            into.add(view)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectLines(view.getChildAt(i), into)
        }
    }

    /** Where each line of the living page is now, and what it says. */
    private fun report(now: Long) {
        val column = (growing?.parent as? LinearLayout) ?: return
        val at = IntArray(2)
        val out = StringBuilder("LINES ").append(now).append(' ')
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i) as? TextView ?: continue
            val text = child.text?.toString().orEmpty()
            if (text.isEmpty()) continue
            child.getLocationOnScreen(at)
            out.append(i).append(':').append(at[1]).append(',').append(child.height)
                .append(',').append(text.hashCode()).append(' ')
        }
        Log.d(TAG, out.toString())
    }

    private fun content(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), if (mode == "header") headerHeight() else pad(), pad(), pad())
        if (mode == "live") {
            // A box that grows while it moves, which is what a message being written into a
            // conversation does: a word is added every few frames, the lines below are
            // pushed down by it, and the whole block slides as the app makes room. Nothing
            // here scrolls. Every fixture before this one moved a page whose text stayed
            // exactly as it was, so the case a reader actually watches - text that changes
            // while it moves - was never once tested.
            for (text in TestWords.DISTINCT.take(6)) addView(line(text, Color.WHITE, BACKGROUND))
            growing = line("", Color.WHITE, BACKGROUND).also { addView(it) }
            for (text in TestWords.DISTINCT.drop(6).take(6)) {
                addView(line(text, Color.WHITE, BACKGROUND))
            }
        } else if (mode == "essay") {
            // Paragraphs that wrap, of words that appear nowhere else on the page.
            //
            // Every other fixture here puts one short line in one view, so a line's words and
            // the view holding them are the same thing. The app a reader complained about is
            // not like that: a message is one long run of text that wraps over a dozen lines,
            // and a transcription is placed inside it from the character positions the app
            // reports. Wrapping is where those come apart - and because the words are unique,
            // a test can say which line a transcription is sitting on and whether that line
            // is the one its word belongs to.
            // Two of the word lines to a paragraph, so each wraps over several lines of the
            // screen, and every word of the page appears exactly once.
            // Three times through the words: ten paragraphs of sixteen fill about a screen
            // and a half, so a drag of a screen ran off the end of the page and the rest of
            // the movement was judged on a page that had stopped.
            for (n in 0 until TestWords.DISTINCT.size / 2 * 3) {
                val from = (n * 2) % TestWords.DISTINCT.size
                val paragraph = "$n " + TestWords.DISTINCT.drop(from).take(2).joinToString(" ")
                addView(line(paragraph, Color.WHITE, BACKGROUND))
            }
        } else if (mode == "unique") {
            for (text in TestWords.DISTINCT) addView(line(text, Color.WHITE, BACKGROUND))
        } else if (mode == "german") {
            // A page in a language the dictionary is not for. Nothing on it should be
            // transcribed: an English pronunciation put on a German word is not a
            // pronunciation of that word.
            for (text in TestWords.GERMAN) addView(line(text, Color.WHITE, BACKGROUND))
        } else if (mode == "gradient") {
            // A page that is not one colour. A music player, a photo behind a title, a sheet
            // that fades into the wallpaper: the surface a word sits on at the top of the
            // screen is not the surface a word sits on at the bottom, and a line whose own
            // colours cannot be read has to be given the colours where it is rather than the
            // one colour that covers most of the screen. Told to draw its text in the colour
            // it is standing on, so no line here can be read on its own terms and every one
            // of them takes the fallback.
            // The text is drawn in nothing at all, so no line can be told from the surface it
            // is on and every one of them falls back. Four lines stand on one colour and the
            // rest on another, so the colour that covers most of the screen is the wrong
            // answer for the four - which is the case a screen with a title over artwork and
            // a dark half below it presents.
            for (text in TestWords.DISTINCT.take(4)) {
                addView(line(text, Color.TRANSPARENT, BAND))
            }
            for (text in TestWords.DISTINCT.drop(4).take(10)) {
                addView(line(text, Color.TRANSPARENT, BACKGROUND))
            }
        } else if (mode == "colors") {
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

    class Holder(val line: TextView) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(line)

    private fun line(text: String, ink: Int, bg: Int) = MarkedLine(this).apply {
        this.text = text
        setTextColor(ink)
        setBackgroundColor(bg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        // A gutter down the left, which is where a line puts the bar that says where it is.
        // Without it the bar sits exactly where the line's first word starts, so the
        // transcription of that word covers it and the line reports itself as absent.
        setPadding(DebugMarks.GUTTER, pad(), 0, pad())
        // What this line is really drawn in and where it ended up, so a test comparing the
        // overlay's colours against the app's own owes nothing to the overlay's account of
        // itself, and can tell one line's words from the next line's identical ones.
        setTag(R_INK, ink)
        setTag(R_BG, bg)
        post { report(this) }
    }

    /** Where a line ended up and what it is really drawn in. */
    private fun report(view: TextView) {
        val ink = view.getTag(R_INK) as? Int ?: return
        val bg = view.getTag(R_BG) as? Int ?: return
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        Log.d(
            TAG,
            "SURFACE ink=#%06X bg=#%06X at=%d,%d,%d,%d text=%s".format(
                ink and 0xFFFFFF, bg and 0xFFFFFF,
                at[0], at[1], view.width, view.height, view.text,
            ),
        )
    }

    /** Every line of the page saying where it is, however the page was arrived at. */
    private fun say() {
        val root = window?.decorView ?: return
        root.post {
            fun walk(v: View) {
                if (v is TextView && v.getTag(R_INK) != null) report(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        }
    }

    private fun pad() = (16 * resources.displayMetrics.density).toInt()
    private fun headerHeight() = (72 * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "PhonetixTest"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MOTION = "motion"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_STROKES = "strokes"
        const val EXTRA_SEED = "seed"
        const val EXTRA_LIVE = "live"
        const val EXTRA_SCROLL = "scrollTo"
        const val EXTRA_FLING = "fling"
        const val EXTRA_SMOOTH = "smoothBy"
        const val EXTRA_ENABLE = "enable"
        const val EXTRA_DENSITY = "density"
        const val EXTRA_SCOPE = "allApps"
        const val HEADER_TAG = "header"
        // Flat black and white on purpose: what the sampler should have read is then a
        // fact rather than an opinion.
        /** How often a word is added to the box being written into. */
        const val WORD_MS = 90f
        /** How far the whole block slides while it is written, in dp. */
        const val SLIDE_PX = 220f

        /** What adb and the system shell come through. */
        val SHELLS = setOf("com.android.shell", "android")

        const val BACKGROUND = Color.BLACK

        /** Where a line keeps the colours it was built with, for reporting them again. */
        val R_INK = io.github.nanbiz.phonetix.R.id.debug_ink
        val R_BG = io.github.nanbiz.phonetix.R.id.debug_bg

        /** The colour of the part of a page that is not the rest of it: what a title sits on
         *  above a dark body, which no single colour for the whole screen can stand for. */
        val BAND = Color.rgb(0x2A, 0x2E, 0x10)
        const val PARAGRAPH =
            "Reading a paragraph teaches pronunciation quietly because every unfamiliar " +
                "word arrives already spoken and the dictionary answers immediately."
    }
}
