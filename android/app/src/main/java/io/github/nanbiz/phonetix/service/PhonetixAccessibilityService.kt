package io.github.nanbiz.phonetix.service

import android.accessibilityservice.AccessibilityService
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nanbiz.phonetix.core.Dictionary
import io.github.nanbiz.phonetix.core.Pick
import io.github.nanbiz.phonetix.core.SettingsStore
import io.github.nanbiz.phonetix.core.Transcriber
import io.github.nanbiz.phonetix.core.WordBox

/**
 * Reads the text of the app in front and hands the overlay the words to transcribe.
 *
 * Accessibility gives a node's text and the node's own rectangle, which is a whole
 * paragraph and far too coarse to put a transcription on one word. The per-character
 * rectangles asked for below are what make word-level placement possible at all: the
 * characters of a word are unioned into that word's exact box.
 *
 * Two rules govern the timing, and they pull in opposite directions. Putting a
 * transcription up may take a moment. Taking one down may not: the instant the screen
 * moves every transcription is in the wrong place, so they are hidden synchronously from
 * the event itself and only restored once the screen has been read again.
 */
class PhonetixAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private lateinit var sampler: ScreenSampler
    private var generation = 0
    private var lastScanEnd = 0L

    // What the last full pass found. A scroll moves these words without changing them, so
    // the next pass can ask them directly for their new positions instead of walking the
    // whole tree again - which is sixty-odd calls into the other app, against five.
    @Volatile private var cachedPlan: List<Planned> = emptyList()
    @Volatile private var cachedPackage: String? = null
    @Volatile private var scrollOnly = false

    // One pair of colours per app, kept until the app changes. Text inside one app is drawn
    // in one or two colours, so this is both steadier and cheaper than asking per word.
    @Volatile private var appColors: WordColors? = null
    @Volatile private var appColorsFor: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsStore.init(this)
        overlay = OverlayController(this)
        val thread = HandlerThread("phonetix-scan").apply { start() }
        worker = Handler(thread.looper)
        sampler = ScreenSampler(this) { r -> worker.post(r) }
        Dictionary.ensureLoaded(this) { schedule(0L) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A scroll says how far the content moved, and the words moved exactly that far, so
        // the transcriptions are carried along in this same frame rather than being taken
        // down and put back. Re-reading the screen then only has to correct the drift.
        // A scroll event reports how far a view believes it travelled, and that number does
        // not agree with how far the words actually moved on screen - measured at 313
        // reported against 176 real pixels across one swipe. Positions guessed from it drift
        // a little further with every event, which is how a transcription ends up sitting
        // beside its word instead of on it. So nothing is extrapolated: the words are asked
        // where they are, which now costs about seven milliseconds because only their
        // bounds are re-fetched, and that is fast enough to simply do it again.
        val isScroll = event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        // Only a run of pure scrolls keeps the fast path; anything else may have changed
        // the words themselves and has to be read properly.
        scrollOnly = isScroll && (scrollOnly || cachedPlan.isNotEmpty())
        if (!isScroll) scrollOnly = false
        schedule(if (isScroll) GAP_SCROLL_MS else GAP_MS)
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hideNow()
    }

    override fun onDestroy() {
        if (::sampler.isInitialized) sampler.destroy()
        if (::overlay.isInitialized) overlay.destroy()
        if (::worker.isInitialized) worker.looper.quitSafely()
        super.onDestroy()
    }

    /**
     * Content-changed and scrolled events arrive in floods while a list moves. Only the
     * last one matters, so each event cancels the pass the one before it asked for; the
     * work happens off the main thread, where a slow read cannot stutter the app being
     * read.
     */
    /**
     * Run on the leading edge, not the trailing one.
     *
     * A trailing debounce made every change wait out the full window even when nothing had
     * happened for a second beforehand, which was the largest single piece of the delay and
     * was entirely self-inflicted. The first event after a quiet moment is acted on at once;
     * only while events keep arriving does the next pass wait, and then just long enough to
     * keep a flood from turning into a queue of passes.
     */
    private fun schedule(minGap: Long) {
        if (!::worker.isInitialized) return
        val mine = ++generation
        val now = android.os.SystemClock.uptimeMillis()
        val delay = (lastScanEnd + minGap - now).coerceIn(0L, minGap)
        worker.removeCallbacksAndMessages(null)
        worker.postDelayed({
            if (mine != generation) return@postDelayed
            runCatching { scan() }
            lastScanEnd = android.os.SystemClock.uptimeMillis()
        }, delay)
    }

    private fun scan() {
        val settings = SettingsStore.current
        val root = rootInActiveWindow ?: run { main.post { overlay.hideNow() }; return }
        if (!SettingsStore.allows(root.packageName?.toString()) || !Dictionary.ready) {
            main.post { overlay.hideNow() }
            return
        }

        val pkg = root.packageName?.toString()
        val t0 = android.os.SystemClock.uptimeMillis()

        // A scroll moved the words it did not change, so the nodes found last time are
        // still the right ones and only their positions need asking for again.
        val reuse = scrollOnly && pkg != null && pkg == cachedPackage && cachedPlan.isNotEmpty()
        val planned: List<Planned>
        val budget = Budget()
        val stats = Stats()
        if (reuse) {
            planned = cachedPlan
        } else {
            val fresh = ArrayList<Planned>(16)
            plan(root, Transcriber(settings.density), fresh, budget, stats)
            planned = fresh
            cachedPlan = fresh
            cachedPackage = pkg
        }
        val t1 = android.os.SystemClock.uptimeMillis()

        // Second pass: ask only the nodes that actually hold a chosen word for their
        // character bounds. That request is a round trip into the other app and is the
        // slowest thing here, so it is spent only on the nodes that earned it.
        val boxes = ArrayList<WordBox>(planned.size * 2)
        for (p in planned) {
            val rects = charRects(p.node, p.from, p.length) ?: continue
            Transcriber.boxes(p.picks, rects, p.from, boxes)
        }
        // Nodes go stale when the screen really did change under a scroll; falling back to
        // a full read is cheaper than showing nothing.
        if (reuse && boxes.isEmpty()) {
            cachedPlan = emptyList()
            scrollOnly = false
            schedule(0L)
            return
        }

        // Colours come from the screen itself, because accessibility does not carry them.
        // The capture is rate-limited by the platform, so a frame is asked for here and
        // whatever frame is already in hand is what these boxes are coloured from.
        sampler.refreshIfStale()
        if (pkg != appColorsFor) { appColors = null; appColorsFor = pkg }
        if (appColors == null && boxes.isNotEmpty()) {
            sampler.sampleAll(boxes.map { it.rect })?.let { appColors = it }
        }
        val c = appColors
        val painted = if (c == null) boxes else boxes.map {
            it.copy(background = c.background, ink = c.ink)
        }

        val t2 = android.os.SystemClock.uptimeMillis()
        val style = settings.style
        main.post {
            val t3 = android.os.SystemClock.uptimeMillis()
            overlay.render(painted, style)
            android.util.Log.d(
                "Phonetix",
                "plan=${t1 - t0}ms${if (reuse) " REUSED" else ""} (ipc=${stats.ipcNs / 1_000_000}ms in ${stats.calls} calls, ours=${stats.computeNs / 1_000_000}ms) nodes=${MAX_NODES - budget.nodes} " +
                    "bounds=${t2 - t1}ms calls=${planned.size} " +
                    "render=${android.os.SystemClock.uptimeMillis() - t3}ms boxes=${boxes.size} " +
                    "coloured=${painted.count { it.background != 0 }}",
            )
        }
    }

    private class Planned(
        val node: AccessibilityNodeInfo,
        val from: Int,
        val length: Int,
        val picks: List<Pick>,
    )

    /** Caps so one very dense screen cannot stall a pass. */
    /** Split of where a pass actually goes: calls into the other app, versus our own work. */
    private class Stats(var ipcNs: Long = 0, var computeNs: Long = 0, var calls: Int = 0)

    private class Budget(
        var nodes: Int = MAX_NODES,
        var words: Int = MAX_WORDS,
        /** Every getChild() is a call into the app being read, and a deep tree has
         *  thousands of them; this is what stops one screen costing a second. */
        var visits: Int = MAX_VISITS,
    )

    private fun plan(
        node: AccessibilityNodeInfo?,
        t: Transcriber,
        out: MutableList<Planned>,
        budget: Budget,
        stats: Stats,
    ) {
        if (node == null || budget.nodes <= 0 || budget.words <= 0 || budget.visits <= 0) return
        budget.visits--
        var mark = System.nanoTime()
        val visible = node.isVisibleToUser
        val text = if (visible) node.text?.toString() else null
        stats.ipcNs += System.nanoTime() - mark
        stats.calls++
        if (!visible) return

        if (!text.isNullOrBlank() && text.length <= MAX_TEXT) {
            budget.nodes--
            mark = System.nanoTime()
            val picks = t.plan(text)
            stats.computeNs += System.nanoTime() - mark
            if (picks.isNotEmpty()) {
                // Only the span from the first chosen word to the last: asking for a whole
                // paragraph's character boxes costs the app that owns it real layout work,
                // and the words in between are not going to be drawn.
                val from = picks.first().start
                val to = picks.last().end
                out.add(Planned(node, from, to - from + 1, picks))
                budget.words -= picks.size
            }
        }

        mark = System.nanoTime()
        val n = node.childCount
        stats.ipcNs += System.nanoTime() - mark
        for (i in 0 until n) {
            mark = System.nanoTime()
            val child = node.getChild(i)
            stats.ipcNs += System.nanoTime() - mark
            stats.calls++
            plan(child, t, out, budget, stats)
            if (budget.nodes <= 0 || budget.words <= 0 || budget.visits <= 0) return
        }
    }

    /**
     * The per-character screen rectangles for a node's text. Returns null when the app does
     * not report them - a Compose or Canvas-drawn surface that exposes text but no layout,
     * for instance - and those nodes are left alone rather than guessed at.
     */
    private fun charRects(node: AccessibilityNodeInfo, from: Int, length: Int): Array<RectF?>? {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, from)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        }
        val ok = runCatching {
            node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
        }.getOrDefault(false)
        if (!ok) return null
        val raw = node.extras?.getParcelableArray(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        ) ?: return null
        return Array(raw.size) { raw[it] as? RectF }
    }

    private companion object {
        // Short enough that a transcription follows a settled screen almost at once, long
        // enough that a fling is read once at its end rather than at every frame.
        // The smallest gap between passes. Not a wait before acting: the first event after
        // a quiet moment runs immediately, and this only spaces out a flood.
        const val GAP_MS = 16L
        // Roughly a frame: while a list is moving, re-place the transcriptions from real
        // positions as often as the screen itself changes.
        const val GAP_SCROLL_MS = 16L
        const val MAX_NODES = 120
        const val MAX_VISITS = 400
        const val MAX_WORDS = 60
        const val MAX_TEXT = 2000
    }
}
