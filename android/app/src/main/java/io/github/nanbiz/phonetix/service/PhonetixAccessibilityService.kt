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
    /** Looks again once a system window has finished coming or going. */
    private val afterASystemWindow = Runnable { scrollOnly = false; schedule(0L) }

    /** How many times running the thing in front has been something we do not transcribe,
     *  so a shade closing is waited out and a launcher sitting there is not polled for ever. */
    @Volatile private var lookAgain = 0
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

    /** Set when a single anchor gave an answer the page cannot have made, so the pass after
     *  it asks the full set and takes the middle answer rather than trusting one again. */
    private var voteNext = false

    /** How many recycled rows the pass now running has transcribed afresh, so that asking a
     *  moving app to lay text out again costs a bounded amount. */
    private var rewrittenThisPass = 0

    /** Where the previous full read found each line, so this one can tell whether the page
     *  has moved even when nothing announced that it had. */
    @Volatile private var previousTops: Map<String, Int> = emptyMap()
    @Volatile private var following = false

    /** Whether a read has already been asked for after a movement, so one is not queued for
     *  every pass of the follow that noticed the page had stopped. */
    @Volatile private var readPending = false

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
            // Not while a movement is being followed. Scheduling clears the loop that
            // follows it, and this fires whenever a reading of the colours has to be tried
            // again - including from a retry posted before the reader put their finger down.
            // The loop then died in the middle of the drag, the words came off the layer and
            // back onto the small windows, and each of those spends a frame invisible every
            // time it moves. The colours are read when the page stops, which is the only time
            // they can be read at all, and the pass that ends a movement asks for them.
            readAgain = { if (!following) schedule(0L) },
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
            // A window of theirs opening or closing is still worth looking at, even though
            // nothing of theirs is ever transcribed: the notification shade is one of these,
            // and it comes down over the app being read. Its events were dropped, so nothing
            // re-read which windows now stand over that app, and the whole screenful of
            // transcriptions stayed painted on top of the shade.
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Looked at once the movement is over rather than at once. These windows
                // arrive and leave with an animation, and during it the thing in front is
                // still the shade: a look taken then finds nothing of ours, hides everything,
                // and the app underneath - which is not sending anything, it did not change -
                // never prompts another. The transcriptions stayed gone until something else
                // happened on screen.
                //
                // Asked for on the main thread, not through the usual scheduling: that empties
                // the worker's queue, so the status bar - which announces itself whenever
                // anything happens anywhere - was cancelling the read the app in front had
                // just asked for and pushing it half a second into the future, over and over.
                // A whole app came back bare.
                main.removeCallbacks(afterASystemWindow)
                main.postDelayed(afterASystemWindow, AFTER_A_SYSTEM_WINDOW)
                return
            }
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
        // reported against 176 real pixels across one swipe, and again at 1918 against 1440
        // across another. Nor are there many of them: seven events for forty-three frames of
        // movement. Carrying the words on them was tried twice and is not worth trying again. Positions guessed from it drift
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
        // A change is not a movement. Whether the screen is moving decides whether a line's
        // characters may be measured - asking for them makes the app lay its text out again,
        // which a page being scrolled cannot afford - and an app that changes something ten
        // times a second was therefore permanently moving and permanently unmeasurable. The
        // settings app is one while it settles: thirteen lines planned, no characters measured
        // for any of them, nothing drawn, and a follow that ran every millisecond keeping no
        // words at all.
        //
        // Movement is measured instead, by the pass that follows, which is the only thing
        // that knows whether anything actually moved. What that costs is that the colours
        // could then be read off a moving screen, which photographs as a smear - so they wait
        // for stillness themselves, just below, rather than the whole overlay waiting for it.
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
    /**
     * Make sure a proper read happens once, a moment from now, cancelling nothing.
     *
     * Not the ordinary scheduling: that clears everything the worker was going to do, which
     * is right when a fresh event has made the pending work stale and wrong here, where the
     * point is only that the screen must not be left half read. If anything else scans in the
     * meantime this stands down.
     */
    private fun readAgainSoon(delay: Long) {
        if (!::worker.isInitialized || readPending) return
        readPending = true
        val was = lastFullReadAt
        worker.postDelayed({
            readPending = false
            if (lastFullReadAt != was) return@postDelayed
            if (following) return@postDelayed
            scrollOnly = false
            runCatching { scan() }
        }, delay)
    }

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
            // And look again in a moment. What is in front is usually on its way somewhere -
            // the notification shade closing, the recents screen going away - and while it
            // animates it is still the thing in front. The app underneath sends nothing more
            // once its own window has changed, so the look taken then was the last one and
            // the transcriptions stayed gone until something else happened on screen.
            //
            // Posted on the main thread rather than the worker: the worker's queue is emptied
            // whenever a fresh pass is asked for, and the events that arrive alongside this
            // ask for one - so a re-look posted there is cancelled by the very thing that
            // made it necessary.
            if (SettingsStore.current.enabled && lookAgain < LOOK_AGAIN_TIMES) {
                lookAgain++
                main.postDelayed({ scrollOnly = false; schedule(0L) }, LOOK_AGAIN_MS)
            }
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

        lookAgain = 0
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
            rewrittenThisPass = 0
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
            // What the page was doing a moment ago, which is both what decides how many
            // lines have to be asked where they are and what their answers are tested
            // against. A speed measured a moment ago describes a page that may since have
            // stopped - a reader scrolling in short pushes stops between each - so it is
            // only believed while it is fresh.
            val speedIsFresh = speedY != 0f && lastShiftAt > 0L &&
                android.os.SystemClock.uptimeMillis() - lastShiftAt < SPEED_FRESH_MS

            // How many lines are asked where they are, which is the whole cost of a pass:
            // each one is a round trip into an app that is busy laying out a scroll, and
            // three of them make the difference between a reading every fifty milliseconds
            // and one every eighty. At a finger's speed that gap is the drift.
            //
            // Three of them exist to outvote a liar: a list hands a row to another line and
            // that row answers with the distance between the two, which is most of a screen,
            // and a lone anchor believed once put the words a thousand pixels from their
            // text during a fling. But a liar can also be recognised rather than outvoted -
            // its answer is nowhere near what the page was doing a moment ago, which is what
            // the filter below tests every answer against anyway. So while there is a fresh
            // speed to test against, one anchor is asked and checked; when there is not -
            // the first pass of a movement, or after a pause - the full three are asked and
            // the middle answer taken. Measured through finger swipes: readings every 50ms
            // against 80, and the typical drawn word 22px from its line against 44.
            fun spread(count: Int): List<Planned> = when {
                usable.size <= count -> usable
                // Spread across the screen, so a page whose top half is one list and whose
                // bottom half is another is not judged entirely from one of them. With a
                // single anchor there is nothing to spread, and the middle of the screen is
                // the part most likely to be the thing the reader is scrolling.
                count <= 1 -> listOf(usable[usable.size / 2])
                else -> {
                    val step = (usable.size - 1).toFloat() / (count - 1)
                    (0 until count).map { usable[(it * step).toInt()] }
                }
            }

            /** Each anchor's answer, and the moment it gave it. */
            val shifts = ArrayList<Triple<Float, Float, Long>>(ANCHORS)
            /** Which lines were asked, however many rounds it took. */
            val anchors = ArrayList<Planned>(ANCHORS)
            fun ask(these: List<Planned>) {
                anchors.addAll(these)
                for (a in these) {
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
            }

            /** Whether an answer is anywhere near what the page was doing a moment ago. */
            fun credible(dy: Float, at: Long): Boolean {
                if (!speedIsFresh) return true
                val expected = lastShiftY + speedY * (at - lastShiftAt)
                return kotlin.math.abs(dy - expected) <= PREDICTION_TOL
            }

            val asked = spread(
                if (speedIsFresh && !voteNext) ANCHORS_WHEN_KNOWN else ANCHORS
            )
            ask(asked)
            // The one anchor said something the page cannot have done. It may be a row a list
            // has handed to another line, and it may be the page doing something new - one
            // answer cannot tell those apart, and believing it moved every word on the screen
            // sixteen hundred pixels.
            //
            // So it is not believed and not replaced either: this pass measures nothing, the
            // layer carries the words on at the speed it already has, and the next pass asks
            // the full three and votes. Asking the other two here instead was tried and is
            // worse - an answer that is merely ahead of the prediction is ordinary while a
            // page is speeding up, so the extra round trips were paid several times a second,
            // and the readings that were meant to come oftener came half as often.
            if (asked.size == 1 && shifts.size == 1 &&
                !credible(shifts[0].second, shifts[0].third)
            ) {
                voteNext = true
                return
            }
            voteNext = false
            // The middle answer, dated by the moment it was given. Not the freshest: on a
            // list, the row that answers last may be a row that has just been handed to a
            // different line, and its "movement" is the jump from one end of the screen to
            // the other rather than the page's. Two of three genuine answers outvote it. Not
            // the average either, for the same reason: one jump would drag it.
            var shiftX = 0f
            var shiftY = 0f
            // A line whose answer is nowhere near what the page was doing has not moved with
            // the page: it has been handed to another line and jumped. With a speed to
            // compare against, such an answer is recognised on its own rather than having to
            // be outvoted, which is what lets a single anchor be trusted above.
            val believable = shifts.filter { (_, dy, at) -> credible(dy, at) }
                .ifEmpty { shifts }
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
                val stillThere = c.node.refresh()
                val says = if (stillThere) c.node.text?.toString() else null
                if (!stillThere || says != c.text) {
                    // A row that now says something else is a row a list has handed to
                    // another line: its words belong to text that is no longer there, so it
                    // is dropped and the rest are followed as before. Abandoning the whole
                    // plan for it meant reading the entire screen again, which takes long
                    // enough to be a visible stall in the middle of the scroll that caused
                    // it - and on a list that recycles constantly, over and over.
                    c.boxes = emptyList()
                    c.measuredAt = null
                    recycling = true
                    // The row is gone, but the node is not: a list hands the same row to the
                    // line that has just scrolled in, so this node now carries text nobody
                    // has transcribed. Doing it here costs the refresh that found it, against
                    // walking the app's tree to find the same line - which costs 45 to 320ms
                    // and stalls the following for all of it.
                    if (stillThere && says != null) rewriteRow(c, says, moved)
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
                val decision = colours.decide(
                    p.text, p.measuredAt?.top ?: -1, colorRect(p),
                    impatient = kotlin.math.abs(speedY) > SETTLING_PX_PER_MS,
                )
                val own = decision.colours
                if (own != null && p.boxes.first().background != own.background) {
                    p.boxes = p.boxes.map { it.copy(background = own.background, ink = own.ink) }
                }
                // Drawn without colours rather than not drawn, while the page is moving:
                // there are no colours to be had until it stops, and a word missing is worse
                // than a word in a palette of ours for the length of a swipe.
                if (own == null && p.boxes.first().background == 0 && !decision.givenUp &&
                    kotlin.math.abs(speedY) <= SETTLING_PX_PER_MS
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
            if (ok && shifted && planned.isNotEmpty()) {
                val had = planned.sumOf { it.boxes.size }
                val fresh = android.os.SystemClock.uptimeMillis() - lastFullReadAt
                // A page still moving is read again too, not only one that has settled.
                // Waiting for stillness was the reason a drag emptied the screen: a plan
                // covers the lines that were on it when the plan was made, the lines that
                // scroll in were never in it, and through a swipe of a screen and a half
                // the reader was left with a third of the transcriptions they started with -
                // measured by photographing the movement, seven a frame against fourteen
                // standing still.
                //
                // It costs what it always cost: reading the screen takes a few hundred
                // milliseconds during which the words are carried on the layer at the speed
                // they had, and past that they stand still. So a moving page is held to a
                // larger loss and a longer wait than a settled one - it has to have lost
                // most of what it was carrying before the reading is worth the stall.
                // A page still moving is read again too, not only one that has settled.
                // A plan covers the lines that were on the screen when it was made, and
                // through a drag of a screen and a half almost none of those are left: the
                // lines that scroll in were never in it and carry nothing. Photographed
                // through three drags, waiting for stillness left a quarter of the
                // transcriptions on the screen; reading again once half of them had scrolled
                // away left more than half of them.
                //
                // It is held to a larger loss and a longer wait than a settled page, because
                // reading costs a few hundred milliseconds during which the words are carried
                // on at the speed they had and then stand still.
                val moving = kotlin.math.abs(speedY) > SETTLING_PX_PER_MS
                val lost = if (moving) MOSTLY_GONE else 1f - KEPT_ENOUGH
                val wait = if (moving) TURNOVER_MOVING_MS else FULL_READ_TURNOVER_MS
                if (had > 0 && clipped > had * lost && fresh > wait) {
                    if (moving) {
                        // Read in the middle of the movement, without stopping it. Giving up
                        // and letting the next pass read instead takes the words off the
                        // layer and puts them back on the small windows for the length of the
                        // read, which photographs as the overlay blinking - and the oftener
                        // the reading is worth doing, the more it blinks.
                        extendInBand(planned)
                    } else {
                        ok = false
                        why = "the screen has moved on: $clipped of $had words scrolled away"
                    }
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
                //
                // A page the app has not answered about for a moment is not a page that has
                // stopped. This asked only how long since a shift was last measured, and a
                // pass that comes back with the same positions - an app too busy to have laid
                // itself out again - looks exactly like stillness. Two of those in a row took
                // the words off the layer and put them back on the small windows, and the
                // next measured shift put them back, and so on: photographed through one
                // drag, the overlay changed hands seven times, and every change costs each
                // word a frame of being invisible while its window moves. The speed the
                // reading itself measured says whether the page is going anywhere, and it
                // falls to nothing on its own once the readings agree.
                val moving = following && (
                    android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS ||
                        kotlin.math.abs(speedY) > SETTLING_PX_PER_MS
                    )
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
                // A page that has stopped has to be read properly, and nothing else will ask.
                //
                // The loop that follows a movement reads the screen when it goes idle, but it
                // is not always the thing that ends: an event arriving near the end of a
                // scroll schedules a pass of its own, and scheduling clears the loop. What
                // ran then was a single following pass, which carries the words already known
                // and cannot know about the lines that scrolled in - and nothing was left to
                // ask again, because a list holding still announces nothing. Measured on a
                // list after a drag: six of seventeen lines carried a transcription, and
                // stayed that way for as long as the reader looked at it.
                if (!following && !moving) {
                    val due = FULL_READ_MS -
                        (android.os.SystemClock.uptimeMillis() - lastFullReadAt)
                    readAgainSoon(due.coerceIn(0L, FULL_READ_MS))
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
        /** How many lines this read has measured from scratch while the page was moving. */
        var measuredMoving = 0
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
            // A line nobody has measured before, on a page that is moving.
            //
            // Asking an app where the characters of a line are makes it lay that text out
            // again, which is why this used to wait for the page to stand still. What waiting
            // costs is every line that scrolls into view during a drag: it has never been
            // measured, so it is not drawn, and after a drag of a screen the reader is left
            // with the few lines they started with. Photographed through three drags, waiting
            // kept a fifth to two fifths of the transcriptions on the screen; measuring keeps
            // half to three fifths.
            //
            // What it costs is the app's own smoothness - the page reported eighty positions
            // through a drag when it was left alone and fifty-five while its lines were being
            // measured - so only a couple of lines are measured per pass. The rest are
            // measured by the passes after it, or when the movement stops.
            if (placed == null && moving && measuredMoving >= MEASURE_MOVING_MAX) {
                waited++
                if (BuildConfig.DEBUG && waitedOut.add(pkg.orEmpty())) {
                    android.util.Log.d(
                        "Phonetix",
                        "WAITING $pkg is never still, so its characters are never measured",
                    )
                }
                p.boxes = emptyList(); continue
            }
            if (placed == null && moving) measuredMoving++
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
        // Not off a screen that is going somewhere. Reading a line's colours photographs the
        // display, and a display that is moving photographs as a smear: white text on a white
        // page came back as the grey half way between them, on every word of the page. What
        // says it is moving is a shift this service measured, not an app announcing that
        // something on it changed.
        //
        // When the page last moved, not when it was last measured. This asked how long since
        // the last reading of the lines' positions, which is a different clock entirely: it
        // is set by every pass of the follow, shift or no shift, so on any screen the follow
        // was still running over - a list that announces itself, an app that redraws - it was
        // never more than a few milliseconds old and the colours were never read at all. The
        // lines that scrolled in during a drag were then held back for want of colours that
        // nothing was going to fetch, and the page a reader was left looking at after
        // scrolling carried transcriptions on a third of its lines.
        val stillEnough = kotlin.math.abs(speedY) < SETTLING_PX_PER_MS &&
            android.os.SystemClock.uptimeMillis() - lastMotionAt > STILL_MS
        val unread = if (!stillEnough) emptyList()
        else planned.filter { it.boxes.isNotEmpty() && colours.wanted(it.text) }
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
            val decision = colours.decide(
                p.text, p.measuredAt?.top ?: -1, colorRect(p),
                impatient = android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS,
            )
            val c = decision.colours
            if (c != null) p.boxes = p.boxes.map { it.copy(background = c.background, ink = c.ink) }
            // A word with no colours yet is still drawn while the page is moving.
            //
            // Withholding it is right on a page standing still: the colours are a moment away
            // and a word that flickers into a palette of ours and out again is worse than one
            // that arrives a moment late. But a screen read in the middle of a movement has no
            // colours to be had - reading them means photographing a still screen - so
            // withholding meant the read that happens during a drag could paint nothing at
            // all. Photographed at the display's own resolution: two frames running, some four
            // hundred milliseconds, with not one transcription on a page full of text, in the
            // middle of the drag that caused the read.
            val moving = android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS
            if (c != null || decision.givenUp || moving) painted.addAll(p.boxes)
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

        /**
         * Move this line to where it now stands, so that lines read at different moments can
         * be followed together.
         *
         * Every line in a plan is carried by one shift measured from where the plan was made,
         * which only works while they were all made at the same moment. A line read later -
         * one that scrolled into view during the movement - starts from where it is now, so
         * the lines already held are brought up to now as well and the shift starts again
         * from zero for all of them.
         */
        fun rebase(dx: Float, dy: Float) {
            if (dx == 0f && dy == 0f) return
            measuredAt?.offset(dx.toInt(), dy.toInt())
            clip.offset(dx.toInt(), dy.toInt())
            boxes = boxes.map {
                it.copy(rect = RectF(
                    it.rect.left + dx, it.rect.top + dy,
                    it.rect.right + dx, it.rect.bottom + dy,
                ))
            }
            lastDx = 0f
            lastDy = 0f
        }
    }


    /**
     * Measure one line's words, or place them from what the line said the last time it was
     * measured, and hand back what it now carries.
     *
     * The same work a full read does for every line, in a form the band read can call for
     * the two or three lines it has just found scrolling into view.
     *
     * @return false when the line could not be placed at all
     */
    private fun measureLine(p: Planned, allowedToAsk: Boolean): Boolean {
        val at = android.graphics.Rect()
        p.node.getBoundsInScreen(at)
        val remembered = layouts.recall(p.text, p.from, p.length)
        val placed = layouts.place(remembered, at, p.viewport)
        if (placed == null && !allowedToAsk) return false
        val rects = placed
            ?: charRects(p.node, p.from, p.length)?.also { fresh ->
                layouts.remember(p.text, p.from, p.length, at, p.viewport, fresh)
            }
            ?: return false
        val made = ArrayList<WordBox>(p.picks.size)
        Transcriber.boxes(p.picks, rects, p.from, made)
        if (remembered != null) p.node.getBoundsInScreen(at)
        p.measuredAt = at
        // Only the words inside the window this line scrolls in.
        //
        // What the bottom of a moving screen is missing is not these: photographed through a
        // drag, the lines without a transcription are the ones arriving at the bottom - in one
        // frame, all seven of them - and letting a line arriving from that edge keep the words
        // that are still outside the window was measured and does not help. Nor does keeping
        // every word wherever it is, which is worse: a word nowhere near the window counts as
        // one the page has scrolled away from, and that is what decides whether the plan still
        // describes the screen, so every pass believed it had moved on. Those lines are simply
        // not in the plan yet.
        p.boxes = made.filter { b ->
            val r = b.rect
            r.left >= p.viewport.left - 1 && r.top >= p.viewport.top - 1 &&
                r.right <= p.viewport.right + 1 && r.bottom <= p.viewport.bottom + 1 &&
                !covered(r, p.exit)
        }
        return p.boxes.isNotEmpty()
    }

    /**
     * A recycled row now carrying a line nobody has read, transcribed where it stands.
     *
     * Everything the planning does for a line is done here for this one, and its characters
     * are measured if this pass has room to ask - the same allowance the strip read works to,
     * since both are asking a moving app to lay text out again.
     */
    private fun rewriteRow(p: Planned, says: String, into: MutableList<WordBox>) {
        if (says.isBlank() || says.length > MAX_TEXT) return
        if (rewrittenThisPass >= MEASURE_MOVING_MAX || !Dictionary.ready) return
        val picks = Transcriber(SettingsStore.current.density).plan(says)
        if (picks.isEmpty()) return
        rewrittenThisPass++
        val now = Planned(
            p.node, picks.first().start, picks.last().end - picks.first().start + 1,
            picks, says, android.graphics.Rect(p.clip), android.graphics.Rect(p.viewport), p.exit,
        )
        if (!measureLine(now, allowedToAsk = true)) return
        val c = colours.decide(
            now.text, now.measuredAt?.top ?: -1, colorRect(now), impatient = true,
        ).colours
        if (c != null) {
            now.boxes = now.boxes.map { it.copy(background = c.background, ink = c.ink) }
        }
        cachedPlan = cachedPlan.map { if (it === p) now else it }
        into.addAll(now.boxes)
    }

    /**
     * Read the strip of screen the movement has brought into view and add it to the plan.
     *
     * Runs on the worker in the middle of following, so the words stay on the layer and keep
     * moving while it happens. On the worker and not on a thread of its own: that was tried,
     * on the reasoning that the following should not have to wait for it, and it is worse.
     * These calls all queue on the app's own main thread, so a reading running beside the
     * following contends with it rather than overlapping it - the following managed ten passes
     * through a drag instead of twenty-four, and its typical pass went from 53ms to 133ms. What it costs is one fetch of the window and a walk of a band
     * rather than of a screen: measured on a page of text, twenty milliseconds against the
     * hundreds a full read takes.
     */
    private fun extendInBand(held: List<Planned>) {
        val began = android.os.SystemClock.uptimeMillis()
        val band = arrivingBand(held) ?: run {
            if (BuildConfig.DEBUG) android.util.Log.d("Phonetix", "BAND none to read")
            return
        }
        // From the window. Walking from the thing the page scrolls in instead was tried, on
        // the reasoning that it holds the lines arriving and none of the app's furniture: no
        // better, a strip costing 106 to 214ms against 132 either time, because finding that
        // container means walking up from a line and asking each parent where it is, which
        // costs what it saves.
        val root = rootInActiveWindow ?: run {
            if (BuildConfig.DEBUG) android.util.Log.d("Phonetix", "BAND no root")
            return
        }
        val gotRoot = android.os.SystemClock.uptimeMillis()
        val settings = SettingsStore.current
        val fresh = ArrayList<Planned>(8)
        val seen = ArrayList<Painted>(32)
        runCatching {
            plan(root, Transcriber(settings.density), fresh, Budget(), Stats(), band, seen)
        }
        val walked = android.os.SystemClock.uptimeMillis()
        if (fresh.isEmpty()) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "BAND ${band.top}..${band.bottom} held nothing")
            }
            return
        }
        // Everything already held is brought up to where it now stands, so that one shift
        // describes lines read at two different moments.
        val already = HashSet<String>(held.size * 2)
        for (p in held) {
            p.rebase(lastShiftX, lastShiftY)
            p.measuredAt?.let { already.add(p.text + "@" + (it.top / LINE_SAME_PX)) }
        }
        val added = ArrayList<Planned>(held)
        var asked = 0
        for (p in fresh) {
            // A line the walk found has been planned but not measured: where its characters
            // sit is a separate question and a costly one, so only a couple are asked per
            // strip and the rest wait for the strip after.
            if (!measureLine(p, allowedToAsk = asked < MEASURE_MOVING_MAX)) continue
            if (asked < MEASURE_MOVING_MAX) asked++
            val at = p.measuredAt ?: continue
            if (already.add(p.text + "@" + (at.top / LINE_SAME_PX))) added.add(p)
        }
        lastShiftY = 0f
        lastShiftX = 0f
        cachedPlan = added
        // Only what is still on the screen, and only the plan's own lines.
        //
        // Both of these are added to on every strip read and neither was ever taken from, so
        // a page scrolled for a while carried a plan of lines that had left long ago and a
        // list of painted rectangles that grew without bound - and every word of every pass
        // is tested against every one of those to see what covers it. Photographed, that is a
        // page that gets worse the more it is scrolled: three swipes in a row kept 65%, then
        // 33%, then 12% of their lines carrying a transcription.
        val screen = android.graphics.Rect(
            0, -resources.displayMetrics.heightPixels,
            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels * 2,
        )
        cachedPainted = (cachedPainted + seen)
            .filter { android.graphics.Rect.intersects(it.rect, screen) }
            .takeLast(MAX_PAINTED)
        cachedPlan = cachedPlan.filter { p ->
            val at = p.measuredAt ?: return@filter true
            android.graphics.Rect.intersects(at, screen)
        }
        lastFullReadAt = android.os.SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "BAND ${band.top}..${band.bottom} read ${fresh.size}, plan now ${added.size}, " +
                    "took ${android.os.SystemClock.uptimeMillis() - began}ms " +
                    "(root ${gotRoot - began}ms, walk ${walked - gotRoot}ms)",
            )
        }
    }

    /** A node's box and where it sits in draw order, for working out what covers what. */
    private class Painted(val enter: Int, val rect: android.graphics.Rect)

    /**
     * The strip of screen a moving page is bringing into view, or null to read the lot.
     *
     * Where the words already planned end is where the new ones begin: below them when the
     * content is travelling up, above them when it is travelling down. Null whenever there is
     * nothing to add to - no plan, a page standing still, a screen that has been replaced -
     * because then the whole screen is what has arrived.
     */
    private fun arrivingBand(held: List<Planned>): android.graphics.Rect? {
        if (held.isEmpty()) return null
        if (kotlin.math.abs(speedY) <= SETTLING_PX_PER_MS) return null
        val dm = resources.displayMetrics
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (p in held) {
            val at = p.measuredAt ?: continue
            if (p.boxes.isEmpty()) continue
            top = minOf(top, at.top + lastShiftY.toInt())
            bottom = maxOf(bottom, at.bottom + lastShiftY.toInt())
        }
        if (top > bottom) return null
        // Content travelling up leaves a gap at the bottom, and the other way round. A hair
        // of overlap, so a line straddling the edge of the band is read rather than missed.
        return if (speedY < 0f) {
            android.graphics.Rect(0, maxOf(0, bottom - LINE_SAME_PX), dm.widthPixels, dm.heightPixels)
        } else {
            android.graphics.Rect(0, 0, dm.widthPixels, minOf(dm.heightPixels, top + LINE_SAME_PX))
        }
    }

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
        // Bounds travel with the node, so narrowing the clip on the way down costs nothing
        // and is the only thing that knows a word has scrolled under a toolbar: a node can
        // be "visible to user" while the part of it holding the word is covered.
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        // Only what can be seen. Reading a screen beyond each edge as well was tried twice,
        // because the lines a drag is missing are exactly the ones about to arrive: it finds
        // nothing. The same ten lines are planned either way, since a node the app has not
        // shown is not visible to the accessibility tree and does not report a rectangle
        // outside the screen for one to reach.
        val text = if (visible) node.text?.toString() else null
        stats.ipcNs += System.nanoTime() - mark
        stats.calls++
        if (!visible) return
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
        /**
         * How many lines a single read may measure from scratch while the page is moving.
         *
         * Every one of them makes the app lay that line out again, which is why there is a
         * limit at all. What it buys is the transcriptions on the lines scrolling into view:
         * the share of a moving page's lines carrying one is about a fifth without this.
         *
         * Twelve, measured against six over three drags each: three fifths of the lines
         * against under a half, and steadier - six ranged from a fifth to two thirds. It was
         * set to six on an earlier measurement taken while a separate fault kept the colours
         * from ever being read, which held back the very lines this was measuring.
         *
         * What it costs the app is too small to see at this figure: the page reported 73 to
         * 75 positions through a drag against 76 to 77 with none of this, in steps of the
         * same size. Somewhere above it that stops being true.
         */
        @Volatile
        @JvmStatic
        var MEASURE_MOVING_MAX = 12

        /** How many painted rectangles are kept for working out what covers what. A screen
         *  holds a hundred or so; beyond that they are old ones from before a scroll. */
        const val MAX_PAINTED = 256

        /** Two readings of the same text this close together are the same line. */
        const val LINE_SAME_PX = 24

        const val KEPT_ENOUGH = 0.7f
        /** However fast a screen turns over, it is not read again more often than this: a
         *  read costs tens of milliseconds during which nothing is followed at all. */
        const val FULL_READ_TURNOVER_MS = 350L

        /**
         * How much of what a moving page was carrying has to have scrolled away before the
         * strip that has arrived is read, and how long apart two such readings may be.
         *
         * Both are set by what such a reading costs, which is not what it looks like. Only a
         * few lines are measured, but finding them means asking every node on the way down
         * where it is, and the walk alone timed at about 195ms on this emulator against 4 to
         * 121ms to fetch the window - so a strip costs two to three hundred milliseconds,
         * about what a whole screen costs, and the words are carried blind for all of it.
         *
         * Even so it is worth reading early. Waiting until a quarter of the words had gone
         * meant the first strip came after the bottom of the screen was already bare, and the
         * reading never caught up with the drag; a tenth reads sooner and holds. Measured
         * over nine swipes each: the middle swipe carried three fifths of its lines against
         * a third. An earlier sweep of this said the opposite and was taken while a separate
         * fault kept the colours from ever being read on a page being followed, so nothing a
         * strip found could be drawn.
         */
        @Volatile
        @JvmStatic
        var MOSTLY_GONE = 0.10f

        @Volatile
        @JvmStatic
        var TURNOVER_MOVING_MS = 200L
        /** How many lines are asked where they are before the rest are carried with them. */
        /** How many lines are asked where they are each pass. Settable so a test can sweep
         *  it: each anchor is a round trip into an app that is busy scrolling, so the count
         *  decides how often the words can be measured at all. */
        @Volatile
        @JvmStatic
        var ANCHORS = 3

        /** How many are asked while there is a fresh speed to check the answer against.
         *  Settable so a test can compare this against asking the full set every pass. */
        @Volatile
        @JvmStatic
        var ANCHORS_WHEN_KNOWN = 1
        /** The speed, in pixels a millisecond, past which a pass buys nothing by asking more
         *  lines: at this rate the page moves a line's height in the time one answer takes. */
        /**
         * Below this the lines are asked whether they are still the lines they were; above
         * it the pass spends its round trips on where the words are instead.
         *
         * It was a pixel a millisecond, which is faster than an ordinary drag - so through
         * every drag a reader makes, each pass spent two to four extra round trips into an
         * app already busy laying out a scroll, on top of the one that measures the movement.
         * Passes are what the whole thing is bound by: photographed through drags of a page
         * that does not recycle, skipping the checks took the share of lines carrying a
         * transcription from three fifths to over three quarters, and the typical
         * transcription no further from its word. On a list, where the checks are also what
         * notices a recycled row and transcribes it, the two cancel and it makes no odds.
         *
         * So they wait for the page to be nearly stopped, which is when what they protect
         * matters: a plan about to be followed on a screen standing still.
         */
        @Volatile
        @JvmStatic
        var HURRIED_PX_PER_MS = 0.3f
        /** Shorter than this, a window standing over the app is one of ours: every
         *  transcription is a window the height of a word. A keyboard is half a screen. */
        const val MIN_BLOCKER = 240

        /** How long to let a system window - the shade, the recents screen - finish coming
         *  or going before looking at what is in front, and how many times to try. */
        const val AFTER_A_SYSTEM_WINDOW = 600L
        const val LOOK_AGAIN_MS = 500L
        const val LOOK_AGAIN_TIMES = 6

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
