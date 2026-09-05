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
    /** When the screen was last seen to move, whether it said so or was measured moving. */
    @Volatile private var lastMotionAt = 0L
    /** Where the previous full read found each line, so this one can tell whether the page
     *  has moved even when nothing announced that it had. */
    @Volatile private var previousTops: Map<String, Int> = emptyMap()
    @Volatile private var following = false

    // What the last full pass found. A scroll moves these words without changing them, so
    // the next pass can ask them directly for their new positions instead of walking the
    // whole tree again - which is sixty-odd calls into the other app, against five.
    @Volatile private var cachedPlan: List<Planned> = emptyList()
    @Volatile private var cachedPackage: String? = null
    @Volatile private var cachedPainted: List<Painted> = emptyList()
    @Volatile private var scrollOnly = false
    /** The frequency the cached plan was made for. */
    @Volatile private var plannedDensity = -1
    /** When the screen was last read in full, rather than followed. */
    @Volatile private var lastFullReadAt = 0L
    /** Which line is next in line to be asked whether it is still itself. */
    @Volatile private var verifyFrom = 0
    /** Whether this screen has been seen handing a row to a different line. */
    @Volatile private var recycling = false
    /** The last measured shift and when, and the speed they give, in pixels a millisecond. */
    @Volatile private var lastShiftY = 0f
    @Volatile private var lastShiftAt = 0L
    @Volatile private var speedY = 0f

    // Colours per line of text rather than per app: a heading and a paragraph in the same
    // app are rarely the same colour, and averaging them gives a transcription that matches
    // neither. Keyed by the text itself so a line keeps its colours while it is on screen,
    // and cleared when the app changes.
    private val lineColors = HashMap<String, WordColors>(64)
    /** How often a line has been looked for without being read, and when it was last
     *  tried, so the overlay steps aside a bounded number of times for a line it cannot
     *  resolve - and so a screen that failed while the device was busy is tried again later
     *  rather than being left without colours for as long as it is open. */
    private class Attempts(var count: Int, var at: Long)
    private val colorTries = HashMap<String, Attempts>(64)
    @Volatile private var colorsFor: String? = null
    /** The page's own colours, for a line whose own could not be read. */
    @Volatile private var screenColors: WordColors? = null
    /** What was read for the apps seen before this one. */
    private class Remembered(val lines: Map<String, WordColors>, val page: WordColors?)
    private val colorsByPackage = LinkedHashMap<String, Remembered>()
    /** Set while the overlay is briefly down so a capture can see the text underneath. */
    @Volatile private var capturing = false
    @Volatile private var capturingSince = 0L
    /** When the overlay actually came down for a capture, as opposed to being asked to. */
    @Volatile private var hiddenAt = 0L
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
        // The card fetches diagrams over the network, and a request to a host that does not
        // answer holds its thread for as long as the connection takes to give up. Sharing a
        // thread with the screen captures meant one unanswered fetch stopped the colours
        // being read at all, and with no colours nothing was drawn.
        val netThread = HandlerThread("phonetix-net").apply { start() }
        val net = Handler(netThread.looper)
        speaker = Speaker(this)
        tooltip = TooltipController(this, speaker) { r -> net.post(r) }
        overlay = OverlayController(this) { box -> main.post { tooltip.show(box) } }
        IpaSymbols.ensureLoaded(this)
        // Positions are the whole product here, and a cached position is a wrong one. The
        // platform keeps a copy of the node tree for a service to read cheaply, and while a
        // page is moving that copy is a picture of where the words used to be: lines came
        // back reporting they had not moved at all, or half of them did, which read as a
        // page that was not scrolling and left the transcriptions standing still on one that
        // was. Reading through to the app every time costs a few milliseconds a line and is
        // the only way to be told the truth.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching { setCacheEnabled(false) }
                .onFailure { android.util.Log.w("Phonetix", "could not turn the node cache off", it) }
        }
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
            lastMotionAt = android.os.SystemClock.uptimeMillis()
            scrollOnly = scrollOnly || cachedPlan.isNotEmpty()
            startFollowing()
            return
        }
        // A scroll event is not the only way a page moves, and waiting for one is why a
        // moving page could sit unfollowed: a view scrolled from code reports itself as a
        // content change, and so does any list whose toolkit describes movement that way.
        // A change to a screen already read is answered by asking the lines we know where
        // they are now, which costs single milliseconds against the hundreds a full read of
        // the tree costs, and falls back to a full read the moment the lines turn out to be
        // different ones.
        //
        // It goes through the same self-driving loop as a scroll rather than through a
        // scheduled pass, because these events arrive faster than any pass completes: each
        // one cancelled the pass the one before it asked for, and a page scrolled from code
        // was therefore never read at all while it moved.
        lastMotionAt = android.os.SystemClock.uptimeMillis()
        // A window appearing or going away can mean a different app is in front, and a
        // follow pass does not look at which one it is - it works from the lines it already
        // holds. So this one is always answered by reading the screen properly.
        val windowChanged = event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!windowChanged && cachedPlan.isNotEmpty() && cachedPackage != null) {
            scrollOnly = true
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
            val since = android.os.SystemClock.uptimeMillis() - lastMotionAt
            // Kept running well past the last thing that announced itself. A page scrolling
            // says so only a few times a second, and a loop that stopped between those
            // announcements stood still for half a second at a time in the middle of a
            // movement. Each pass is a few milliseconds, so waiting out the quiet is cheap;
            // it is the standing still that is expensive.
            if (since > FOLLOW_IDLE_MS) {
                following = false
                // One last read, and a full one. Following carries the words by how far the
                // lines report they have moved, and if that has gone wrong - a list that
                // recycles its rows, a line that answered for a different line - the error
                // stays on screen until something happens to ask again, which in an app that
                // says nothing while it is idle is never. Reading the screen properly is what
                // ends a movement.
                scrollOnly = false
                cachedPlan = emptyList()
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
        // The loop's own next pass is among the callbacks cleared below, so it is told it
        // has stopped; otherwise it would never start again, having never noticed it died.
        following = false
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
        val sinceFull = android.os.SystemClock.uptimeMillis() - lastFullReadAt
        val settled = android.os.SystemClock.uptimeMillis() - lastMotionAt > STILL_MS
        val overdue = sinceFull > FULL_READ_MS && settled || sinceFull > FULL_READ_MOVING_MS
        // Following needs the lines, which are already in hand; it does not need the window
        // they are in. Asking for the whole window costs thirty to ninety milliseconds while
        // the app is busy scrolling, and every one of those is a millisecond the positions
        // are out of date by the time they are drawn. The window is fetched on the full read
        // that follows shortly after, which is what would notice the app had changed.
        val followOnly = scrollOnly && cachedPlan.isNotEmpty() && cachedPackage != null &&
            settings.density == plannedDensity && !overdue &&
            SettingsStore.allows(cachedPackage) && Dictionary.ready
        val root = if (followOnly) null else {
            val askedRoot = android.os.SystemClock.uptimeMillis()
            val fetched = rootInActiveWindow ?: run { main.post { overlay.hideNow() }; return }
            val rootMs = android.os.SystemClock.uptimeMillis() - askedRoot
            if (BuildConfig.DEBUG && rootMs > 30) {
                android.util.Log.d("Phonetix", "ROOT took ${rootMs}ms")
            }
            fetched
        }
        if (root != null && (!SettingsStore.allows(root.packageName?.toString()) || !Dictionary.ready)) {
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

        val pkg = if (root != null) root.packageName?.toString() else cachedPackage
        val t0 = android.os.SystemClock.uptimeMillis()

        // A scroll moved the words it did not change, so the nodes found last time are
        // still the right ones and only their positions need asking for again.
        // The plan is the answer to a question that includes the frequency setting, so a
        // reader who moves the bar has to be answered afresh: reusing the plan meant the
        // words stayed exactly as they were and the setting appeared to do nothing.
        // A full read is also what finds new words and reads colours, so following cannot be
        // allowed to go on for ever: a screen that keeps announcing changes without moving
        // would otherwise be followed indefinitely and never read again. It waits for the
        // movement to stop, though, because reading the whole tree takes a few hundred
        // milliseconds during which nothing is followed at all - a visible stall in the
        // middle of a scroll. A long movement is interrupted for one anyway, since words
        // scrolling in have never been read.
        val reuse = followOnly || (scrollOnly && pkg != null && pkg == cachedPackage &&
            cachedPlan.isNotEmpty() && settings.density == plannedDensity && !overdue)
        val planned: List<Planned>
        val budget = Budget()
        val stats = Stats()
        if (reuse) {
            planned = cachedPlan
        } else {
            val before = HashMap<String, Int>(cachedPlan.size)
            for (p in cachedPlan) p.measuredAt?.let { before[colorKey(p.text)] = it.top }
            val fresh = ArrayList<Planned>(16)
            val full = android.graphics.Rect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
            val seen = ArrayList<Painted>(128)
            plan(root!!, Transcriber(settings.density), fresh, budget, stats, full, seen)
            planned = fresh
            cachedPlan = fresh
            cachedPackage = pkg
            cachedPainted = seen
            previousTops = before
            plannedDensity = settings.density
            lastFullReadAt = android.os.SystemClock.uptimeMillis()
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
            /** Whether the lines are anywhere other than where they were last read. */
            var shifted = false
            var clipped = 0
            var hidden = 0
            var unreadable = 0
            /** When the positions in this pass were actually read from the app. */
            var readAt = tf

            // The words of a scrolling page all move by the same amount, so a few lines are
            // asked where they are and the rest are carried by what those lines report.
            // Asking every line meant two round trips into the other app per line, and a
            // round trip into an app that is busy laying out a scroll costs tens of
            // milliseconds: a pass that takes seven when the screen is still took over seven
            // hundred in the middle of the movement it was meant to follow.
            //
            // Several lines are asked rather than one because a single answer can be a lie
            // that looks like a fact - see the shift below - and the answer they agree on is
            // taken.
            val usable = planned.filter { p ->
                val at = p.measuredAt
                at != null && p.boxes.isNotEmpty() &&
                    // Whole when they were measured, so there is an edge of them to compare.
                    at.top > p.viewport.top + 1 && at.bottom < p.viewport.bottom - 1
            }.ifEmpty { planned.filter { it.measuredAt != null && it.boxes.isNotEmpty() } }
            val anchors = when {
                usable.size <= ANCHORS -> usable
                else -> {
                    val step = (usable.size - 1).toFloat() / (ANCHORS - 1)
                    (0 until ANCHORS).map { usable[(it * step).toInt()] }
                }
            }
            /** Each anchor's answer, and the moment it gave it. */
            val shifts = ArrayList<Triple<Float, Float, Long>>(ANCHORS)
            for (a in anchors) {
                val was = a.measuredAt ?: continue
                if (!a.node.refresh()) { gone++; continue }
                if (a.node.text?.toString() != a.text) { gone++; continue }
                val now = android.graphics.Rect()
                a.node.getBoundsInScreen(now)
                val readingAt = android.os.SystemClock.uptimeMillis()
                if (now.isEmpty) { gone++; continue }
                alive++
                val shift = shiftOf(was, now, a.viewport)
                if (shift == null) { unreadable++; continue }
                shifts.add(Triple(shift.first, shift.second, readingAt))
            }
            // The middle answer, dated by the moment it was given. Not the freshest: on a
            // list, the row that answers last may be a row that has just been handed to a
            // different line, and its "movement" is the jump from one end of the screen to
            // the other rather than the page's. Two of three genuine answers outvote it. Not
            // the average either, for the same reason: one jump would drag it.
            var shiftX = 0f
            var shiftY = 0f
            var agreed = shifts.isNotEmpty()
            if (agreed) {
                val middle = shifts.sortedBy { it.second }[shifts.size / 2]
                shiftX = middle.first
                shiftY = middle.second
                readAt = middle.third
                // A disagreement far larger than the movement can explain is not one page
                // scrolling: it is a list inside a page, or a bar that stays put while the
                // text moves under it, and then there is nothing for one measurement to
                // stand for and each line is asked about itself.
                val spread = shifts.maxOf { it.second } - shifts.minOf { it.second }
                if (spread > INDEPENDENT_PX) agreed = false
            }
            if (alive > 0 && (shiftY != 0f || shiftX != 0f)) shifted = true
            // How fast the page is going, from this reading against the one before. The
            // full read below needs it: it takes tens of milliseconds, and its first line is
            // measured well before its last.
            if (agreed && lastShiftAt > 0L && readAt > lastShiftAt) {
                val dt = (readAt - lastShiftAt).toFloat()
                if (dt in 1f..300f) speedY = (shiftY - lastShiftY) / dt
            }
            lastShiftY = shiftY
            lastShiftAt = readAt

            // A few lines are asked whether they are still the lines they were, a couple
            // each pass and a different couple next time, so every one of them is checked
            // within a few frames. A list that recycles its rows hands the same node to a
            // different line, and carrying that line's old words by the anchors' distance is
            // how transcriptions end up scattered over text they have nothing to do with -
            // words that are not on the screen at all any more.
            // The ones nearest the edge the content is leaving by, because that is where a
            // list hands a row to a new line: scrolling down, the rows at the top go first.
            // The rest are taken in turn, so every line is checked within a few frames.
            val checks = ArrayList<Planned>(VERIFY_PER_PASS + VERIFY_AT_EDGE)
            if (planned.isNotEmpty()) {
                val leaving = planned
                    .filter { it.measuredAt != null && it.boxes.isNotEmpty() }
                    .sortedBy { line ->
                        val top = line.measuredAt?.top ?: 0
                        if (shiftY <= 0f) top else -top
                    }
                checks.addAll(leaving.take(VERIFY_AT_EDGE))
                for (i in 0 until minOf(VERIFY_PER_PASS, planned.size)) {
                    checks.add(planned[(verifyFrom + i) % planned.size])
                }
                verifyFrom = (verifyFrom + VERIFY_PER_PASS) % planned.size
            }
            val verified = HashSet<Planned>(checks.size + anchors.size)
            verified.addAll(anchors)
            for (c in checks) {
                if (c in verified) continue
                if (!c.node.refresh() || c.node.text?.toString() != c.text) {
                    // A row that now says something else is a row a list has handed to
                    // another line. From here on this screen is treated as one that recycles.
                    recycling = true
                    ok = false
                    why = "a line is not the line it was"
                    break
                }
                verified.add(c)
            }

            for (p in planned) {
                if (!ok) break
                // On a screen that recycles its rows, a line nobody has asked about this pass
                // is a line that may already belong to different words. Its transcriptions
                // wait for the movement to end rather than being carried on a guess, which is
                // how they ended up scattered over text they had nothing to do with.
                if (recycling && p !in verified) continue
                val was = p.measuredAt
                // A line whose words were all clipped away contributes nothing, which is
                // normal and not a reason to abandon following the rest of them.
                if (was == null || p.boxes.isEmpty()) continue
                // The same rule the full read follows: a line still waiting for its colours
                // is not drawn in a palette of ours. Its colours may also have arrived since
                // it was planned, in which case they are put on now - otherwise a line that
                // came into view during a scroll would keep the absence it was born with
                // until the movement stopped.
                val key = colorKey(p.text)
                val colours = lineColors[key]
                if (colours != null && p.boxes.first().background != colours.background) {
                    p.boxes = p.boxes.map {
                        it.copy(background = colours.background, ink = colours.ink)
                    }
                }
                if (colours == null && p.boxes.first().background == 0 &&
                    (colorTries[key]?.count ?: 0) < COLOR_TRIES
                ) {
                    continue
                }
                var dx = shiftX
                var dy = shiftY
                if (!agreed) {
                    // A line that has scrolled off is recycled and can no longer be asked
                    // anything - that is ordinary during a fling, and abandoning the whole
                    // pass for it left the rest of the screen unfollowed and falling back to
                    // a full read that costs hundreds of milliseconds. Skip the line, keep
                    // the others.
                    if (!p.node.refresh()) { gone++; continue }
                    if (p.node.text?.toString() != p.text) { gone++; continue }
                    val now = android.graphics.Rect()
                    p.node.getBoundsInScreen(now)
                    readAt = android.os.SystemClock.uptimeMillis()
                    if (now.isEmpty) { gone++; continue }
                    alive++
                    val shift = shiftOf(was, now, p.viewport)
                    if (shift == null) { unreadable++; continue }
                    dx = shift.first
                    dy = shift.second
                    if (dy != 0f || dx != 0f) shifted = true
                }
                for (b in p.boxes) {
                    val r = RectF(b.rect.left + dx, b.rect.top + dy, b.rect.right + dx, b.rect.bottom + dy)
                    // Against the window the line scrolls inside, which does not move when
                    // its contents do. Testing a moved word against the line's own old
                    // rectangle instead meant every word left it as soon as the page moved,
                    // and the whole screen was declared lost after a hundred pixels of
                    // scrolling - which is exactly when following it mattered.
                    val inside = r.top >= p.viewport.top - 1 && r.bottom <= p.viewport.bottom + 1 &&
                        r.left >= p.viewport.left - 1 && r.right <= p.viewport.right + 1
                    if (!inside) { clipped++; continue }
                    if (covered(r, p.exit)) { hidden++; continue }
                    moved.add(b.copy(rect = r))
                }
            }
            // Lines that are still there but whose words have all fallen outside them: the
            // plan describes a screen that has moved on, and following it would take every
            // transcription off the screen. That happened after a jump, where a surviving
            // line's words all landed outside the clip it was measured in, and the overlay
            // went blank on a page full of words.
            if (ok && recycling && moved.isEmpty()) {
                // Nothing verified yet on a recycling screen is not a failure; the next pass
                // verifies the next lines, and the full read at the end settles it.
                ok = true
            } else if (ok && moved.isEmpty() && planned.any { it.boxes.isNotEmpty() }) {
                ok = false
                why = "the follow kept no words at all ($clipped clipped, $hidden covered, $unreadable unreadable)"
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
                val took = android.os.SystemClock.uptimeMillis() - tf
                // Measured movement is movement, whatever the app called it. A page scrolled
                // from code sends no scroll event, so this is where such a movement is
                // noticed, and noticing it is what keeps the loop below running and the words
                // riding on the layer rather than being re-read once a second.
                if (shifted) {
                    lastMotionAt = android.os.SystemClock.uptimeMillis()
                    if (!overlay.inMotion) main.post { overlay.beginMotion() }
                    startFollowing()
                }
                // Still moving: hand the measurement to the layer, which corrects both the
                // position and the speed it is carrying them at. Once the scrolling has
                // stopped, put the tappable windows back where the words actually are.
                val moving = following && android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS
                if (BuildConfig.DEBUG) {
                    // Stamped with when the positions were read, not with when the line was
                    // written: they describe the page as it was at that instant.
                    val sb = StringBuilder("BOXES ")
                        .append(readAt).append(' ')
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
                    if (moving && overlay.inMotion) overlay.motionMeasured(moved, readAt)
                    else overlay.endMotion(moved)
                    android.util.Log.d(
                        "Phonetix",
                        "follow=${took}ms lines=${planned.size} boxes=${moved.size} moving=$moving " +
                            "shift=$shiftY agreed=$agreed alive=$alive gone=$gone clipped=$clipped",
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
        /** When the lines of this pass were measured; the page may move while it runs. */
        var readAt = t1
        /** And when each of them was, since they are not measured together. */
        val lineReadAt = HashMap<Planned, Long>(planned.size)
        for (p in planned) {
            // Where the characters of this line sit within it is a property of the line, not
            // of where the page has scrolled to, so a line already measured once is placed
            // from what it said then. Asking again is the single most expensive thing here -
            // it makes the other app lay the text out again, a fifth of a second for a
            // screenful - and it is the reason a page that scrolled a new line into view
            // stalled at exactly the moment it needed to be quick.
            val at = android.graphics.Rect()
            p.node.getBoundsInScreen(at)
            readAt = android.os.SystemClock.uptimeMillis()
            lineReadAt[p] = readAt
            val remembered = charLayouts[layoutKey(p)]
            val placed = placeRemembered(remembered, at, p.viewport)
            // Asking the app where its characters are makes it lay the text out again, and a
            // list being laid out while it scrolls clamps its own scroll position and jumps.
            // A line that cannot be placed from memory is therefore left alone until the
            // movement stops; it is one of the part-hidden lines at the edges of the screen,
            // and it comes back with the next full read.
            val moving = android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS
            if (placed == null && moving) { p.boxes = emptyList(); continue }
            val rects = placed
                ?: charRects(p.node, p.from, p.length)?.also { fresh ->
                    remember(p, at, fresh)
                }
                ?: continue
            // refreshWithExtraData just refreshed the node, so this is the text as it is
            // now. If it has moved on, the picks describe a screen that is gone.
            if (reuse && p.node.text?.toString() != p.text) { stale = true; break }
            val before = boxes.size
            Transcriber.boxes(p.picks, rects, p.from, boxes)
            // Remember where this line was when its characters were measured, so a scroll
            // can carry its words rather than measuring them again.
            if (remembered != null) p.node.getBoundsInScreen(at)
            p.measuredAt = at
            // Drop anything the node's ancestors clip away rather than painting a word that
            // is behind something else.
            var w = before
            for (i in before until boxes.size) {
                val r = boxes[i].rect
                val inside = r.left >= p.viewport.left - 1 && r.top >= p.viewport.top - 1 &&
                    r.right <= p.viewport.right + 1 && r.bottom <= p.viewport.bottom + 1
                if (inside && !covered(r, p.exit)) { boxes[w] = boxes[i]; w++ }
            }
            while (boxes.size > w) boxes.removeAt(boxes.size - 1)
            p.boxes = boxes.subList(before, boxes.size).toList()
        }
        // Every line of a full read is measured at a different moment, and on a moving page
        // that is a screen that never existed: the first line belongs to where the page was
        // fifty milliseconds ago and the last to where it is now. Each is carried forward at
        // the speed the following measured, to the moment the reading finished.
        if (!reuse && speedY != 0f &&
            android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS
        ) {
            for (p in planned) {
                val when0 = lineReadAt[p] ?: continue
                val ahead = (readAt - when0).coerceIn(0L, 200L).toFloat() * speedY
                if (ahead == 0f) continue
                p.boxes = p.boxes.map {
                    val r = it.rect
                    it.copy(rect = RectF(r.left, r.top + ahead, r.right, r.bottom + ahead))
                }
            }
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
        // Colours are kept per app rather than thrown away whenever another one is in front
        // for a moment. A notification shade or a system dialog counts as a different app
        // here, and forgetting a screen's colours for it meant reading them all again - and
        // painting a screenful in the fallback palette until they arrived.
        if (pkg != colorsFor) {
            colorsFor?.let { previous ->
                if (lineColors.isNotEmpty() || screenColors != null) {
                    colorsByPackage[previous] = Remembered(HashMap(lineColors), screenColors)
                }
            }
            val kept = colorsByPackage[pkg]
            lineColors.clear()
            colorTries.clear()
            if (kept != null) lineColors.putAll(kept.lines)
            screenColors = kept?.page
            colorsFor = pkg
            cleanFrameAt = 0L
            recycling = false
            while (colorsByPackage.size > REMEMBERED_APPS) {
                colorsByPackage.remove(colorsByPackage.keys.first())
            }
        }

        // A line is the unit, not a word. One word carries a few dozen glyph pixels and a
        // short one carries none worth the name, which is how words ended up with no colour
        // and wearing the palette below instead; a line carries thousands, and every word on
        // it is drawn in the colour of its neighbours. Keying on the line's own text also
        // keeps a heading apart from the paragraph under it, and one message in a
        // conversation apart from the next, which a single colour per app cannot do.
        val nowForColours = android.os.SystemClock.uptimeMillis()
        val unread = planned.filter {
            val key = colorKey(it.text)
            val tried = colorTries[key]
            it.boxes.isNotEmpty() && key !in lineColors &&
                (tried == null || tried.count < COLOR_TRIES ||
                    nowForColours - tried.at > COLOR_RETRY_MS)
        }
        // Only lines never read on this screen are worth stepping aside for. Without that
        // the overlay hid itself every second and a half for the whole life of a page,
        // which cost more than the colours were worth and left flings barely drawn.
        if (unread.isNotEmpty()) {
            requestCleanFrame(unread.map { colorKey(it.text) to colorRect(it) })
        }

        // The colours ride on the boxes the lines keep, so that following a scroll carries
        // them too: re-reading positions does not re-read colours, and a set of words that
        // lost its colours the moment the screen moved was the flicker between a scrolling
        // transcription and a still one.
        // A line whose colours have not been read yet waits rather than being painted in a
        // palette of ours: a screen that appears in gold on brown and corrects itself a
        // moment later is seen as the correction, which is what "it is orange first" meant.
        // The wait ends after a bounded number of attempts, so an app that can never be
        // captured - one that forbids screenshots - is still transcribed shortly after.
        //
        // The colours are kept on the line's own boxes, so that following a scroll carries
        // them; only the painting is withheld, because a line dropped from the plan would be
        // missing from the next pass too rather than appearing the moment its colours come.
        val capturingNow = capturing &&
            android.os.SystemClock.uptimeMillis() - capturingSince < CAPTURE_TIMEOUT_MS
        val painted = ArrayList<WordBox>(boxes.size)
        for (p in planned) {
            val key = colorKey(p.text)
            // The page's own colours stand in only once this line has been given up on.
            // Using them straight away would be quicker and wrong: every line would wear the
            // page's ink, and a heading, a quotation and a highlighted phrase all differ
            // from it - which is the whole reason colours are read per line.
            val givenUp = (colorTries[key]?.count ?: 0) >= COLOR_TRIES && !capturingNow
            val c = lineColors[key] ?: if (givenUp) screenColors else null
            if (c != null) p.boxes = p.boxes.map { it.copy(background = c.background, ink = c.ink) }
            if (c != null || givenUp) painted.addAll(p.boxes)
        }

        // One line per pass naming every transcription and where it sits, so a test can
        // assert that a word moved exactly as far as the text under it did.
        if (BuildConfig.DEBUG) {
            val sb = StringBuilder("BOXES ")
            sb.append(readAt).append(' ').append(pkg).append(' ')
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

        // A full read is also a measurement of where the lines are, so it can see for itself
        // that the page has moved since the last one. Without this the loop stopped every
        // time the plan had to be rebuilt and waited for an event to start it again, and a
        // page scrolled from code announces itself only a few times a second: the overlay
        // redrew twice during a movement it should have followed sixty times.
        if (!reuse) {
            var movedSince = false
            for (p in planned) {
                val was = previousTops[colorKey(p.text)] ?: continue
                val at = p.measuredAt ?: continue
                if (kotlin.math.abs(at.top - was) > 1) { movedSince = true; break }
            }
            if (movedSince) lastMotionAt = android.os.SystemClock.uptimeMillis()
        }
        if (planned.isNotEmpty()) {
            scrollOnly = true
            if (android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS) startFollowing()
        }

        val colourMs = android.os.SystemClock.uptimeMillis() - tb
        val t2 = tb
        main.post {
            val t3 = android.os.SystemClock.uptimeMillis()
            overlay.render(painted)
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
    private fun requestCleanFrame(lines: List<Pair<String, RectF>>) {
        val now = android.os.SystemClock.uptimeMillis()
        // A capture that never came back must not shut this for good, and must not leave the
        // words waiting for colours that are not coming either.
        if (capturing && now - capturingSince < CAPTURE_TIMEOUT_MS) return
        // The first reading for an app is not made to wait: until it lands there are no
        // colours at all for this screen, and the words are either withheld or painted in a
        // palette that is not the page's.
        val nothingKnown = lineColors.isEmpty() && screenColors == null
        if (!nothingKnown && now - cleanFrameAt < CLEAN_FRAME_GAP_MS) return
        capturing = true
        capturingSince = now
        cleanFrameAt = now
        val wanted = lines.toList()
        // When the overlay was really taken down, which is not when it was asked to be: the
        // request is posted to the main thread and waits its turn there. A frame captured
        // between the asking and the hiding still has our own paint in it, and reading that
        // gives our own gold back as the colour of the app's text.
        hiddenAt = 0L
        main.post { overlay.hideNow(); hiddenAt = android.os.SystemClock.uptimeMillis() }
        io.postDelayed({
            sampler.invalidateFrame()
            sampler.refreshIfStale(force = true)
            io.postDelayed({
                // Only a frame taken after the overlay went down can be trusted; anything
                // older still has our transcriptions in it.
                val down = hiddenAt
                if (!sampler.hasFrame || down == 0L || sampler.frameAt < down + SETTLE_MS) {
                    // A capture that never arrived says nothing about the lines, so they are
                    // not counted as read: a device that refuses one screenshot, for a rate
                    // limit or a moment of secure content, would otherwise leave the screen
                    // permanently uncoloured.
                    // The lines were asked for and the answer did not come. That counts:
                    // otherwise an app the platform refuses to capture would leave every one
                    // of them waiting for colours that can never arrive, and unpainted.
                    for ((key, _) in wanted) countAttempt(key)
                    capturing = false
                    if (BuildConfig.DEBUG) android.util.Log.d("Phonetix", "COLOURS no clean frame")
                    schedule(0L)
                    return@postDelayed
                }
                // The frame now holds the app's own text where our transcriptions were.
                var read = 0
                for ((key, rect) in wanted) {
                    countAttempt(key)
                    if (key in lineColors) continue
                    val c = sampler.sampleRegion(rect) ?: continue
                    lineColors[key] = c
                    read++
                }
                // Whatever could not be read still has to be covered by something, and the
                // page's own colour is a better guess than a palette of ours.
                screenColors = sampler.screenColors() ?: screenColors
                capturing = false
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Phonetix",
                        "COLOURS read=$read of ${wanted.size} screen=" +
                            (screenColors?.let { Integer.toHexString(it.background) } ?: "none"),
                    )
                }
                schedule(0L)
            }, 320)
        }, 80)
    }

    /**
     * How far a line has moved, from where it says it is now against where it was.
     *
     * A node's rectangle is not the line: it is the part of the line that can be seen. A
     * line half scrolled off the top of its list reports a rectangle whose top sits exactly
     * at the edge of the list and whose height has shrunk, and it keeps reporting that same
     * top however far the page moves, so the line appears to be standing still while
     * everything around it moves. Following that answer is how transcriptions ended up
     * strewn across a scrolling page.
     *
     * So the distance is measured along an edge that was whole in both readings: comparing
     * a top that has since been cut against one that had not is comparing the line against
     * the edge of the list. A line with no such edge says nothing that can be used, and null
     * says so rather than guessing.
     */
    private fun shiftOf(
        was: android.graphics.Rect,
        now: android.graphics.Rect,
        clip: android.graphics.Rect,
    ): Pair<Float, Float>? {
        val dx = (now.left - was.left).toFloat()
        val wasTopCut = was.top <= clip.top + 1
        val wasBottomCut = was.bottom >= clip.bottom - 1
        val nowTopCut = now.top <= clip.top + 1
        val nowBottomCut = now.bottom >= clip.bottom - 1
        return when {
            !wasTopCut && !nowTopCut -> dx to (now.top - was.top).toFloat()
            !wasBottomCut && !nowBottomCut -> dx to (now.bottom - was.bottom).toFloat()
            else -> null
        }
    }

    private fun countAttempt(key: String) {
        val now = android.os.SystemClock.uptimeMillis()
        val tried = colorTries[key]
        if (tried == null) {
            colorTries[key] = Attempts(1, now)
        } else {
            // A run of attempts long ago says nothing about now: the device was busy, or the
            // app was mid-layout. Time since the last one starts the count again.
            if (now - tried.at > COLOR_RETRY_MS) tried.count = 0
            tried.count++
            tried.at = now
        }
    }

    /** A line is known by its text, which is what stays the same while it scrolls. */
    private fun colorKey(text: String): String = text.length.toString() + ":" + text.hashCode()

    /**
     * Where to read a line's colours from: the line itself, as the app drew it.
     *
     * The node's own rectangle, clipped to what its ancestors show of it, is the whole run
     * of text and so holds enough of both colours to tell them apart. When there is no
     * rectangle to be had, the words fall back to their own boxes grown sideways, which is
     * the same line with fewer pixels of it.
     */
    private fun colorRect(p: Planned): RectF {
        val at = p.measuredAt
        if (at != null) {
            val r = android.graphics.Rect(at)
            if (r.intersect(p.clip) && r.width() > 2 && r.height() > 2) return RectF(r)
        }
        val first = p.boxes.first().rect
        var left = first.left
        var right = first.right
        var top = first.top
        var bottom = first.bottom
        for (b in p.boxes) {
            left = minOf(left, b.rect.left); right = maxOf(right, b.rect.right)
            top = minOf(top, b.rect.top); bottom = maxOf(bottom, b.rect.bottom)
        }
        val grow = (bottom - top)
        return RectF(left - grow, top, right + grow, bottom)
    }

    private class Planned(
        val node: AccessibilityNodeInfo,
        val from: Int,
        val length: Int,
        val picks: List<Pick>,
        /** What the node said when these picks were made; a reused node whose text has
         *  changed would otherwise be painted with the previous screen's transcriptions. */
        val text: String,
        /** Everything the node's ancestors clip it to, the node's own bounds included. */
        val clip: android.graphics.Rect,
        /** What the ancestors alone allow: the window the line scrolls inside. A line's own
         *  rectangle is reported cut to this, so it is what says which of its edges are
         *  whole. */
        val viewport: android.graphics.Rect,
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
                out.add(
                    Planned(
                        node, from, to - from + 1, picks, text,
                        android.graphics.Rect(clip), android.graphics.Rect(inherited), 0,
                    ),
                )
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
            out[plannedHere] = Planned(
                was.node, was.from, was.length, was.picks, was.text, was.clip, was.viewport,
                budget.order,
            )
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
    /**
     * What a line said about itself, kept so that it need not be asked twice.
     *
     * Held relative to the line's own top left corner, which is what makes it reusable: the
     * page scrolls, the line moves, and the characters keep their places within it.
     */
    private class Layout(val width: Int, val height: Int, val rects: Array<RectF?>)

    private val charLayouts = object : LinkedHashMap<String, Layout>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Layout>?) = size > 200
    }

    private fun layoutKey(p: Planned): String =
        p.text.length.toString() + ":" + p.text.hashCode() + ":" + p.from + ":" + p.length

    /** Keep a line's character boxes, as offsets inside the line. */
    private fun remember(p: Planned, at: android.graphics.Rect, rects: Array<RectF?>) {
        // Only a line that is whole on screen can be remembered: the rectangle of a line
        // half scrolled off is cut at the edge of its list, and offsets taken from it would
        // put every character in the wrong place.
        if (at.isEmpty || at.top <= p.viewport.top + 1 || at.bottom >= p.viewport.bottom - 1) return
        val relative = Array<RectF?>(rects.size) { i ->
            rects[i]?.let { RectF(it.left - at.left, it.top - at.top, it.right - at.left, it.bottom - at.top) }
        }
        charLayouts[layoutKey(p)] = Layout(at.width(), at.height(), relative)
    }

    /**
     * The remembered boxes, moved to where the line is now, or null when they cannot be
     * trusted for it: a line of a different width has been laid out again, and a line cut at
     * both ends has no corner to measure from.
     */
    private fun placeRemembered(
        layout: Layout?,
        at: android.graphics.Rect,
        clip: android.graphics.Rect,
    ): Array<RectF?>? {
        if (layout == null || at.isEmpty || at.width() != layout.width) return null
        // Only a line reported whole. A line cut by the edge of its list reports the part of
        // itself that shows, and a corner taken from that is a few pixels out - enough to
        // set a transcription over the line above or below it. Those lines are asked
        // directly instead, which costs one request at each edge of the screen.
        if (at.top <= clip.top + 1 || at.bottom >= clip.bottom - 1) return null
        if (at.height() != layout.height) return null
        val top = at.top
        return Array(layout.rects.size) { i ->
            layout.rects[i]?.let {
                RectF(it.left + at.left, it.top + top, it.right + at.left, it.bottom + top)
            }
        }
    }

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
        /** How long the following keeps checking after the last sign of movement. Long
         *  enough to cover the pause between two strokes of a scroll, because a reader who
         *  moves the page in short pushes stops for a moment between them, and a loop that
         *  gave up in that moment had to be woken by an event that arrives late. */
        const val FOLLOW_IDLE_MS = 900L
        /** However well the following is going, a settled screen is read in full this often. */
        const val FULL_READ_MS = 900L
        /** And a moving one this often, to pick up the words scrolling into it. */
        const val FULL_READ_MOVING_MS = 2500L
        /** How rarely the overlay may step aside to be able to read a colour. */
        const val CLEAN_FRAME_GAP_MS = 1500L
        /** How often a line's colours are looked for before the page's own are used. */
        const val COLOR_TRIES = 3
        /** And how long before a line that could not be read is worth trying again. */
        const val COLOR_RETRY_MS = 8000L
        /** After this a capture is treated as lost rather than still on its way. */
        const val CAPTURE_TIMEOUT_MS = 2500L
        /** How long after the overlay comes down before a frame is free of it: what was
         *  drawn is still on the display for a frame or two after the window has gone. */
        const val SETTLE_MS = 48L
        /** How many apps' colours are kept, so switching between two is not a fresh read. */
        const val REMEMBERED_APPS = 4
        /** How many lines are asked where they are before the rest are carried with them. */
        const val ANCHORS = 3
        /** And how many are asked whether they are still themselves, each pass. */
        const val VERIFY_PER_PASS = 2
        /** Plus this many at the edge the content is leaving by, where rows are recycled. */
        const val VERIFY_AT_EDGE = 2
        /** Beyond this, lines are not moving together and each has to be asked itself. */
        const val INDEPENDENT_PX = 300f
        const val MAX_NODES = 120
        const val MAX_VISITS = 400
        const val MAX_WORDS = 60
        const val MAX_TEXT = 2000
    }
}
