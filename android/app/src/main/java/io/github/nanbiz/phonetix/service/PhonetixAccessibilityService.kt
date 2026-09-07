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
import io.github.nanbiz.phonetix.core.Eld
import io.github.nanbiz.phonetix.core.IpaSymbols
import io.github.nanbiz.phonetix.core.Language
import io.github.nanbiz.phonetix.core.Pick
import io.github.nanbiz.phonetix.core.SettingsStore
import io.github.nanbiz.phonetix.core.Transcriber
import io.github.nanbiz.phonetix.core.WordBox
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private lateinit var bystanders: Bystanders
    private var settingsWatch: kotlinx.coroutines.CoroutineScope? = null
    private val layouts = LineLayouts()
    private var generation = 0
    /** Where other windows - a keyboard above all - stand over the app being read. */
    @Volatile private var blockers: List<android.graphics.Rect> = emptyList()
    /** Apps already named in the log as never standing still. */
    private val waitedOut = HashSet<String>(4)
    /** Apps already named in the log as giving no character bounds, so each is said once. */
    private val noCharacters = HashSet<String>(4)
    /** Apps already named in the log as bystanders, so each is said once. */
    private val ignored = HashSet<String>(4)
    /** Whether the last event found the overlay switched on, so switching off hides once. */
    @Volatile private var wasEnabled = true
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
    /** How many passes in a row have kept no words at all. */
    @Volatile private var blankFollows = 0
    /** And how many in a row have had no line answer where it is. */
    @Volatile private var deadFollows = 0
    /** The last measured shift and when, and the speed they give, in pixels a millisecond. */
    @Volatile private var lastShiftY = 0f
    @Volatile private var lastShiftX = 0f
    @Volatile private var lastShiftAt = 0L
    @Volatile private var speedY = 0f

    /** The colours each line is drawn in, read off the screen. */
    private lateinit var colours: LineColours

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
        // Which language a line is in, which decides whether it is transcribed at all. Read
        // on the io thread: it is a megabyte of ngrams and the service must not wait for it.
        io.post { Eld.ensureLoaded(this) { schedule(0L) } }
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
        colours = LineColours(
            sampler, main, io,
            hideOverlay = { overlay.hideNow() },
            readAgain = { schedule(0L) },
        )
        bystanders = Bystanders(this)
        // A change of setting is acted on at once, not at the next thing the app in front
        // happens to do. The switch in the shade and the bar in this app both write here,
        // and a reader who turns the overlay on while looking at a still page expects the
        // words to appear, not to wait for the page to move.
        settingsWatch = kotlinx.coroutines.MainScope().also { scope ->
            scope.launch {
                SettingsStore.state.collect { s ->
                    if (!s.enabled) {
                        overlay.hideNow()
                        tooltip.hide()
                    }
                    overlay.applyTouchability()
                    scrollOnly = false
                    schedule(0L)
                }
            }
        }
        // The accessibility button, which is the one place a reader can reach without leaving
        // what they are reading: it sits in the navigation bar or floats over the screen, and
        // it is where a service that changes what every app looks like belongs. The tile in
        // the shade does the same thing, two swipes away.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching {
                accessibilityButtonController.registerAccessibilityButtonCallback(
                    object : android.accessibilityservice.AccessibilityButtonController
                    .AccessibilityButtonCallback() {
                        override fun onClicked(
                            controller: android.accessibilityservice.AccessibilityButtonController,
                        ) {
                            val on = !SettingsStore.current.enabled
                            SettingsStore.setEnabled(on)
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("Phonetix", "BUTTON enabled=$on")
                            }
                        }
                    },
                )
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Phonetix",
                        "BUTTON registered available=" +
                            accessibilityButtonController.isAccessibilityButtonAvailable,
                    )
                }
            }.onFailure { android.util.Log.w("Phonetix", "no accessibility button", it) }
        }
        Dictionary.ensureLoaded(this) { schedule(0L) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The home screen and the keyboard announce every flicker of themselves. Nothing of
        // theirs is ever transcribed, so there is no reason to fetch their window to find
        // that out - which is what every one of those announcements cost.
        // Ignored rather than answered: the status bar reports its clock ticking over while
        // the reader is in another app entirely, and taking the words down for that would
        // be a flicker a minute. Whether the app in front is one of these is settled by the
        // read itself, from the window that is actually there.
        val from = event?.packageName?.toString()
        if (::bystanders.isInitialized && bystanders.contains(from)) {
            // Said once per app, because an app dropped here is dropped before anything else
            // is recorded about it: the settings app was ignored whole on a device whose home
            // intent answers with its own placeholder activity, and nothing in the log
            // mentioned the settings app at all.
            if (BuildConfig.DEBUG && ignored.add(from.orEmpty())) {
                android.util.Log.d("Phonetix", "IGNORING $from, it is a bystander")
            }
            return
        }
        // Switched off, nothing is read at all. Fetching the window to discover that on
        // every event of every app is work a reader who turned the overlay off did not ask
        // for; the words come down once, and the next event after it is switched back on
        // is answered as usual.
        if (!SettingsStore.current.enabled) {
            if (wasEnabled && ::overlay.isInitialized) main.post { overlay.hideNow() }
            wasEnabled = false
            return
        }
        wasEnabled = true
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
        //
        // A change counts as movement here, which is not obviously right and was tried both
        // ways. It decides whether a line's characters may be measured - asking for them
        // makes the app lay its text out again, which a page being scrolled cannot afford -
        // so an app that changes something ten times a second is never measured at all.
        // Chrome is one. Not counting it, though, let the colours be read off a screen that
        // was moving, which photographs as a smear, and left what is drawn through a swipe
        // measurably further from its word. Chrome cannot be transcribed either way: it
        // answers the request for character positions with nothing at all.
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Every app on the device has just been repainted - a theme changed, or the text got
        // bigger - and the colours were read off the way it looked before. They are keyed by
        // the words of a line, which have not changed, so nothing else would notice.
        if (::colours.isInitialized) colours.forget()
        cachedPlan = emptyList()
        scrollOnly = false
        layouts.forget()
        schedule(0L)
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hideNow()
    }

    override fun onDestroy() {
        settingsWatch?.cancel()
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
            worker.postDelayed(this, gapMs)
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
            SettingsStore.allows(cachedPackage) && !bystanders.contains(cachedPackage) &&
            Dictionary.ready
        val root = if (followOnly) null else {
            val askedRoot = android.os.SystemClock.uptimeMillis()
            val fetched = rootInActiveWindow ?: run {
                if (BuildConfig.DEBUG) android.util.Log.d("Phonetix", "NOROOT")
                main.post { overlay.hideNow() }
                return
            }
            val rootMs = android.os.SystemClock.uptimeMillis() - askedRoot
            if (BuildConfig.DEBUG && rootMs > 30) {
                android.util.Log.d("Phonetix", "ROOT took ${rootMs}ms")
            }
            fetched
        }
        val inFront = root?.packageName?.toString()
        if (root != null && (!SettingsStore.allows(inFront) || bystanders.contains(inFront) || !Dictionary.ready)) {
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
            for (p in cachedPlan) p.measuredAt?.let { before[colours.key(p.text)] = it.top }
            val fresh = ArrayList<Planned>(16)
            val full = android.graphics.Rect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
            val seen = ArrayList<Painted>(128)
            readBlockers()
            plan(root!!, Transcriber(settings.density), fresh, budget, stats, full, seen)
            // A screen in a language this dictionary is not for is left alone. Judged after
            // the walk, because it is the whole of the screen that says what language it is
            // in - a line on its own says too little, and saying it confidently.
            if (!Language.ours(stats.tongue.read())) fresh.clear()
            planned = fresh
            cachedPlan = fresh
            cachedPackage = pkg
            cachedPainted = seen
            previousTops = before
            plannedDensity = settings.density
            lastFullReadAt = android.os.SystemClock.uptimeMillis()
            // Every line is measured where it is now, so the distance from there is nothing
            // and the following starts again from that. Carrying the old numbers over made
            // the first pass after a read report the whole of the previous plan's travel as
            // movement that had just happened.
            lastShiftY = 0f
            lastShiftX = 0f
            blankFollows = 0
            deadFollows = 0
            // And what the previous plan was seen doing is not known about this one; held
            // across plans, one recycled row on one screen let every later pass keep no words
            // and still call itself a success.
            recycling = false
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
            // The same few however fast the page is going. Asking fewer while it hurries was
            // tried, on the reasoning that an answer arriving late is worth less than one
            // arriving: two are a shade quicker and photograph no better through a drag, and
            // one cannot be outvoted at all - a line handed to another row answers with the
            // distance between them, and a single anchor put the words a thousand pixels off
            // during a fling. Three costs a round trip more and is the only count that
            // outvotes a liar.
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
            // What the page was doing a moment ago says what it is doing now: a line whose
            // answer is nowhere near that has not moved with the page, it has been handed to
            // another line and jumped. With a speed to compare against, one such answer is
            // recognised on its own rather than having to be outvoted.
            // A speed measured a moment ago describes a page that may since have stopped -
            // a reader scrolling in short pushes stops between each - so it is only believed
            // while it is fresh.
            val speedIsFresh = speedY != 0f && lastShiftAt > 0L &&
                android.os.SystemClock.uptimeMillis() - lastShiftAt < SPEED_FRESH_MS
            val believable = if (!speedIsFresh) shifts else {
                val kept = shifts.filter { (_, dy, at) ->
                    val expected = lastShiftY + speedY * (at - lastShiftAt)
                    kotlin.math.abs(dy - expected) <= PREDICTION_TOL
                }
                if (kept.isEmpty()) shifts else kept
            }
            var agreed = believable.isNotEmpty()
            if (agreed) {
                val middle = believable.sortedBy { it.second }[believable.size / 2]
                shiftX = middle.first
                shiftY = middle.second
                readAt = middle.third
                // A disagreement far larger than the movement can explain is not one page
                // scrolling: it is a list inside a page, or a bar that stays put while the
                // text moves under it, and then there is nothing for one measurement to
                // stand for and each line is asked about itself.
                val spread = believable.maxOf { it.second } - believable.minOf { it.second }
                if (spread > INDEPENDENT_PX) agreed = false
            }
            // Against the shift the last pass measured, not against zero. A shift is the
            // distance from where the plan was made, so it stays large for as long as the
            // plan lives: read as movement, a page that scrolled once and then stood still
            // reported itself moving for ever. The loop then never idled, the full read that
            // ends a movement never ran, and the words stayed on the motion layer being
            // carried at a speed the page no longer had.
            if (alive > 0 && (kotlin.math.abs(shiftY - lastShiftY) > MOVED_PX ||
                    kotlin.math.abs(shiftX - lastShiftX) > MOVED_PX)
            ) {
                shifted = true
            }
            // How fast the page is going, from this reading against the one before. The
            // full read below needs it: it takes tens of milliseconds, and its first line is
            // measured well before its last.
            if (agreed && lastShiftAt > 0L && readAt > lastShiftAt) {
                val dt = (readAt - lastShiftAt).toFloat()
                if (dt in 1f..300f) speedY = (shiftY - lastShiftY) / dt
            }
            lastShiftY = shiftY
            lastShiftX = shiftX
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
            // Not while the page is racing, for the reason above: each check is another round
            // trip, and a row that has been handed to a different line is caught by the next
            // slower pass and by the full read that ends the movement. What it costs to check
            // it now is every word on the screen sitting where the page was a moment ago.
            if (planned.isNotEmpty() && kotlin.math.abs(speedY) <= HURRIED_PX_PER_MS) {
                val leaving = planned
                    .filter { it.measuredAt != null && it.boxes.isNotEmpty() }
                    .sortedBy { line ->
                        val top = line.measuredAt?.top ?: 0
                        if (shiftY <= 0f) top else -top
                    }
                // The edge is where a list hands a row to a new line, so it is watched closely
                // - but only on a screen that has been seen doing it. An article does not
                // recycle anything, and on one of those these were four round trips a pass
                // spent confirming what never changes, which is four round trips the words
                // were not being measured in: the readings came half as often and everything
                // on the screen sat that much further behind the text.
                checks.addAll(leaving.take(if (recycling) VERIFY_AT_EDGE else 1))
                val turns = if (recycling) VERIFY_PER_PASS else 1
                for (i in 0 until minOf(turns, planned.size)) {
                    checks.add(planned[(verifyFrom + i) % planned.size])
                }
                verifyFrom = (verifyFrom + turns) % planned.size
            }
            val verified = HashSet<Planned>(checks.size + anchors.size)
            verified.addAll(anchors)
            for (c in checks) {
                if (c in verified) continue
                if (!c.node.refresh() || c.node.text?.toString() != c.text) {
                    // A row that now says something else is a row a list has handed to
                    // another line: its words belong to text that is no longer there, so it
                    // is dropped and the rest are followed as before. Abandoning the whole
                    // plan for it meant reading the entire screen again, which takes long
                    // enough to be a visible stall in the middle of the scroll that caused
                    // it - and on a list that recycles constantly, over and over.
                    c.boxes = emptyList()
                    c.measuredAt = null
                    recycling = true
                    continue
                }
                verified.add(c)
            }

            for (p in planned) {
                if (!ok) break
                val was = p.measuredAt
                // A line whose words were all clipped away contributes nothing, which is
                // normal and not a reason to abandon following the rest of them.
                if (was == null || p.boxes.isEmpty()) continue
                // The same rule the full read follows: a line still waiting for its colours
                // is not drawn in a palette of ours. Its colours may also have arrived since
                // it was planned, in which case they are put on now - otherwise a line that
                // came into view during a scroll would keep the absence it was born with
                // until the movement stopped.
                // Asked the same question the full read asks, which includes what a line that
                // could not be read at all is to be drawn in. Asking only for the line's own
                // reading meant a line that had been given up on kept the nothing it was born
                // with for as long as the screen kept moving, and nothing is drawn as black:
                // a black patch over a word on a coloured page, which is what it did on a
                // music player whose title sits on its cover art.
                val decision = colours.decide(p.text, p.measuredAt?.top ?: -1)
                val own = decision.colours
                if (own != null && p.boxes.first().background != own.background) {
                    p.boxes = p.boxes.map { it.copy(background = own.background, ink = own.ink) }
                }
                if (own == null && p.boxes.first().background == 0 && !decision.givenUp) {
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
                    // Against this line's own last answer, for the reason above: the shift is
                    // measured from where the plan was made and does not return to zero when
                    // the page stops.
                    if (kotlin.math.abs(dy - p.lastDy) > MOVED_PX ||
                        kotlin.math.abs(dx - p.lastDx) > MOVED_PX
                    ) {
                        shifted = true
                    }
                    p.lastDx = dx
                    p.lastDy = dy
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
            if (moved.isEmpty()) blankFollows++ else blankFollows = 0
            if (ok && recycling && moved.isEmpty() && blankFollows <= BLANK_FOLLOWS) {
                // Nothing verified yet on a recycling screen is not a failure; the next pass
                // verifies the next lines, and the full read at the end settles it. Only for
                // a pass or two, though: text that is being written changes the lines under
                // the plan, every word then falls outside the line it was measured in, and
                // treating that as success held a plan describing a screen that was gone for
                // as long as the movement lasted - an overlay showing nothing at all while
                // the reader watched an answer being written.
                ok = true
            } else if (ok && moved.isEmpty() && planned.any { it.boxes.isNotEmpty() }) {
                ok = false
                why = "the follow kept no words at all ($clipped clipped, $hidden covered, $unreadable unreadable)"
            }
            // However few lines are left to keep words in, an overlay that has drawn nothing
            // for several passes running is not following anything.
            if (ok && moved.isEmpty() && blankFollows > BLANK_FOLLOWS) {
                ok = false
                why = "nothing kept for $blankFollows passes ($clipped clipped, $hidden " +
                    "covered, $unreadable unreadable)"
            }
            // Nothing answered at all. That is what a screen replaced under us looks like -
            // and also what a busy moment looks like, because asking a line where it is goes
            // to the app's own thread and comes back empty-handed when that thread is behind.
            // Reading the whole screen again costs a tenth of a second during which nothing
            // is followed, so a page being flung answered nothing, was read in full, answered
            // nothing again: nine full reads in one swipe, and the words standing still
            // through all of them. A pass or two of silence is waited out instead; the layer
            // carries the words meanwhile, and the plan is only given up when the silence
            // lasts.
            if (alive == 0) deadFollows++ else deadFollows = 0
            if (ok && alive == 0 && deadFollows > DEAD_FOLLOWS) {
                ok = false
                why = "nothing answered for $deadFollows passes"
            }
            // Most of the screen gone means the same thing, even if a line or two remain.
            if (ok && gone > alive) {
                ok = false
                why = "more lines recycled ($gone) than kept ($alive)"
            }
            // A screen that has largely scrolled away is a screen whose words are mostly new
            // ones, and following cannot transcribe a word it has never read. Carried on to
            // the end of the movement, the overlay emptied out as the reader scrolled: thirty
            // transcriptions at the top of a page, nineteen a screen later, and the lines that
            // had arrived in the meantime bare. The clock that forces a read during a movement
            // cannot answer for this - it is the distance travelled that matters, not the time
            // taken - so a plan that has lost this much of itself is read again now.
            // What says so is how many words have been carried off the edge of the window
            // they live in, not how many were drawn: a line still waiting for the colours it
            // is to be painted in is held back every pass, and counting those as words the
            // page had lost sent a screen that was standing still into a read a second.
            // And only once the page has all but stopped. Reading the screen again takes as
            // long as forty round trips into an app that is busy scrolling - a third to half
            // a second, measured - and the words stand still on the page for all of it. A
            // drag a reader can read along with is half a pixel a millisecond, so allowing it
            // at anything under a pixel meant an ordinary swipe stalled the overlay twice.
            // What scrolled in is filled in the moment the finger stops, which is what the
            // loop does when it goes idle anyway.
            if (ok && shifted && planned.isNotEmpty() &&
                kotlin.math.abs(speedY) <= SETTLING_PX_PER_MS
            ) {
                val had = planned.sumOf { it.boxes.size }
                val fresh = android.os.SystemClock.uptimeMillis() - lastFullReadAt
                if (had > 0 && clipped > had * (1f - KEPT_ENOUGH) &&
                    fresh > FULL_READ_TURNOVER_MS
                ) {
                    ok = false
                    why = "the screen has moved on: $clipped of $had words scrolled away"
                }
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
                    if (moving && overlay.inMotion) overlay.motionMeasured(moved, readAt, speedY)
                    else overlay.endMotion(moved)
                    android.util.Log.d(
                        "Phonetix",
                        "follow=${took}ms lines=${planned.size} boxes=${moved.size} moving=$moving " +
                            "shift=$shiftY agreed=$agreed alive=$alive gone=$gone " +
                            "clipped=$clipped covered=$hidden unreadable=$unreadable",
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
        var waited = 0
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
            val remembered = layouts.recall(p.text, p.from, p.length)
            val placed = layouts.place(remembered, at, p.viewport)
            // Asking the app where its characters are makes it lay the text out again, and a
            // list being laid out while it scrolls clamps its own scroll position and jumps.
            // A line that cannot be placed from memory is therefore left alone until the
            // movement stops; it is one of the part-hidden lines at the edges of the screen,
            // and it comes back with the next full read.
            val moving = android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS
            if (placed == null && moving) {
                waited++
                if (BuildConfig.DEBUG && waitedOut.add(pkg.orEmpty())) {
                    android.util.Log.d(
                        "Phonetix",
                        "WAITING $pkg is never still, so its characters are never measured",
                    )
                }
                p.boxes = emptyList(); continue
            }
            val rects = placed
                ?: charRects(p.node, p.from, p.length)?.also { fresh ->
                    layouts.remember(p.text, p.from, p.length, at, p.viewport, fresh)
                }
                ?: run {
                    // Some apps will not say where the characters of a line are. Without them
                    // a transcription cannot be put on one word, so the line is left alone -
                    // and it is worth knowing which apps those are.
                    if (BuildConfig.DEBUG && noCharacters.add(pkg.orEmpty())) {
                        android.util.Log.d(
                            "Phonetix",
                            "NOCHARS $pkg will not give character bounds for '" +
                                p.text.take(24) + "'",
                        )
                    }
                    continue
                }
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
        // How fast the page went during this read, measured by the read itself: the first
        // line that was measured is asked once more at the end, and the distance it has
        // covered meanwhile is what every other line has to be carried by.
        //
        // The speed the following left behind cannot answer for this. A read takes tens of
        // milliseconds to a fifth of a second, and it is entered exactly when the following
        // has stopped believing its own speed - at the start of a stroke, after a pause -
        // so the compensation below was skipped in the case that most needed it, and a page
        // read from top to bottom during a movement came out as a screen that never existed.
        var during = 0f
        if (!reuse && android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS) {
            val first = planned.firstOrNull { lineReadAt[it] != null && it.boxes.isNotEmpty() }
            val was = first?.measuredAt
            val when0 = if (first != null) lineReadAt[first] else null
            if (first != null && was != null && when0 != null) {
                val again = android.graphics.Rect()
                if (first.node.refresh() && first.node.text?.toString() == first.text) {
                    first.node.getBoundsInScreen(again)
                    val nowAt = android.os.SystemClock.uptimeMillis()
                    val travelled = shiftOf(was, again, first.viewport)
                    val dt = (nowAt - when0).toFloat()
                    // A page cannot cross the screen in a frame. A number larger than that is
                    // a line that was handed to another line, or a layout jump, and carrying
                    // every word by it would throw the whole screenful somewhere it never was.
                    val rate = travelled?.second?.div(dt) ?: 0f
                    if (travelled != null && dt >= 1f && kotlin.math.abs(rate) <= SANE_PX_PER_MS) {
                        during = rate
                        // What the following would have measured, had it been running: this
                        // is a reading of the page's speed like any other, and the pass after
                        // this one has nothing fresher.
                        speedY = during
                        lastShiftAt = nowAt
                        readAt = nowAt
                    }
                }
            }
        }
        // Every line of a full read is measured at a different moment, and on a moving page
        // that is a screen that never existed: the first line belongs to where the page was
        // fifty milliseconds ago and the last to where it is now. Each is carried forward to
        // the moment the reading finished.
        if (!reuse && during != 0f) {
            for (p in planned) {
                val when0 = lineReadAt[p] ?: continue
                val ahead = ((readAt - when0).coerceIn(0L, 200L).toFloat() * during)
                    .coerceIn(-CARRY_LIMIT_PX, CARRY_LIMIT_PX)
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
        if (colours.switchTo(pkg)) recycling = false

        // Only lines never read on this screen are worth stepping aside for. Without that
        // the overlay hid itself every second and a half for the whole life of a page,
        // which cost more than the colours were worth and left flings barely drawn.
        val unread = planned.filter { it.boxes.isNotEmpty() && colours.wanted(it.text) }
        if (unread.isNotEmpty()) {
            colours.read(unread.map { colours.key(it.text) to colorRect(it) })
        }

        // The colours ride on the boxes the lines keep, so that following a scroll carries
        // them too: re-reading positions does not re-read colours, and a set of words that
        // lost its colours the moment the screen moved was the flicker between a scrolling
        // transcription and a still one. Only the painting of a line still waiting for its
        // colours is withheld, because a line dropped from the plan would be missing from
        // the next pass too rather than appearing the moment its colours come.
        val painted = ArrayList<WordBox>(boxes.size)
        for (p in planned) {
            val decision = colours.decide(p.text, p.measuredAt?.top ?: -1)
            val c = decision.colours
            if (c != null) p.boxes = p.boxes.map { it.copy(background = c.background, ink = c.ink) }
            if (c != null || decision.givenUp) painted.addAll(p.boxes)
            if (BuildConfig.DEBUG && c == null && decision.givenUp) {
                android.util.Log.d("Phonetix", "DECIDE none givenUp for '${p.text.take(20)}'")
            }
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
                val was = previousTops[colours.key(p.text)] ?: continue
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
                    "coloured=${painted.count { it.background != 0 }} during=$during " +
                    "waited=$waited/${planned.size}",
            )
        }
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
        /** How far this line was carried on the last pass, so the next one can tell whether
         *  it has moved since rather than whether it has moved at all. */
        var lastDx = 0f
        var lastDy = 0f
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
    private class Stats(
        var ipcNs: Long = 0,
        var computeNs: Long = 0,
        var calls: Int = 0,
        /** The text of the screen, for deciding what language it is in. */
        val tongue: Language.Screen = Language.Screen(),
    )

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
            // Every line counts towards what language the screen is in, including the ones
            // that hold nothing worth transcribing: a page's German is mostly in its labels
            // and its buttons.
            stats.tongue.add(text)
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
        // Another window standing over the app: a keyboard, most of all. The app still holds
        // the text that is behind it and still reports where it is, so a transcription of a
        // word under the keyboard was drawn on top of the keyboard - our own window is above
        // both of them - and floated there over the letter keys.
        for (r in blockers) if (android.graphics.Rect.intersects(r, w)) return true
        for (p in cachedPainted) {
            if (p.enter < exit) continue
            if (android.graphics.Rect.intersects(p.rect, w)) return true
        }
        return false
    }

    /**
     * The windows standing over the app being read, which nothing of ours may be drawn on.
     *
     * Read from the system rather than guessed at: a keyboard is a window of its own, and so
     * is a system dialog or a picture in picture. Asked once a pass rather than once a word,
     * and only the ones that are not the app itself.
     */
    private fun readBlockers() {
        val found = ArrayList<android.graphics.Rect>(2)
        runCatching {
            for (w in windows) {
                val type = w.type
                val isOverlay = type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM ||
                    type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                if (!isOverlay) continue
                val r = android.graphics.Rect()
                w.getBoundsInScreen(r)
                // Ours is a window too, and it is a system overlay by type. Anything the size
                // of one word is one of ours; a keyboard is not.
                if (!r.isEmpty && r.height() > MIN_BLOCKER) found.add(r)
            }
        }
        blockers = found
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
        val raw = if (!ok) null else node.extras?.getParcelableArray(
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        )
        if (raw == null) return null
        return Array(raw.size) { raw[it] as? RectF }
    }

    companion object {
        /** The wait between passes while a page moves, left settable so it can be measured
         *  against photographs of the screen rather than argued about. */
        @Volatile
        @JvmStatic
        var gapMs = 16L

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
        /** How much of a plan's words have to survive the following for it to still describe
         *  the screen. Below this, most of what a reader is looking at has never been read. */
        const val KEPT_ENOUGH = 0.7f
        /** However fast a screen turns over, it is not read again more often than this: a
         *  read costs tens of milliseconds during which nothing is followed at all. */
        const val FULL_READ_TURNOVER_MS = 350L
        /** How many lines are asked where they are before the rest are carried with them. */
        const val ANCHORS = 3
        /** The speed, in pixels a millisecond, past which a pass buys nothing by asking more
         *  lines: at this rate the page moves a line's height in the time one answer takes. */
        const val HURRIED_PX_PER_MS = 1.0f
        /** Shorter than this, a window standing over the app is one of ours: every
         *  transcription is a window the height of a word. A keyboard is half a screen. */
        const val MIN_BLOCKER = 240

        /** A page that has all but stopped: slow enough that reading it again in full is
         *  worth the moment the overlay stands still for. */
        const val SETTLING_PX_PER_MS = 0.15f

        /** Faster than any page really travels, at a screen height in a frame or two. A
         *  measurement above this is not a speed, it is a jump. */
        const val SANE_PX_PER_MS = 12f
        /** And however fast it is going, no line is carried further than this to bring it to
         *  the same instant as the rest: past a screenful the correction is the error. */
        const val CARRY_LIMIT_PX = 400f
        /** And how many are asked whether they are still themselves, each pass. */
        const val VERIFY_PER_PASS = 2
        /** Plus this many at the edge the content is leaving by, where rows are recycled. */
        const val VERIFY_AT_EDGE = 2
        /** Beyond this, lines are not moving together and each has to be asked itself. */
        const val INDEPENDENT_PX = 300f
        /** How far an answer may be from what the page's own speed predicts before it is
         *  taken for a row that jumped rather than a page that moved. */
        const val PREDICTION_TOL = 90f
        /** How long a measured speed still describes the page. */
        const val SPEED_FRESH_MS = 120L

        /** Movement, in pixels: below this a line has been measured twice in the same place
         *  and the difference is rounding, not the page going anywhere. */
        const val MOVED_PX = 0.5f

        /** How many passes running may have no line answer at all before the screen is read
         *  properly. A line that has really gone stays gone; a busy moment does not. */
        const val DEAD_FOLLOWS = 2

        /** How many passes running may keep no words before the screen is read properly.
         *  One is ordinary while a list hands rows around; several in a row means the plan
         *  describes a screen that is no longer there. */
        const val BLANK_FOLLOWS = 2
        const val MAX_NODES = 120
        const val MAX_VISITS = 400
        const val MAX_WORDS = 60
        const val MAX_TEXT = 2000
    }
}
