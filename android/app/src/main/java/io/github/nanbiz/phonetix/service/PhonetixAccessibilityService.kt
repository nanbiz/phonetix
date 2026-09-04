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
    private var generation = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsStore.init(this)
        overlay = OverlayController(this)
        val thread = HandlerThread("phonetix-scan").apply { start() }
        worker = Handler(thread.looper)
        Dictionary.ensureLoaded(this) { schedule() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A scroll says how far the content moved, and the words moved exactly that far, so
        // the transcriptions are carried along in this same frame rather than being taken
        // down and put back. Re-reading the screen then only has to correct the drift.
        var scrolled = false
        if (::overlay.isInitialized && event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val dx = event.scrollDeltaX
            val dy = event.scrollDeltaY
            if (dx != Int.MIN_VALUE && dy != Int.MIN_VALUE) {
                overlay.shiftBy(-dx, -dy)
                scrolled = true
            }
        }
        schedule(if (scrolled) SETTLE_SCROLL_MS else SETTLE_MS)
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hideNow()
    }

    override fun onDestroy() {
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
    private fun schedule(settle: Long = SETTLE_MS) {
        if (!::worker.isInitialized) return
        val mine = ++generation
        worker.removeCallbacksAndMessages(null)
        worker.postDelayed({ if (mine == generation) runCatching { scan() } }, settle)
    }

    private fun scan() {
        val settings = SettingsStore.current
        val root = rootInActiveWindow ?: run { main.post { overlay.hideNow() }; return }
        if (!SettingsStore.allows(root.packageName?.toString()) || !Dictionary.ready) {
            main.post { overlay.hideNow() }
            return
        }

        val t0 = android.os.SystemClock.uptimeMillis()
        // First pass: decide which words are worth showing. This needs only their text, so
        // it is cheap, and it is what keeps the second pass small.
        val planned = ArrayList<Planned>(16)
        val transcriber = Transcriber(settings.density)
        val budget = Budget()
        plan(root, transcriber, planned, budget)
        val t1 = android.os.SystemClock.uptimeMillis()

        // Second pass: ask only the nodes that actually hold a chosen word for their
        // character bounds. That request is a round trip into the other app and is the
        // slowest thing here, so it is spent only on the nodes that earned it.
        val boxes = ArrayList<WordBox>(planned.size * 2)
        for (p in planned) {
            val rects = charRects(p.node, p.from, p.length) ?: continue
            Transcriber.boxes(p.picks, rects, p.from, boxes)
        }

        val t2 = android.os.SystemClock.uptimeMillis()
        val style = settings.style
        main.post {
            val t3 = android.os.SystemClock.uptimeMillis()
            overlay.render(boxes, style)
            android.util.Log.d(
                "Phonetix",
                "plan=${t1 - t0}ms nodes=${MAX_NODES - budget.nodes} visits=${MAX_VISITS - budget.visits} " +
                    "bounds=${t2 - t1}ms calls=${planned.size} " +
                    "render=${android.os.SystemClock.uptimeMillis() - t3}ms boxes=${boxes.size}",
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
    ) {
        if (node == null || budget.nodes <= 0 || budget.words <= 0 || budget.visits <= 0) return
        budget.visits--
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= MAX_TEXT) {
            budget.nodes--
            val picks = t.plan(text)
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

        for (i in 0 until node.childCount) {
            plan(node.getChild(i), t, out, budget)
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
        const val SETTLE_MS = 60L
        // A scroll has already carried the transcriptions with it, so re-reading is only a
        // correction and can wait for the fling to finish instead of fighting it.
        const val SETTLE_SCROLL_MS = 220L
        const val MAX_NODES = 120
        const val MAX_VISITS = 400
        const val MAX_WORDS = 60
        const val MAX_TEXT = 2000
    }
}
