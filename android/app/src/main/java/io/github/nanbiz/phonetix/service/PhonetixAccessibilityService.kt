package io.github.nanbiz.phonetix.service

import android.accessibilityservice.AccessibilityService
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nanbiz.phonetix.core.Dictionary
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
 */
class PhonetixAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var rectsOk = 0
    private var rectsFail = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsStore.init(this)
        Dictionary.ensureLoaded(this) { handler.post { schedule() } }
        overlay = OverlayController(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        schedule()
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.clear()
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.destroy()
        super.onDestroy()
    }

    /**
     * Content-changed and scrolled events arrive in floods while a list moves. Collapsing
     * them into one pass per idle moment is the difference between a readable overlay and
     * a device that spends its battery re-reading the same screen.
     */
    private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            runCatching { scan() }
        }, SETTLE_MS)
    }

    private fun scan() {
        if (!::overlay.isInitialized) return
        val settings = SettingsStore.current
        val root = rootInActiveWindow
        if (root == null) { overlay.clear(); return }
        if (!SettingsStore.allows(root.packageName?.toString()) || !Dictionary.ready) {
            android.util.Log.d(
                "Phonetix",
                "skip pkg=${root.packageName} enabled=${settings.enabled} " +
                    "allows=${SettingsStore.allows(root.packageName?.toString())} dict=${Dictionary.ready}",
            )
            overlay.clear()
            return
        }

        val out = ArrayList<WordBox>(64)
        val transcriber = Transcriber(settings.density)
        val budget = Budget()
        collect(root, transcriber, out, budget)
        android.util.Log.d(
            "Phonetix",
            "scan pkg=${root.packageName} textNodes=${MAX_NODES - budget.nodes} " +
                "rectsOk=$rectsOk rectsFail=$rectsFail boxes=${out.size} density=${settings.density}",
        )
        rectsOk = 0; rectsFail = 0
        overlay.render(out, settings.style)
    }

    /** Caps so one very dense screen cannot stall the pass. */
    private class Budget(var nodes: Int = MAX_NODES, var words: Int = MAX_WORDS)

    private fun collect(node: AccessibilityNodeInfo?, t: Transcriber, out: MutableList<WordBox>, budget: Budget) {
        if (node == null || budget.nodes <= 0 || budget.words <= 0) return
        // Nothing off-screen is worth measuring, and asking for its character bounds is
        // the expensive part of the pass.
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= MAX_TEXT) {
            budget.nodes--
            charRects(node, text)?.let { rects ->
                val before = out.size
                t.boxes(text, rects, out)
                budget.words -= (out.size - before)
            }
        }

        for (i in 0 until node.childCount) {
            collect(node.getChild(i), t, out, budget)
            if (budget.nodes <= 0 || budget.words <= 0) return
        }
    }

    /**
     * The per-character screen rectangles for a node's text. Returns null when the app
     * does not report them — a Compose or Canvas-drawn surface that exposes text but no
     * layout, for instance — and those nodes are simply left alone.
     */
    private fun charRects(node: AccessibilityNodeInfo, text: String): Array<RectF?>? {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, text.length)
        }
        val ok = runCatching {
            node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
        }.getOrDefault(false)
        if (!ok) { rectsFail++; return null }
        rectsOk++
        val raw = node.extras?.getParcelableArray(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        ) ?: return null
        return Array(raw.size) { raw[it] as? RectF }
    }

    private companion object {
        const val SETTLE_MS = 140L
        const val MAX_NODES = 120
        const val MAX_WORDS = 400
        const val MAX_TEXT = 2000
    }
}
