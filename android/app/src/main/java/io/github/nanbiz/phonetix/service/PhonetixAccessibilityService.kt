package io.github.nanbiz.phonetix.service

import android.accessibilityservice.AccessibilityService
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nanbiz.phonetix.BuildConfig
import io.github.nanbiz.phonetix.core.Dictionary
import io.github.nanbiz.phonetix.core.IpaSymbols
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
    private lateinit var tooltip: TooltipController
    private lateinit var speaker: Speaker
    private lateinit var io: Handler
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private lateinit var sampler: ScreenSampler
    private var generation = 0
    private var lastScanEnd = 0L
    @Volatile private var lastScrollAt = 0L
    @Volatile private var following = false

    // What the last full pass found. A scroll moves these words without changing them, so
    // the next pass can ask them directly for their new positions instead of walking the
    // whole tree again - which is sixty-odd calls into the other app, against five.
    @Volatile private var cachedPlan: List<Planned> = emptyList()
    @Volatile private var cachedPackage: String? = null
    @Volatile private var cachedPainted: List<Painted> = emptyList()
    @Volatile private var scrollOnly = false

    // Colours per line of text rather than per app: a heading and a paragraph in the same
    // app are rarely the same colour, and averaging them gives a transcription that matches
    // neither. Keyed by the text itself so a line keeps its colours while it is on screen,
    // and cleared when the app changes.
    private val wordColors = HashMap<String, WordColors>(64)
    /** Words already looked for on this screen, found or not, so the overlay steps aside
     *  a bounded number of times rather than once for every word it never manages to read. */
    private val colorAttempted = HashSet<String>(64)
    @Volatile private var colorsFor: String? = null
    /** The rectangles the overlay is actually showing, which is what a capture contains. */
    @Volatile private var onScreenBoxes: List<RectF> = emptyList()
    /** Set while the overlay is briefly down so a capture can see the text underneath. */
    @Volatile private var capturing = false
    @Volatile private var cleanFrameAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsStore.init(this)
        val thread = HandlerThread("phonetix-scan").apply { start() }
        worker = Handler(thread.looper)
        // A separate thread for capture and for fetching a diagram. Both were queued behind
        // the scans on the worker, and a scan can hold that thread for most of a second, so
        // the screenshot callback simply never arrived and the colours never came.
        val ioThread = HandlerThread("phonetix-io").apply { start() }
        io = Handler(ioThread.looper)
        speaker = Speaker(this)
        tooltip = TooltipController(this, speaker) { r -> io.post(r) }
        overlay = OverlayController(this) { box -> main.post { tooltip.show(box) } }
        IpaSymbols.ensureLoaded(this)
        sampler = ScreenSampler(this) { r -> io.post(r) }
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
        // Nothing is taken down for a scroll: the words move with it. The layer takes over
        // the moment one starts, and predicts between the measurements the loop below
        // feeds it.
        if (isScroll) {
            if (::tooltip.isInitialized) tooltip.hide()
            if (::overlay.isInitialized) main.post { overlay.beginMotion() }
            lastScrollAt = android.os.SystemClock.uptimeMillis()
            scrollOnly = scrollOnly || cachedPlan.isNotEmpty()
            startFollowing()
            return
        }
        scrollOnly = false
        schedule(GAP_MS)
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hideNow()
    }

    override fun onDestroy() {
        if (::tooltip.isInitialized) tooltip.hide()
        if (::speaker.isInitialized) speaker.destroy()
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
     * Keep re-reading positions for as long as the screen is moving.
     *
     * Driving this from the events themselves does not work: they arrive faster than a pass
     * completes, and each one cancelled the pass the last one asked for, so during a fling
     * the pass was starved and never ran at all - the transcriptions sat where the fling
     * began and were nearly a thousand pixels adrift by the time it stopped. This is a loop
     * that reschedules itself instead, and stops on its own once the events stop coming.
     */
    private fun startFollowing() {
        if (!::worker.isInitialized || following) return
        following = true
        worker.post(follower)
    }

    private val follower = object : Runnable {
        override fun run() {
            val since = android.os.SystemClock.uptimeMillis() - lastScrollAt
            if (since > STILL_MS) {
                following = false
                // One last read, to put the tappable windows back exactly on the words.
                runCatching { scan() }
                return
            }
            runCatching { scan() }
            worker.postDelayed(this, GAP_SCROLL_MS)
        }
    }

    /**
     * Run on the leading edge, not the trailing one.
     *
     * A trailing debounce made every change wait out the full window even when nothing had
     * happened for a second beforehand, which was the largest single piece of the delay and
     * was entirely self-inflicted. The first event after a quiet moment is acted on at once;
     * only while events keep arriving does the next pass wait, and then just long enough to
     * keep a flood from turning into a queue of passes.
     */
    private fun schedule(minGap: Long, trailing: Boolean = false) {
        if (!::worker.isInitialized) return
        val mine = ++generation
        val now = android.os.SystemClock.uptimeMillis()
        // Trailing for a scroll - wait for it to stop - and leading for everything else, so
        // an ordinary change is acted on at once.
        val delay = if (trailing) minGap else (lastScanEnd + minGap - now).coerceIn(0L, minGap)
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
            // Reported as an empty screen rather than saying nothing at all: silence here
            // reads to anyone watching as "the last set is still up", which is the very
            // thing that went wrong.
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Phonetix",
                    "BOXES ${android.os.SystemClock.uptimeMillis()} ${root.packageName} ",
                )
            }
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
            val full = android.graphics.Rect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
            val seen = ArrayList<Painted>(128)
            plan(root, Transcriber(settings.density), fresh, budget, stats, full, seen)
            planned = fresh
            cachedPlan = fresh
            cachedPackage = pkg
            cachedPainted = seen
        }
        val t1 = android.os.SystemClock.uptimeMillis()

        // Second pass: ask only the nodes that actually hold a chosen word for their
        // character bounds. That request is a round trip into the other app and is the
        // slowest thing here, so it is spent only on the nodes that earned it.
        // Following a scroll does not need the characters again. They have not moved
        // relative to their line; the line has moved. Asking each line where it is now is a
        // single cheap call, against a character-by-character re-layout inside the other app
        // that costs a hundred milliseconds and is the reason following a scroll looked
        // impossible.
        if (reuse) {
            val tf = android.os.SystemClock.uptimeMillis()
            val moved = ArrayList<WordBox>(16)
            var ok = true
            var why = ""
            var alive = 0
            var gone = 0
            for (p in planned) {
                val was = p.measuredAt
                // A line whose words were all clipped away contributes nothing, which is
                // normal and not a reason to abandon following the rest of them.
                if (was == null || p.boxes.isEmpty()) continue
                // A line that has scrolled off is recycled and can no longer be asked
                // anything - that is ordinary during a fling, and abandoning the whole pass
                // for it left the rest of the screen unfollowed and falling back to a full
                // read that costs hundreds of milliseconds. Skip the line, keep the others.
                if (!p.node.refresh()) { gone++; continue }
                if (p.node.text?.toString() != p.text) { gone++; continue }
                val now = android.graphics.Rect()
                p.node.getBoundsInScreen(now)
                if (now.isEmpty) { gone++; continue }
                alive++
                val dx = (now.left - was.left).toFloat()
                val dy = (now.top - was.top).toFloat()
                for (b in p.boxes) {
                    val r = RectF(b.rect.left + dx, b.rect.top + dy, b.rect.right + dx, b.rect.bottom + dy)
                    // The viewport does not move when its contents scroll, so the word is
                    // tested against the clip where it stands, not against a moved one.
                    // A word carried past the edge of its list simply drops out.
                    val inside = r.top >= p.clip.top - 1 && r.bottom <= p.clip.bottom + 1 &&
                        r.left >= p.clip.left - 1 && r.right <= p.clip.right + 1
                    if (inside && !covered(r, p.exit)) moved.add(b.copy(rect = r))
                }
            }
            // Nothing survived at all: the screen is not what it was, so read it properly.
            if (ok && alive == 0) {
                ok = false
                why = "every line was recycled"
            }
            // Most of the screen gone means the same thing, even if a line or two remain.
            if (ok && gone > alive) {
                ok = false
                why = "more lines recycled ($gone) than kept ($alive)"
            }
            if (ok) {
                val style0 = settings.style
                val took = android.os.SystemClock.uptimeMillis() - tf
                // Still moving: hand the measurement to the layer, which corrects both the
                // position and the speed it is carrying them at. Once the scrolling has
                // stopped, put the tappable windows back where the words actually are.
                val moving = following && android.os.SystemClock.uptimeMillis() - lastScrollAt < STILL_MS
                if (BuildConfig.DEBUG) {
                    val sb = StringBuilder("BOXES ")
                        .append(android.os.SystemClock.uptimeMillis()).append(' ')
                        .append(pkg).append(' ')
                    for ((i, b) in moved.withIndex()) {
                        sb.append(b.word).append('#').append(i).append(':')
                            .append(b.rect.left.toInt()).append(',')
                            .append(b.rect.top.toInt()).append(',')
                            .append(b.rect.right.toInt()).append(',')
                            .append(b.rect.bottom.toInt()).append(',')
                            .append(Integer.toHexString(b.background)).append(',')
                            .append(Integer.toHexString(b.ink)).append(',')
                            .append(if (b.background != 0 && b.ink != 0) 1 else 0).append(' ')
                    }
                    android.util.Log.d("Phonetix", sb.toString())
                }
                main.post {
                    if (moving && overlay.inMotion) overlay.motionMeasured(moved, style0)
                    else overlay.endMotion(moved, style0)
                    android.util.Log.d(
                        "Phonetix",
                        "follow=${took}ms lines=${planned.size} boxes=${moved.size} moving=$moving",
                    )
                }
                return
            }
            android.util.Log.d("Phonetix", "follow gave up: $why")
            // Anything unexpected and the screen is simply read again.
            cachedPlan = emptyList()
            scrollOnly = false
            schedule(0L)
            return
        }

        val boxes = ArrayList<WordBox>(planned.size * 2)
        var stale = false
        for (p in planned) {
            val rects = charRects(p.node, p.from, p.length) ?: continue
            // refreshWithExtraData just refreshed the node, so this is the text as it is
            // now. If it has moved on, the picks describe a screen that is gone.
            if (reuse && p.node.text?.toString() != p.text) { stale = true; break }
            val before = boxes.size
            Transcriber.boxes(p.picks, rects, p.from, boxes)
            // Remember where this line was when its characters were measured, so a scroll
            // can carry its words rather than measuring them again.
            val at = android.graphics.Rect()
            p.node.getBoundsInScreen(at)
            p.measuredAt = at
            // Drop anything the node's ancestors clip away rather than painting a word that
            // is behind something else.
            var w = before
            for (i in before until boxes.size) {
                val r = boxes[i].rect
                val inside = r.left >= p.clip.left - 1 && r.top >= p.clip.top - 1 &&
                    r.right <= p.clip.right + 1 && r.bottom <= p.clip.bottom + 1
                if (inside && !covered(r, p.exit)) { boxes[w] = boxes[i]; w++ }
            }
            while (boxes.size > w) boxes.removeAt(boxes.size - 1)
            p.boxes = boxes.subList(before, boxes.size).toList()
        }
        if (stale) {
            cachedPlan = emptyList()
            scrollOnly = false
            schedule(0L)
            return
        }
        // Nodes go stale when the screen really did change under a scroll; falling back to
        // a full read is cheaper than showing nothing.
        if (reuse && boxes.isEmpty()) {
            cachedPlan = emptyList()
            scrollOnly = false
            schedule(0L)
            return
        }

        // Colours come from the screen, because accessibility carries none - and only ever
        // from a frame taken while our own transcriptions are hidden.
        //
        // Every earlier attempt sampled a frame that already had them in it and read its own
        // paint back: first as gold on cream, then, when the words were excluded from the
        // sample, as nothing at all, because on a line holding one word the only text there
        // is the word we covered. Stepping aside for a single frame removes the whole class
        // of problem instead of guarding against it.
        val tb = android.os.SystemClock.uptimeMillis()
        if (pkg != colorsFor) {
            wordColors.clear()
            colorAttempted.clear()
            colorsFor = pkg
            cleanFrameAt = 0L
        }

        // Only words never tried on this screen are worth stepping aside for. Without that
        // the overlay hid itself every second and a half for the whole life of a page,
        // which cost more than the colours were worth and left flings barely drawn.
        val untried = boxes.filter {
            val key = it.word.lowercase()
            key !in wordColors && key !in colorAttempted
        }
        if (untried.isNotEmpty()) requestCleanFrame(untried)

        val painted = boxes.map {
            val c = wordColors[it.word.lowercase()]
            if (c == null) it else it.copy(background = c.background, ink = c.ink)
        }
        onScreenBoxes = painted.map { it.rect }

        // One line per pass naming every transcription and where it sits, so a test can
        // assert that a word moved exactly as far as the text under it did.
        if (BuildConfig.DEBUG) {
            val sb = StringBuilder("BOXES ")
            sb.append(android.os.SystemClock.uptimeMillis()).append(' ').append(pkg).append(' ')
            for ((i, b) in painted.withIndex()) {
                sb.append(b.word).append('#').append(i).append(':')
                    .append(b.rect.left.toInt()).append(',')
                    .append(b.rect.top.toInt()).append(',')
                    .append(b.rect.right.toInt()).append(',')
                    .append(b.rect.bottom.toInt()).append(',')
                    .append(Integer.toHexString(b.background)).append(',')
                    .append(Integer.toHexString(b.ink)).append(',')
                    .append(if (b.background != 0 && b.ink != 0) 1 else 0).append(' ')
            }
            android.util.Log.d("Phonetix", sb.toString())
        }

        val colourMs = android.os.SystemClock.uptimeMillis() - tb
        val t2 = tb
        val style = settings.style
        main.post {
            val t3 = android.os.SystemClock.uptimeMillis()
            overlay.render(painted, style)
            android.util.Log.d(
                "Phonetix",
                "plan=${t1 - t0}ms (ipc=${stats.ipcNs / 1_000_000}ms in ${stats.calls} calls, ours=${stats.computeNs / 1_000_000}ms) nodes=${MAX_NODES - budget.nodes} " +
                    "bounds=${t2 - t1}ms calls=${planned.size} colour=${colourMs}ms " +
                    "render=${android.os.SystemClock.uptimeMillis() - t3}ms boxes=${boxes.size} " +
                    "coloured=${painted.count { it.background != 0 }}",
            )
        }
    }

    /**
     * Hide, capture, read the colours of these words, show again.
     *
     * The overlay is down for one capture. It is throttled, and only asked for when a word
     * on screen has no colour yet, so a screen whose words are already known never blinks.
     */
    private fun requestCleanFrame(boxes: List<WordBox>) {
        if (capturing) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - cleanFrameAt < CLEAN_FRAME_GAP_MS) return
        capturing = true
        cleanFrameAt = now
        val wanted = boxes.toList()
        val hiddenAt = now
        main.post { overlay.hideNow() }
        io.postDelayed({
            sampler.invalidateFrame()
            sampler.refreshIfStale(force = true)
            io.postDelayed({
                // Only a frame taken after the overlay went down can be trusted; anything
                // older still has our transcriptions in it.
                if (!sampler.hasFrame || sampler.frameAt < hiddenAt) {
                    capturing = false
                    schedule(0L)
                    return@postDelayed
                }
                // The frame now holds the app's own text where our transcriptions were.
                for (b in wanted) {
                    val key = b.word.lowercase()
                    colorAttempted.add(key)
                    if (key in wordColors) continue
                    // The word's own pixels first. A short word carries few of them, so if
                    // that is too little to tell ink from surface, widen along the line -
                    // the neighbouring words are the same colour as this one.
                    val wide = RectF(
                        b.rect.left - b.rect.width(), b.rect.top,
                        b.rect.right + b.rect.width(), b.rect.bottom,
                    )
                    val c = sampler.sample(b.rect) ?: sampler.sample(wide)
                    if (c != null) wordColors[key] = c
                }
                capturing = false
                schedule(0L)
            }, 320)
        }, 80)
    }

    private class Planned(
        val node: AccessibilityNodeInfo,
        val from: Int,
        val length: Int,
        val picks: List<Pick>,
        /** What the node said when these picks were made; a reused node whose text has
         *  changed would otherwise be painted with the previous screen's transcriptions. */
        val text: String,
        /** Everything the node's ancestors clip it to. */
        val clip: android.graphics.Rect,
        /** Where this node's subtree ends in draw order; anything that starts after it is
         *  painted on top of it. */
        val exit: Int,
    ) {
        /** Where the node sat when its characters were measured, and what came out. A
         *  scroll moves the line without moving the characters inside it, so the words can
         *  be carried by the difference instead of being measured again. */
        var measuredAt: android.graphics.Rect? = null
        var boxes: List<WordBox> = emptyList()
    }

    /** A node's box and where it sits in draw order, for working out what covers what. */
    private class Painted(val enter: Int, val rect: android.graphics.Rect)

    /**
     * Whether a box is really a backdrop rather than something that hides a word.
     *
     * Most of a screen's tree is containers that span it, and a scrolling list is itself
     * drawn after plenty of them. Counting those as covering anything would suppress every
     * transcription on the screen, so only boxes small enough to be actual furniture - a
     * toolbar, a sheet, a floating button - are treated as able to hide a word.
     */
    private fun isBackdrop(r: android.graphics.Rect): Boolean {
        val dm = resources.displayMetrics
        val screen = dm.widthPixels.toLong() * dm.heightPixels.toLong()
        if (screen <= 0) return false
        return r.width().toLong() * r.height().toLong() * 10 >= screen * 6
    }

    /** Caps so one very dense screen cannot stall a pass. */
    /** Split of where a pass actually goes: calls into the other app, versus our own work. */
    private class Stats(var ipcNs: Long = 0, var computeNs: Long = 0, var calls: Int = 0)

    private class Budget(
        /** Depth-first position, which is also paint order: later means on top. */
        var order: Int = 0,
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
        inherited: android.graphics.Rect,
        painted: MutableList<Painted>,
    ) {
        if (node == null || budget.nodes <= 0 || budget.words <= 0 || budget.visits <= 0) return
        budget.visits--
        val enter = budget.order++
        var mark = System.nanoTime()
        val visible = node.isVisibleToUser
        var plannedHere = -1
        val text = if (visible) node.text?.toString() else null
        stats.ipcNs += System.nanoTime() - mark
        stats.calls++
        if (!visible) return

        // Bounds travel with the node, so narrowing the clip on the way down costs nothing
        // and is the only thing that knows a word has scrolled under a toolbar: a node can
        // be "visible to user" while the part of it holding the word is covered.
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val clip = android.graphics.Rect(inherited)
        if (!bounds.isEmpty && !clip.intersect(bounds)) return

        // Anything with its own area can end up covering what was drawn before it. A layer
        // spanning most of the screen is a backdrop rather than something that hides a
        // word, so it is not counted.
        if (!bounds.isEmpty && !isBackdrop(bounds)) {
            painted.add(Painted(enter, android.graphics.Rect(bounds)))
        }

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
                plannedHere = out.size
                out.add(Planned(node, from, to - from + 1, picks, text, android.graphics.Rect(clip), 0))
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
            plan(child, t, out, budget, stats, clip, painted)
            if (budget.nodes <= 0 || budget.words <= 0 || budget.visits <= 0) break
        }
        // The subtree is finished, so record where it ended: whatever is visited from here
        // on is painted over this node.
        if (plannedHere >= 0 && plannedHere < out.size) {
            val was = out[plannedHere]
            out[plannedHere] = Planned(was.node, was.from, was.length, was.picks, was.text, was.clip, budget.order)
        }
    }

    /**
     * The strip of a line that actually holds text: as wide as the line, as tall as its
     * characters, which is what a transcription has to match.
     */
    private fun textBand(p: Planned): RectF? {
        if (p.boxes.isEmpty()) return null
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (b in p.boxes) {
            if (b.rect.top < top) top = b.rect.top
            if (b.rect.bottom > bottom) bottom = b.rect.bottom
        }
        if (bottom <= top) return null
        val pad = (bottom - top) * 0.15f
        return RectF(
            p.clip.left.toFloat(),
            (top - pad).coerceAtLeast(p.clip.top.toFloat()),
            p.clip.right.toFloat(),
            (bottom + pad).coerceAtMost(p.clip.bottom.toFloat()),
        )
    }

    /**
     * Whether something painted after this word's node lies over it.
     *
     * A word can be perfectly visible as far as its own view is concerned and still be
     * hidden behind a toolbar the list scrolls under: the bar is a sibling drawn later, not
     * an ancestor clipping anything, so neither the node's own bounds nor isVisibleToUser
     * says a word about it. Draw order does. Anything whose subtree begins after this
     * node's ended is on top, and a word it overlaps is not on screen to be replaced.
     */
    private fun covered(word: RectF, exit: Int): Boolean {
        val w = android.graphics.Rect(
            word.left.toInt(), word.top.toInt(), word.right.toInt(), word.bottom.toInt(),
        )
        for (p in cachedPainted) {
            if (p.enter < exit) continue
            if (android.graphics.Rect.intersects(p.rect, w)) return true
        }
        return false
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
        // A scroll is followed rather than waited out, so this only spaces the passes to
        // about one a frame.
        const val GAP_SCROLL_MS = 16L
        /** No scroll event for this long and the screen is considered to have stopped. */
        const val STILL_MS = 120L
        /** How rarely the overlay may step aside to be able to read a colour. */
        const val CLEAN_FRAME_GAP_MS = 1500L
        const val MAX_NODES = 120
        const val MAX_VISITS = 400
        const val MAX_WORDS = 60
        const val MAX_TEXT = 2000
    }
}
