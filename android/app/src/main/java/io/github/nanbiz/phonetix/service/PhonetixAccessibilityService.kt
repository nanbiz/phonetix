package io.github.nanbiz.phonetix.service

import android.accessibilityservice.AccessibilityService
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.nanbiz.phonetix.BuildConfig
import io.github.nanbiz.phonetix.MainActivity
import io.github.nanbiz.phonetix.core.Dictionary
import io.github.nanbiz.phonetix.core.Eld
import io.github.nanbiz.phonetix.core.IpaSymbols
import io.github.nanbiz.phonetix.core.Language
import io.github.nanbiz.phonetix.core.Pick
import io.github.nanbiz.phonetix.core.Reading
import io.github.nanbiz.phonetix.core.Settings
import io.github.nanbiz.phonetix.core.SettingsStore
import io.github.nanbiz.phonetix.core.Accents
import io.github.nanbiz.phonetix.core.Wording
import io.github.nanbiz.phonetix.ui.Tokens
import io.github.nanbiz.phonetix.ui.themeNamed
import io.github.nanbiz.phonetix.core.Answer
import io.github.nanbiz.phonetix.core.Languages
import io.github.nanbiz.phonetix.core.Packs
import io.github.nanbiz.phonetix.core.Placement
import io.github.nanbiz.phonetix.core.Speech
import io.github.nanbiz.phonetix.core.Translator
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
    private lateinit var hover: HoverController

    /** Whether the screen has been replaced by its own translation. Asked from paths that run
     *  before the service has finished building itself - a scroll event arrives as soon as the
     *  service is bound - so it answers for a page that does not exist yet. */

    /** What the screen last turned out to be in, which is one half of the translation
     *  direction. Held because the engine is opened for a direction, not per screen. */
    @Volatile
    private var lastScreenLanguage: String? = null
    /** The language the engine was last opened for reading into, so choosing another opens it
     *  for the new direction rather than waiting for the screen's own language to change. */
    private var lastTarget: String? = null
    private lateinit var tooltip: TooltipController
    private lateinit var speaker: Speaker
    private lateinit var io: Handler
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    /** Asks the system about its windows, which is work that can block. */
    private lateinit var watcher: Handler
    private lateinit var sampler: ScreenSampler
    private lateinit var bystanders: Bystanders
    private var settingsWatch: kotlinx.coroutines.CoroutineScope? = null
    private val layouts = LineLayouts()
    private var generation = 0
    /** Where other windows - a keyboard above all - stand over the app being read. */
    @Volatile private var blockers: List<android.graphics.Rect> = emptyList()
    /** Looks again once a system window has finished coming or going. */
    private val afterASystemWindow = Runnable { scrollOnly = false; schedule(0L) }

    /**
     * The page has stopped moving: put the words back where they now are.
     *
     * The lines are the ones already found, asked where they are now. A scroll moves words
     * without changing them, so walking the tree again is a few hundred milliseconds spent
     * learning what is already known - it is what this pass was cut from, measured, when the
     * overlay was made fast: seventy-odd calls into the app become none, and the pass goes
     * from hundreds of milliseconds to single figures. A page that has genuinely changed
     * falls back to a full read on its own, from the check inside the pass.
     */
    private val afterMoving = Runnable {
        scrollOnly = cachedPlan.isNotEmpty()
        replaceNow = scrollOnly
        schedule(0L)
    }

    /** Whether the next pass is the one that puts the words back after a movement, which asks
     *  the lines it already has where they are now rather than looking for them again. */
    @Volatile private var replaceNow = false

    /** Anything that is only worth naming to say it is deliberately unused. */
    private fun void(@Suppress("UNUSED_PARAMETER") value: Any?) = Unit

    /** How many times running the thing in front has been something we do not transcribe,
     *  so a shade closing is waited out and a launcher sitting there is not polled for ever. */
    @Volatile private var lookAgain = 0
    /** Apps already named in the log as never standing still. */
    private val waitedOut = HashSet<String>(4)
    /** Apps already named in the log as giving no character bounds, so each is said once. */
    private val noCharacters = HashSet<String>(4)

    /** Where the keyboard's top edge was when it was last looked at, so that windows changing
     *  for any other reason costs nothing. */
    @Volatile private var lastKeyboardTop = -1

    /** The app whose arrival has already had the words taken down, so that one switch does
     *  it once however many events the app sends while it comes up. */
    @Volatile private var clearedFor: String? = null
    /**
     * How many reads in a row have found exactly the screen that was already known.
     *
     * A screen nobody is touching is read again every [FULL_READ_MS] regardless, because a
     * list can change without announcing it. Asking the app for its window blocks the whole
     * service while it answers - the platform holds one lock for that call and for delivering
     * events - and an app that is starting up takes its time: measured at 1445ms, during
     * which no event reaches the service and nothing can be drawn. So the reader switched
     * apps and the words of the app before stayed painted over the new one for as long as
     * that call took. Reading a screen that has not changed is what puts that call in flight
     * for no gain, so the quiet gets longer the longer it lasts, and anything at all resets
     * it.
     */
    @Volatile private var sameAgain = 0
    /** When the app in front last changed, so the words can come off the screen first. */
    @Volatile private var switchedAt = 0L
    /** The direction the reader asked for and this phone has no model for, or nothing. */
    @Volatile private var missingDirection: String? = null
    /** What the replacing was before it was switched off, so a press puts back what was there
     *  rather than a mode the reader never chose. */
    @Volatile private var lastReplacing: String = "both"

    /** Which screenful is being read: a number that changes whenever the plan is built
     *  afresh, so anything that takes a moment can tell whether it still belongs. */
    @Volatile private var screenful = 0

    /** When that screenful was read, so anything that photographs the screen can let the app
     *  finish drawing it first. */
    @Volatile private var screenfulAt = 0L

    /** What the screen being read was read as, which is the language its words are in. */
    @Volatile private var readingSource = Language.OURS

    /** What the last full read made of the screen's tree, for [dumpState]: how many nodes
     *  carried text, and how many were passed over, and why. A screen the mark has nothing
     *  to answer about is a screen whose text never got this far, and nothing outside the
     *  walk could say which of its reasons was the one. */
    @Volatile private var lastWalk: org.json.JSONObject? = null

    /**
     * How tall a row is on the screen being read, where the app will not say where its
     * characters are and the layout has to be guessed. Zero until a read has had to guess.
     */
    @Volatile private var guessedRowTall = 0f

    /** How many lines of the last read could not be placed because the app would not say
     *  where its characters are: see [dumpState]. Those lines are read and then dropped, so
     *  the mark has nothing to answer about on that part of the screen. */
    @Volatile private var unplaceable = 0

    /** And how many lines that read planned at all, to say it against. */
    @Volatile private var plannedLines = 0
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
    /** Below this many words, a screen is too small to judge a follow by what it kept. */
    private val FEW_WORDS = 6

    /** How small a share of the plan's words a follow may keep and still be a follow: one in
     *  this many. */
    private val KEPT_ENOUGH = 3

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
        // So a test can ask this service to measure something about itself. Nothing in the
        // app reaches the service otherwise: it is started by the system, not by us.
        running = this
        SettingsStore.init(this)
        // A way to ask the service what it believes, from a phone in a reader's hand at the
        // moment something is wrong.
        //
        // Debug builds only, and exported there because a broadcast from adb arrives as if
        // from another app: a receiver that is not exported never hears it, which is what
        // "Broadcast completed: result=0" and no file meant. A release build registers
        // nothing, so nothing on the device can ask a reader's phone for this.
        if (BuildConfig.DEBUG) {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                dumpAsked,
                android.content.IntentFilter(StateDump.ACTION),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                probeAsked,
                android.content.IntentFilter(StateDump.PROBE),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }
        val thread = HandlerThread("phonetix-scan").apply { start() }
        worker = Handler(thread.looper)
        // A thread of its own for asking the system what its windows are. It cannot be the
        // main thread, because those calls reach into an app that is busy and block - that is
        // an ANR, seen as "Phonetix isn't responding" with the stack in getRootInActiveWindow
        // under onAccessibilityEvent. It cannot be the scan worker, which scheduling clears,
        // nor the io thread, which loads a seventeen megabyte engine: queued behind that, the
        // keyboard was noticed too late to move the button and it sat behind the keys.
        val windowThread = HandlerThread("phonetix-windows").apply { start() }
        watcher = Handler(windowThread.looper)
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
        // The circle: a way to read a word that does not take the screen's touches. A
        // transcription that can be tapped swallows the swipe that began on it, and on a page
        // of text that is most of the page, so the alternative is one mark the reader drags,
        // with the circle that does the looking riding clear above the hand.
        hover = HoverController(
            this,
            // Nothing to ask about while the page has been replaced: the words the boxes
            // belong to are covered by the translation, and a card about one of them would be
            // about a word the reader cannot see.
            wordAt = { x, y -> overlay.wordAt(x, y) },
            onScreen = { overlay.onScreen() },
            // The last look's answer rather than a fresh one: where the button parks is decided
            // on the main thread, and asking the system for its windows there is a call that
            // can block. It is looked at whenever the windows change, which is exactly when a
            // keyboard comes or goes.
            keyboardTop = { lastKeyboardTop },
            onWord = { box -> main.post { if (box != null) tooltip.show(box) else tooltip.hide() } },
            onHand = { y ->
                tooltip.clearOf(y)
                // While the mark is being dragged, the words under it must be where the app
                // has them now. A screen that moves without scrolling - a message being
                // written into a conversation, a list growing - announces nothing this service
                // acts on, so between reads the boxes drift behind the text: the reader points
                // at one word and is answered about the word that used to be there. Reading is
                // not free, so it is done at most this often, and only while a finger is down.
                // Asked for rather than scheduled: scheduling cancels whatever the worker
                // was about to do, and a finger moves thirty times a second, so every read
                // was cancelled by the next move before it could run - one read in six
                // seconds of dragging. This stands in the queue and stands down if anything
                // else reads first.
                //
                // A read of the tree rather than a follow: a follow carries the boxes by how
                // far each line says it has moved, and a screen that grows without scrolling
                // says nothing at all.
                readWhileDragging()
            },
            // Held and let go where it started: the word the reader is looking for, rather
            // than one somebody else wrote. The app opens on the screen that asks for it.
            // Held: the whole screen in the reader's own language, and held again to put it
            // back. The heavier of the two questions on the heavier gesture.
            onHold = {
                // Turns the replacing on and off, and nothing else.
                //
                // It used to put the whole screen into the reader's own language, which is a
                // thing they asked for once and reach for rarely; what they reach for
                // constantly is having the words back. So the heavier gesture on the button
                // does the heavier of the two everyday things, and the settings screen says
                // so under the switch it shares.
                main.post { tooltip.hide() }
                val now = SettingsStore.current
                val next = if (now.layer == "off") lastReplacing else "off"
                if (now.layer != "off") lastReplacing = now.layer
                SettingsStore.setLayer(next)
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("Phonetix", "HELD layer $next")
                }
                hover.saying(next != "off")
                main.post {
                    android.widget.Toast.makeText(
                        this@PhonetixAccessibilityService,
                        Wording.says[if (next == "off") "replacing-off" else "replacing-on"]
                            .orEmpty(),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                readAgain()
            },
            // A drag that passed over nothing writes down what this believed at that moment,
            // so "it does nothing" can be answered from the phone afterwards rather than from
            // whatever anyone manages to catch live. Debug builds only, and at most one every
            // half minute, so a reader waving the mark about does not fill their phone.
            onFoundNothing = {
                val now = System.currentTimeMillis()
                if (BuildConfig.DEBUG && now - lastNothingDump > NOTHING_DUMP_MS) {
                    lastNothingDump = now
                    main.post {
                        runCatching { dumpState() }.onFailure {
                            android.util.Log.w("Phonetix", "the state could not be written", it)
                        }
                    }
                }
            },
            onPhrase = { run ->
                // The clause as the app wrote it, not the words the circle happened to land
                // on: only some words of a line carry a transcription, so a run made of those
                // would ask about "dog road bench" where the reader swept a sentence.
                val text = phraseOf(run)
                val across = android.graphics.RectF(run.first().rect)
                for (box in run) across.union(box.rect)
                val first = run.first()
                // What the screen was found to be in, which is the same thing the transcriptions
                // were made from. A word box carries no language of its own, and taking the
                // reader's own as the fallback asks the engine to translate English into
                // English and answers nothing.
                val source = first.language.ifEmpty { lastScreenLanguage ?: Language.OURS }
                val target = SettingsStore.current.into.ifEmpty { source }
                // Off the main thread: a clause is a pass through the translation model, and
                // the finger that finished the sweep is still on the screen.
                io.post {
                    val answer = Reading.phrase(text, source, target)
                    main.post {
                        if (answer == null) {
                            // Nothing an engine could answer. The card that would open would
                            // carry the reader's own words back at them, so none opens.
                            android.util.Log.d(
                                "Phonetix", "PHRASE unanswered $source->$target: $text")
                            return@post
                        }
                        tooltip.showPhrase(
                            first.copy(rect = across, word = text, ipa = "", full = ""),
                            answer,
                        )
                    }
                }
            },
            // Tapped: the word the reader is looking for, which is the question they ask
            // oftenest and so belongs on the easiest gesture.
            onTap = { main.post { openSay() } },
        )
        // Which language a line is in, which decides whether it is transcribed at all. Read
        // on the io thread: it is a megabyte of ngrams and the service must not wait for it.
        io.post { Eld.ensureLoaded(this) { schedule(0L) } }
        // The synthesiser, for the words no pack holds. On the io thread: the first call
        // unpacks a few megabytes out of the apk, and the service must not wait for it. The
        // screen is read again once it is up, so the words that were bare get their
        // transcription without the reader doing anything.
        io.post { if (Speech.start(this)) readAgain() }
        // The translation engine, for the words no pack holds. Opened for the direction the
        // reader is reading in, and only once a model for it has been fetched: a reader who
        // has chosen no language to read into is not translating, and one whose host has no
        // model for their pair is not either.
        io.post { openTranslator() }
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
            screenful = { screenful },
            screenfulAt = { screenfulAt },
            hideOverlay = { overlay.holdDown(true) },
            showOverlay = { overlay.holdDown(false) },
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
                    // The lens is up whenever there is something to read with it.
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "Phonetix",
                            "LENS enabled=${s.enabled} wanted=${s.lens} up=${hover.showing}",
                        )
                    }
                    // Put up where there is something to read with it: the pass that reads
                    // the screen takes it away again over anything this does not annotate.
                    if (s.enabled && s.lens) hover.show() else hover.hide()
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
        Dictionary.ensureLoaded(this) {
            Packs.openHeld(this)
            schedule(0L)
        }
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
            if (wasEnabled && ::overlay.isInitialized) overlay.hideNow()
            wasEnabled = false
            return
        }
        wasEnabled = true
        // Something happened on a screen, so it is not a screen to leave alone.
        sameAgain = 0
        // The app in front changed, so nothing held from the app before it is worth a thing.
        //
        // The words are placed where that app had them, and the new one has its own text in
        // its own places. Two things then go wrong at once: what is painted stays painted
        // over the new screen, and the next scroll it announces is followed - which carries
        // the old app's plan rather than reading the new app at all, because following
        // deliberately does not fetch the window. Filmed while switching between two apps,
        // that is a pile of chips in the corner of the settings screen, each carrying a word
        // from the page before.
        if (from != null && cachedPackage != null && from != cachedPackage &&
            from != packageName && SettingsStore.allows(from) && clearedFor != from
        ) {
            // Once for each app arrived at, not on every event it sends while it is loading:
            // the plan is cleared and a read scheduled, and a stream of events each clearing
            // it again pushes that read out of the way for as long as the app keeps talking -
            // which on a real app is a screenful of English with nothing on it.
            clearedFor = from
            cachedPlan = emptyList()
            scrollOnly = false
            // Taken down here rather than posted for later: an event arrives on the main
            // thread, so this is already the thread that draws, and one frame is all it
            // takes.
            if (::overlay.isInitialized) overlay.hideNow()
            switchedAt = android.os.SystemClock.uptimeMillis()
            if (BuildConfig.DEBUG) android.util.Log.d("Phonetix", "SWITCHED to $from")
        }
        // Nothing is read for a moment after arriving at another app.
        //
        // Asking an app that is still starting up for its window blocks the service whole:
        // the platform holds one lock for that call and for delivering events, so no event
        // arrives, no message on the main thread runs, and nothing is drawn until the app
        // answers - measured at 1445ms on an app being brought to the front. The words were
        // taken down just above, and a read begun in the same breath froze the service before
        // the frame that would have taken them off the screen could be drawn, so they stayed
        // over the new app for the whole of it. This waits long enough to draw that frame,
        // and the app is usually readier by then as well.
        val settling = android.os.SystemClock.uptimeMillis() - switchedAt < AFTER_A_SWITCH
        if (settling) {
            schedule(AFTER_A_SWITCH, trailing = true)
            return
        }
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
            // What the app says it scrolled by, against what it really moved, so a test can
            // say whether these are worth carrying words on. They disagree - 1918 reported
            // against 1440 real, once - and the question is whether they disagree by a
            // constant, which could be learned, or unpredictably, which could not.
            if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.util.Log.d(
                    "Phonetix",
                    "SAIDSCROLL ${android.os.SystemClock.uptimeMillis()} " +
                        "dy=${event?.scrollDeltaY} y=${event?.scrollY} maxY=${event?.maxScrollY} " +
                        "from=${event?.fromIndex} to=${event?.toIndex} " +
                        "items=${event?.itemCount} cls=${event?.className}",
                )
            }
            if (::tooltip.isInitialized) tooltip.hide()
            // How far the view says it has just moved. Where an app fills this in it is
            // exact, and it arrives without being asked for - so the words are moved by it at
            // once rather than waiting for the next reading, which on a slow app is hundreds
            // of milliseconds and several lines away. Apps that recycle their rows report
            // nothing here (a Compose list says -1 an event, a ListView says 0), and nothing
            // is what this then does.
            val said = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event?.scrollDeltaY ?: 0
            } else {
                0
            }
            // What a list that reports no distance still reports: where it has got to.
            //
            // A Compose list fills in no delta at all, and its bounds do not move during a
            // fling either - twenty readings running put every word at the same pixel while
            // the page travelled seven hundred - so there is nothing to measure a speed from
            // and the words stood still on a moving page. The one thing it does report is
            // scrollY, and it moves continuously.
            //
            // It is not in pixels. A lazy list has no scroll offset to give, so it estimates
            // one as its first visible item's index times five hundred plus how far into that
            // item it has scrolled, which is real pixels. Undoing that needs the height of an
            // item, which is on the screen to be measured.
            val pseudo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event?.scrollY ?: -1
            } else {
                -1
            }
            val exact = kotlin.math.abs(said) >= SAID_TOO_SMALL
            // The estimate is for lists that report no distance at all, which they mark with
            // the undefined value rather than with a small one. An app that fills this in
            // reports its offset in pixels too, and putting that through the conversion below
            // inflated it: the settings app's words were carried 124% of what it moved.
            val carried = when {
                exact -> said.toFloat()
                said == UNDEFINED_SCROLL -> fromPseudoScroll(pseudo, event?.packageName?.toString())
                else -> 0f
            }
            if (BuildConfig.DEBUG && PROBE_TREE) {
                android.util.Log.d(
                    "Phonetix", "CARRIED said=$said pseudo=$pseudo row=$rowHeight -> $carried",
                )
            }
            // When the page was where this event says it was, which is not when we hear about
            // it: an accessibility event carries the moment it was made.
            val told = event?.eventTime ?: android.os.SystemClock.uptimeMillis()
            if (BuildConfig.DEBUG && PROBE_TREE) {
                android.util.Log.d(
                    "Phonetix",
                    "LATE ${android.os.SystemClock.uptimeMillis() - told}ms",
                )
            }
            // Nothing of ours on a page that is moving.
            //
            // The words were carried along with the scroll instead, predicted between
            // readings. What a reader saw was a screenful of transcriptions sliding at a
            // slightly different speed to the words under them - and every apparent delay in
            // the app is a word sitting where its word no longer is. So they come down the
            // instant the page moves and go back the instant it stops: the page is either
            // being read or being scrolled, and only one of those needs them.
            if (::overlay.isInitialized) main.post { overlay.hideNow() }
            void(carried)
            void(exact)
            void(told)
            lastMotionAt = android.os.SystemClock.uptimeMillis()
            scrollOnly = scrollOnly || cachedPlan.isNotEmpty()
            // Read again as soon as it has stopped. Short, because it is the gap between the
            // page settling and the words being back, and a reader is looking at it.
            main.removeCallbacks(afterMoving)
            main.postDelayed(afterMoving, AFTER_MOVING)
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
        // A window appearing or going away is usually the keyboard, which the button has to
        // stand clear of: it costs a look at where the button belongs rather than a read of
        // the screen, and the screen itself has not changed.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Asked of the system away from the main thread, always.
            //
            // Both of the questions below are calls into the system, and one of them reaches
            // into the app in front for its window. An app that is busy - which an app whose
            // windows have just changed is - answers in its own time, and this callback is
            // the main thread: the service froze inside it long enough for the platform to
            // put up "Phonetix isn't responding", with the stack standing in
            // getRootInActiveWindow under onAccessibilityEvent. Nothing that can block
            // belongs on this thread.
            //
            // Not through the usual scheduling, which clears the worker to make room for a
            // read: this is not a read and must not cancel one.
            if (::watcher.isInitialized) watcher.post { lookAtTheWindows() }
            return
        }
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

    /**
     * What it costs to ask different kinds of node where they are.
     *
     * Every anchor is a line of text, and a pass spends nearly all of its time waiting for
     * those answers. Whether a text node is dearer to ask than the row that holds it is worth
     * knowing rather than assuming: on a Compose page a node's answer is computed when it is
     * asked for, and a line of text has more to compute than a box.
     */
    fun timeTheAsking(times: Int) {
        worker.post {
            val lines = cachedPlan.filter { it.measuredAt != null }.take(3)
            if (lines.isEmpty()) {
                android.util.Log.d("Phonetix", "ASKING nothing planned to ask")
                return@post
            }
            for (kind in 0..1) {
                var total = 0L
                var asked = 0
                for (round in 0 until times) {
                    for (p in lines) {
                        val node = if (kind == 0) p.node else runCatching { p.node.parent }
                            .getOrNull() ?: continue
                        val began = System.nanoTime()
                        node.refresh()
                        val at = android.graphics.Rect()
                        node.getBoundsInScreen(at)
                        total += System.nanoTime() - began
                        asked++
                    }
                }
                android.util.Log.d(
                    "Phonetix",
                    "ASKING ${if (kind == 0) "a line of text" else "the row holding it"}: " +
                        "${total / 1_000_000}ms for $asked questions",
                )
            }
        }
    }

    /** How fast the platform will hand this service frames, for a test to find out. */
    fun raceTheCamera(times: Int, gapMs: Long) {
        if (::sampler.isInitialized) sampler.raceTheCamera(times, gapMs)
    }

    /**
     * Everything this service believes, written where it can be read from outside the app.
     *
     * What the overlay draws is decided by what it believes, and none of that is visible from
     * a screenshot: an overlay window is invisible to anything reading the screen, and a log
     * line carries one pass's worth. This is the whole of it at one moment, asked for while
     * the thing is going wrong.
     */
    fun dumpState(): java.io.File {
        val settings = SettingsStore.current
        val state = org.json.JSONObject()
            .put("at", System.currentTimeMillis())
            .put("app", org.json.JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("debug", BuildConfig.DEBUG))
            .put("settings", org.json.JSONObject()
                .put("on", settings.enabled)
                .put("layer", settings.layer)
                .put("density", settings.density)
                .put("into", settings.into)
                .put("learning", settings.learning)
                .put("recent", org.json.JSONArray(settings.recent))
                .put("lens", settings.lens)
                .put("touchWords", settings.touchWords)
                .put("narrow", settings.narrow)
                .put("hideStress", settings.hideStress)
                .put("theme", settings.theme)
                .put("dark", settings.dark)
                .put("host", settings.packHost)
                .put("allApps", settings.allApps)
                .put("apps", org.json.JSONArray(settings.apps)))
            .put("screen", org.json.JSONObject()
                .put("package", cachedPackage ?: org.json.JSONObject.NULL)
                // What the last read actually read this screen as, rather than the last
                // language any screen was read as: those differ, and the sticky one had a
                // page reported as Spanish while its words were being read as English.
                .put("language", readingSource)
                .put("target", lastTarget ?: org.json.JSONObject.NULL)
                .put("lines", cachedPlan.size)
                .put("walk", lastWalk ?: org.json.JSONObject.NULL)
                // Where the keyboard's top edge is, or zero: the button waits above it, and
                // when it does not, this says whether the keyboard was seen at all.
                .put("keyboardTop", keyboardTop())
                .put("windows", org.json.JSONArray(windowsSeen()))
                .put("linesPlanned", plannedLines)
                .put("linesWithoutCharacters", unplaceable)
                .put("scrollOnly", scrollOnly)
                .put("following", following)
                .put("blockers", org.json.JSONArray().also { out ->
                    for (r in blockers) out.put(StateDump.rect(r))
                }))
            .put("overlay", if (::overlay.isInitialized) overlay.state() else org.json.JSONObject.NULL)
            .put("mark", if (::hover.isInitialized) hover.state() else org.json.JSONObject.NULL)
            .put("card", if (::tooltip.isInitialized) tooltip.state() else org.json.JSONObject.NULL)
            .put("dictionaries", org.json.JSONArray(Packs.held(this)))
            .put("ask", org.json.JSONObject()
                .put("panelUp", asking != null)
                .put("asked", askedFor ?: org.json.JSONObject.NULL)
                .put("into", askedInto ?: org.json.JSONObject.NULL)
                .put("stage", askStage ?: org.json.JSONObject.NULL)
                .put("waitingMs", if (askedAt == 0L) 0 else System.currentTimeMillis() - askedAt)
                .put("machineTrouble", Machine.trouble ?: org.json.JSONObject.NULL))
        return StateDump.write(this, state)
    }

    /**
     * Turned on from outside, for one investigation on a reader's own phone.
     *
     * What an app answers when it is asked where its characters are cannot be reasoned about
     * from here - the app in the reader's hand is the only one that knows - and the answer
     * decides whether a word can be pointed at at all.
     */
    private val probeAsked = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            PROBE_TREE = intent?.getBooleanExtra("on", true) ?: true
            android.util.Log.d("Phonetix", "PROBE ${if (PROBE_TREE) "on" else "off"}")
        }
    }

    /** Asked for from outside: `am broadcast -a io.github.nanbiz.phonetix.DUMP`. */
    private val dumpAsked = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            main.post { runCatching { dumpState() }.onFailure {
                android.util.Log.w("Phonetix", "the state could not be written out", it)
            } }
        }
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) {
            runCatching { unregisterReceiver(dumpAsked) }
            runCatching { unregisterReceiver(probeAsked) }
        }
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
                if (BuildConfig.DEBUG) android.util.Log.d("Phonetix", "LOOPIDLE after ${since}ms")
                following = false
                // One last read, of the lines already in hand.
                //
                // This used to throw them away and walk the tree again, because the words
                // were carried along by how far each line reported it had moved and that
                // drifts: a list that recycles its rows, a line answering for another line.
                // Nothing is carried any more - the words come down while the page moves -
                // so there is no accumulated error to correct, and a full walk here was a few
                // hundred milliseconds at the end of every scroll. The pass falls back to one
                // by itself the moment the lines turn out to be different ones.
                scrollOnly = cachedPlan.isNotEmpty()
                replaceNow = scrollOnly
                runCatching { scan() }
                // And a proper read of the tree afterwards.
                //
                // The pass above only puts the words back where the lines already in hand say
                // they are, and is deliberately not allowed to become a full read. Nothing
                // asked for one after it, so on a screen that keeps announcing changes - a
                // conversation with an answer arriving in it - the loop restarted on every
                // announcement and the tree went unwalked: on the reader's phone, ten lines
                // known and three of them still alive, on a screen full of new text, with
                // nothing for the mark to ask about. This stands down if anything else reads
                // in the meantime.
                readAgainSoon(quietGap())
                return
            }
            runCatching { scan() }
            worker.postDelayed(this, gapMs)
        }
    }

    /**
     * How long to leave a screen that keeps turning out to be the same screen.
     *
     * Doubles up to [FULL_READ_IDLE_MS] and drops back to [FULL_READ_MS] the moment anything
     * happens, so a page being read is still re-read promptly and a page nobody is touching
     * stops putting a blocking call to the app in flight four times a second.
     */
    private fun quietGap(): Long =
        (FULL_READ_MS shl sameAgain.coerceIn(0, 1)).coerceAtMost(FULL_READ_IDLE_MS)

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

    /**
     * Read the tree again while the mark is being dragged over it.
     *
     * Neither of the other two ways would do it. Scheduling cancels whatever the worker was
     * about to do, and a finger moves thirty times a second, so every read was cancelled by
     * the next move before it could run. Asking to read again soon stands down while the
     * follow loop is going, and that loop carries boxes by how far each line reports it has
     * moved - which a screen that grows without scrolling never reports, so the boxes drift
     * behind the text and the reader is answered about the word that used to be there.
     */
    private fun readWhileDragging() {
        if (!::worker.isInitialized || dragReadQueued) return
        dragReadQueued = true
        worker.postDelayed({
            dragReadQueued = false
            following = false
            scrollOnly = false
            runCatching { scan() }
        }, WHILE_DRAGGING_MS)
    }

    /** Whether a read for the drag is already waiting its turn. */
    private var dragReadQueued = false

    private fun schedule(minGap: Long, trailing: Boolean = false) {
        if (!::worker.isInitialized) return
        // Everything the worker was going to do is about to be cleared, this among it.
        dragReadQueued = false
        val mine = ++generation
        val now = android.os.SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG && following) {
            android.util.Log.d(
                "Phonetix",
                "LOOPCUT by " + Throwable().stackTrace.drop(1).take(4)
                    .joinToString(",") { it.methodName },
            )
        }
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


    /**
     * Whether the page is going somewhere.
     *
     * A shift this service measured just now, or one seen a moment ago - either will do, and
     * asking for only one of them is what put the two halves of the drawing out of step. A
     * reading that comes back with the same positions, from an app too busy to have laid
     * itself out again, reads as a speed of nothing while the page is plainly still moving;
     * an app that says nothing at all between its own frames leaves the clock stale while the
     * speed is right. Everything that has to know asks this.
     */
    private fun onTheMove(): Boolean =
        kotlin.math.abs(speedY) > SETTLING_PX_PER_MS ||
            android.os.SystemClock.uptimeMillis() - lastMotionAt < STILL_MS

    private fun scan() {
        val settings = SettingsStore.current
        val sinceFull = android.os.SystemClock.uptimeMillis() - lastFullReadAt
        val settled = android.os.SystemClock.uptimeMillis() - lastMotionAt > STILL_MS
        // A full read is due on a page that has been sitting still, not on the pass that puts
        // the words back the moment a scroll ends: a scroll lasts longer than the age that
        // makes one due, so every scroll ended in a tree walk of a few hundred milliseconds
        // where asking the lines where they are now costs single figures. The walk still
        // happens, a moment later, from the loop that reads a settled page.
        val replacing = replaceNow
        replaceNow = false
        val overdue = !replacing &&
            (sinceFull > FULL_READ_MS && settled || sinceFull > FULL_READ_MOVING_MS)
        // Following needs the lines, which are already in hand; it does not need the window
        // they are in. Asking for the whole window costs thirty to ninety milliseconds while
        // the app is busy scrolling, and every one of those is a millisecond the positions
        // are out of date by the time they are drawn. The window is fetched on the full read
        // that follows shortly after, which is what would notice the app had changed.
        val followOnly = scrollOnly && cachedPlan.isNotEmpty() && cachedPackage != null &&
            settings.density == plannedDensity && !overdue &&
            SettingsStore.allows(cachedPackage) && !bystanders.contains(cachedPackage) &&
            Dictionary.ready
        var root = if (followOnly) null else {
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
        // A window that covers part of the screen does not replace it.
        //
        // A keyboard, the status bar, a heads-up notification: each of them can be the active
        // window while the reader goes on reading the app underneath. Treated as "something
        // else is in front", every transcription came down and the mark was put away, and
        // nothing read that app again - it had not changed, so it announced nothing. A reader
        // who typed a message and then went to ask about a word found nothing under the
        // circle. The shade and the launcher do replace the screen, and those still count.
        val front = root?.packageName?.toString()
        if (root != null && bystanders.contains(front) && !coversTheScreen()) {
            behindTheSystem()?.let {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "Phonetix",
                        "BEHIND $front -> ${it.packageName} (${it.childCount} children)",
                    )
                }
                root = it
            }
        }
        val inFront = root?.packageName?.toString()
        // Said only when the window in front is not the one being read, which is the case
        // worth explaining: the rest of the time it is one line per pass and drowns the log.
        if (BuildConfig.DEBUG && front != inFront) {
            android.util.Log.d(
                "Phonetix",
                "WINDOW front=$front read=$inFront windows=" + runCatching {
                    windows.joinToString(",") { w ->
                        val r = android.graphics.Rect().also { w.getBoundsInScreen(it) }
                        "${w.type}/${w.layer}@${r.top}..${r.bottom}" +
                            (if (w.isActive) "*" else "")
                    }
                }.getOrDefault("?"),
            )
        }
        if (root != null && (!SettingsStore.allows(inFront) || bystanders.contains(inFront) || !Dictionary.ready)) {
            main.post {
                overlay.hideNow()
                // And the circle with it. It belongs to the app being read: parked over a
                // launcher, a keyboard or this app's own screen it is a mark sitting on
                // somebody's home screen with nothing behind it to ask about.
                hover.hide()
            }
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
        // Something worth reading is in front, so the circle is there to reach for. It was
        // taken away over whatever this does not annotate.
        val wanted = SettingsStore.current
        // Put up where it is missing, and put back where it belongs where it is not: a
        // keyboard opening over the foot of the screen swallows a button sitting there, and
        // the screen it opened over is read again on this same pass.
        if (wanted.enabled && wanted.lens) main.post { hover.show() }
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
        // Room for more words where none of them is drawn: the cost of a word kept is the
        // core's answer and a rectangle, and what a pass really spends is the asking of each
        // line for its character positions. A reader who has turned replacing off has only
        // the mark to ask with, and it can only ask about words this pass kept.
        val budget = Budget(
            words = if (settings.layer == "off") MAX_WORDS_SILENT else MAX_WORDS,
        )
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
            plan(root!!, fresh, budget, stats, full, seen)
            // What the core says about the whole screen, in one call: only the lines it found
            // something in stay, and each keeps the span from its first chosen word to its
            // last, because asking an app for a whole paragraph's character boxes costs it
            // real layout work for words nothing will draw.
            // What the screen is in, judged from the whole of it rather than a line: a line
            // says too little, and says it confidently. Nothing is left out for being in the
            // wrong language any more - a screen the packs cannot answer comes back with
            // nothing to draw, which is the same outcome decided in one place.
            lastWalk = org.json.JSONObject()
                .put("withText", stats.withText)
                .put("unseen", stats.unseen)
                .put("clipped", stats.clipped)
                .put("tooLong", stats.tooLong)
                .put("longest", stats.longest)
                .put("overBudget", stats.overBudget)
                .put("maxText", MAX_TEXT)
            val screen = stats.tongue.read()
            // Kept, because the direction the reader is translating in is this and their own
            // language, and the engine has to be opened for a direction before it can answer.
            if (screen.language != null && screen.language != lastScreenLanguage) {
                lastScreenLanguage = screen.language
                val reading = screen.language
                io.post {
                    // How this language's words are said, built out of what the app carries
                    // the first time a screen in it is read. Before this, a reader of anything
                    // but English met a screen the app could not answer at all.
                    Dictionary.ensure(this, reading)
                    // What decides which word a spelling is, for the language on screen. The
                    // pack it belongs to may be the one bundled with the app rather than one
                    // the reader fetched, so this does not hang off a pack opening.
                    Packs.openClassifier(this, reading)
                    openTranslator()
                    // The words it could not answer a moment ago can be answered now.
                    main.post { readAgain() }
                }
            }
            // The reader chose another language to read into. The engine holds one direction
            // open, and it was opened for the old one: without this the phone translates
            // nothing until the screen's own language happens to change, which on a reader's
            // own page is never.
            if (settings.into != lastTarget) {
                lastTarget = settings.into
                io.post {
                    openTranslator()
                    main.post { readAgain() }
                }
            }
            chooseWords(fresh, settings, screen.language, budget)
            planned = fresh
            // A screen counts as a new one when its lines are not the ones that were there
            // a moment ago. Every read builds a plan afresh, so counting each of those as a
            // new screen put off photographing the colours for ever - and a line whose
            // colours have not been read is a line that is not drawn at all.
            val wasHere = cachedPlan.mapTo(HashSet(cachedPlan.size)) { it.text }
            val kept = if (fresh.isEmpty()) 0 else fresh.count { wasHere.contains(it.text) }
            val another = cachedPackage != pkg || kept * 2 < fresh.size
            sameAgain = if (another || kept != fresh.size || fresh.size != cachedPlan.size) {
                0
            } else {
                sameAgain + 1
            }
            cachedPlan = fresh
            if (another) {
                screenful++
                screenfulAt = android.os.SystemClock.uptimeMillis()
                // The words up belong to a screen that is not there any more, and this is not
                // only a question of what a reader sees: the colours of the new screen are
                // read off a photograph of it, and the old screen's transcriptions are in that
                // photograph. Every line of the new page then took its ink from a chip of
                // ours - near-white, on a line the app had drawn in amber. Staying inside one
                // app is enough for this; the package never changed, so nothing else took
                // them down.
                if (::overlay.isInitialized) main.post { overlay.hideNow() }
            }
            cachedPackage = pkg
            // The words on screen are this app's again, so the next arrival at another one
            // takes them down afresh. Left set, this remembered that it had already cleared
            // for that app once and never did it again: the second visit to a screen kept
            // the previous app's transcriptions over it for as long as it was open.
            clearedFor = null
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
            // Where a pass's time actually goes: into the app, or into this. A pass has been
            // seen taking six hundred milliseconds and the app was blamed for it without
            // anyone checking which half it was.
            var askedNs = 0L
            var asks = 0
            var colourNs = 0L
            val moved = ArrayList<WordBox>(16)
            /** How many words the plan held before this pass, to say how much it kept. */
            val had = planned.sumOf { it.boxes.size }
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
                if (at == null || p.boxes.isEmpty()) return@filter false
                // Whole when they were measured, so there is an edge of them to compare.
                if (at.top <= p.viewport.top + 1 || at.bottom >= p.viewport.bottom - 1) {
                    return@filter false
                }
                // And still on the screen now, not merely when the plan was made.
                //
                // A line that has scrolled out of view stops being told where it is: asking
                // it returns the same rectangle pass after pass while the page keeps moving.
                // Believed, that is a page that has stopped - so the words were carried at a
                // shift frozen where the anchor left the screen while the text went on
                // without them, which is a transcription sitting on somebody else's word.
                // Seen on a page of paragraphs: six passes running reporting a shift of
                // exactly -242 through the second half of a drag.
                val now = android.graphics.Rect(at)
                now.offset(lastShiftX.toInt(), lastShiftY.toInt())
                now.top > p.viewport.top + 1 && now.bottom < p.viewport.bottom - 1
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
                    // The row holding the line, when there is one worth asking; otherwise the
                    // line itself.
                    val holder = a.askNode
                    val was = (if (holder != null) a.askAt else a.measuredAt) ?: continue
                    val node = holder ?: a.node
                    val began = System.nanoTime()
                    val alive0 = node.refresh()
                    // The text is read off the line itself either way: it is what says the
                    // row has been handed to a different line, and a row does not carry it.
                    val says = if (alive0 && holder == null) node.text?.toString() else a.text
                    val now = android.graphics.Rect()
                    if (alive0) node.getBoundsInScreen(now)
                    askedNs += System.nanoTime() - began
                    asks++
                    if (!alive0) { gone++; continue }
                    if (says != a.text) { gone++; continue }
                    val readingAt = android.os.SystemClock.uptimeMillis()
                    if (now.isEmpty) { gone++; continue }
                    alive++
                    val shift = shiftOf(was, now, a.viewport)
                    if (BuildConfig.DEBUG && PROBE_TREE) {
                        // What the same line says when it is found again from the root rather
                        // than refreshed in place. If those two disagree, a refreshed node is
                        // answering about a screen that is gone.
                        android.util.Log.d(
                            "Phonetix",
                            "ASKED holder=${holder != null} was=$was now=$now " +
                                "measuredAt=${a.measuredAt} shift=$shift " +
                                "of '${a.text.take(14)}'",
                        )
                    }
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

            // Asking one rather than three costs one round trip rather than three, and a
            // round trip into a busy app is the whole cost of a pass: 701ms of one pass of
            // 948, and 130 of 137 in the next worst. Asking one whenever the app has lately
            // been slow, as well as whenever a fresh speed is in hand, was tried and does not
            // help - the long passes do not follow one another, so a pass cannot tell from
            // the one before it that it is about to be slow.
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
                val colourBegan = System.nanoTime()
                val decision = colours.decide(
                    p.text, p.measuredAt?.top ?: -1, colorRect(p),
                    impatient = onTheMove(),
                )
                colourNs += System.nanoTime() - colourBegan
                val own = decision.colours
                if (own != null && p.boxes.first().background != own.background) {
                    p.boxes = p.boxes.map { it.copy(background = own.background, ink = own.ink) }
                }
                // Drawn without colours rather than not drawn, while the page is moving:
                // there are no colours to be had until it stops, and a word missing is worse
                // than a word in a palette of ours for the length of a swipe.
                //
                // Moving by the same reckoning the drawing uses, which is the speed this
                // measured or a movement seen a moment ago. Asking only about the speed left
                // a window where the page was plainly still going - the words had just been
                // handed back to the small windows for exactly that reason - and every word
                // that had arrived without colours was withheld through it. On a list, where
                // most of a drag's words are new, that is two swipes in eight showing under a
                // tenth of their lines while the service reported thirty words drawn.
                if (own == null && p.boxes.first().background == 0 && !decision.givenUp &&
                    !onTheMove()
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
            if (PUT_THEM_WRONG_BY != 0f) {
                val wronged = putThemWrong(moved)
                moved.clear()
                moved.addAll(wronged)
            }
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
            // And so does a pass that kept a handful of words out of a screenful.
            //
            // Keeping nothing is caught above; keeping almost nothing was counted a success,
            // and it is what a conversation being written into looks like - the lines under
            // the plan change as the answer arrives, every word of them falls outside the line
            // it was measured in, and the one or two that happened not to change are all that
            // is left. Painted, that is a single word on a page full of them, and the mark can
            // answer about nothing else: dragged over the page, it says "nothing" from one end
            // to the other. Read from a reader's own phone while an answer was being written:
            // one word believed, where a full read of the same screen found forty-seven.
            if (ok && had >= FEW_WORDS && moved.size * KEPT_ENOUGH < had) {
                ok = false
                why = "kept ${moved.size} of $had words"
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
                // Not while it is moving. Reading the strip of screen that has just arrived
                // means walking the app's node tree, and that walk costs two to three hundred
                // milliseconds on a page of any length - two to four of them in a drag of a
                // second and a third. What it buys is the lines that scrolled in; what it
                // costs is every reading that did not happen while it ran, and the following
                // is the only thing keeping the words on their text. Starved of readings the
                // layer stops trusting the speed it has and stops carrying, so the words fall
                // behind the page and then stand still.
                //
                // Ten drags of each page, transcriptions more than a line from their own word
                // through a drag: 36 to 11 percent on a conversation, 28 to 16 on wrapped
                // paragraphs, 9 to 7 on a list. It costs less than nothing in what is drawn,
                // because a stale reading is a reading the layer refuses to draw from: over
                // the same ten drags the layer withheld the words on 75 to 862 samples with
                // the strip read and on 0 to 737 without it.
                val moving = onTheMove()
                if (!moving && had > 0 && clipped > had * (1f - KEPT_ENOUGH) &&
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
                } else if (onTheMove()) {
                    // A pass that measured nothing is not a page that has stopped, and the
                    // handover below already says so: an app too busy to have laid itself out
                    // again hands back the positions it gave last time. Letting that stop the
                    // loop meant one such pass mid-drag left nothing scheduled, and on a page
                    // whose scroll events arrive every ninety milliseconds nothing ran until
                    // the next one. Measured on a drag of a page of paragraphs: a gap of 141ms
                    // between readings where the passes either side cost 12 and 53.
                    //
                    // The clock is not touched, only the loop kept running, so this cannot
                    // hold the following open on a page that really has stopped.
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
                val moving = following && onTheMove()
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
                    if (moving && overlay.inMotion) {
                        overlay.motionMeasured(moved, readAt, speedY, shifted)
                    } else {
                        overlay.endMotion(moved)
                    }
                    android.util.Log.d(
                        "Phonetix",
                        "follow=${took}ms (asked ${askedNs / 1_000_000}ms in $asks, " +
                            "colours ${colourNs / 1_000_000}ms) " +
                            "lines=${planned.size} boxes=${moved.size} moving=$moving " +
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
                    val gap = quietGap()
                    val due = gap - (android.os.SystemClock.uptimeMillis() - lastFullReadAt)
                    readAgainSoon(due.coerceIn(0L, gap))
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
        // Counted afresh for this read: what the state dump reports is this screen, not the
        // sum of every screen since the service started.
        unplaceable = 0
        plannedLines = planned.size
        /** How many lines this read has measured from scratch while the page was moving. */
        var measuredMoving = 0
        var waited = 0
        var stale = false
        /** When the lines of this pass were measured; the page may move while it runs. */
        var readAt = t1
        /** And when each of them was, since they are not measured together. */
        val lineReadAt = HashMap<Planned, Long>(planned.size)
        /** The blocks of this read nobody would place, to be guessed at once it is over. */
        val toGuess = ArrayList<Pair<Planned, android.graphics.Rect>>()
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
            val moving = onTheMove()
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
                        "WAITING $pkg is never still, so its characters are never measured " +
                            "(cap=$MEASURE_MOVING_MAX)",
                    )
                }
                p.boxes = emptyList(); continue
            }
            if (placed == null && moving) measuredMoving++
            val rects = placed
                ?: charRects(p.node, p.from, p.length)?.also { fresh ->
                    againstWhereItIsNow(p, at)
                    layouts.remember(p.text, p.from, p.length, at, p.viewport, fresh)
                }
                ?: run {
                    // Some apps will not say where the characters of a line are. Without them
                    // a transcription cannot be put on one word, so the line is left alone -
                    // and it is worth knowing which apps those are.
                    // Counted every time, not only the first: which app refuses is worth
                    // saying once, but how much of a screen is being lost to it is what
                    // decides whether the mark has anything to answer about at all.
                    unplaceable++
                    // With nothing drawn over the words, a guess at where they are is worth
                    // more than nothing at all: the reader is pointing at a word rather than
                    // reading something placed on it, and an app that will not say where its
                    // characters are - or stops saying while its text is being written into,
                    // which is when a reader is looking at it - otherwise leaves the mark
                    // with a screenful of text and nothing to answer about.
                    if (SettingsStore.current.layer == "off" && p.length > 0) {
                        val rect = p.measuredAt ?: android.graphics.Rect().also {
                            p.node.getBoundsInScreen(it)
                        }
                        // Laid out once the whole screen has been read, because how tall a
                        // row is is a property of the screen rather than of one block: a
                        // short block is mostly the padding around its text, and a block the
                        // screen cuts off reports only the part of its box that is showing,
                        // so either one on its own puts its rows at the wrong height.
                        if (!rect.isEmpty) toGuess.add(p to android.graphics.Rect(rect))
                    }
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
            Placement.boxes(p.picks, rects, p.from, boxes, readingSource)
            // Remember where this line was when its characters were measured, so a scroll
            // can carry its words rather than measuring them again.
            if (remembered != null) p.node.getBoundsInScreen(at)
            p.measuredAt = at
            rememberACheaperNode(p, at)
            // Drop anything the node's ancestors clip away rather than painting a word that
            // is behind something else.
            var w = before
            var outside = 0
            var hidden = 0
            for (i in before until boxes.size) {
                val r = boxes[i].rect
                // In the window, rather than wholly within it. A character's box carries the
                // font's ascent and descent, and an element that wraps its own text is exactly
                // as tall as the text: the box then stands a pixel or two proud of the box
                // that clips it, at the top and the bottom both. Demanding containment threw
                // out every word of every web page for that couple of pixels, which is what a
                // browser showing nothing at all looked like from in here. What this has to
                // catch is a word scrolled out of its list or under a toolbar, and a word
                // whose middle is outside the window is what that is.
                val midX = (r.left + r.right) / 2
                val midY = (r.top + r.bottom) / 2
                val inside = midX >= p.viewport.left && midX <= p.viewport.right &&
                    midY >= p.viewport.top && midY <= p.viewport.bottom
                val behind = inside && covered(r, p.exit, p.clip)
                if (!inside) outside++ else if (behind) hidden++
                if (inside && !behind) { boxes[w] = boxes[i]; w++ }
            }
            if (BuildConfig.DEBUG && PROBE_TREE) {
                android.util.Log.d(
                    "Phonetix",
                    "CLIP kept=${w - before} outside=$outside covered=$hidden " +
                        "viewport=${p.viewport} of '${p.text.take(16)}'",
                )
            }
            while (boxes.size > w) boxes.removeAt(boxes.size - 1)
            p.boxes = boxes.subList(before, boxes.size).toList()
            // Which line of the page these came from, where the page says so by numbering its
            // lines. Only a test fixture does that, and it is what lets a check made of pixels
            // alone compare the line the overlay believes a word is on against the line the
            // word is drawn over.
            if (BuildConfig.DEBUG && MARK_LINES) {
                val numbered = p.text.takeWhile { it.isDigit() }.toIntOrNull()
                if (numbered != null) {
                    p.boxes = p.boxes.map { it.copy(line = numbered) }
                    for (i in before until boxes.size) {
                        boxes[i] = boxes[i].copy(line = numbered)
                    }
                }
            }
        }
        // The blocks nobody would place, laid out evenly now that the whole screen is known.
        //
        // With nothing drawn over the words, a guess at where they are is worth more than
        // nothing at all: the reader is pointing at a word rather than reading something
        // placed on it, and an app that will not say where its characters are - or stops
        // saying while its text is being written into, which is when a reader is looking at
        // it - otherwise leaves the mark with a screenful of text and nothing to answer
        // about.
        //
        // One row height for the whole screen, taken from the longest block on it. A block's
        // own box says how tall its rows are only in so far as its text fills it, which the
        // text of a long block does and that of a short or a cut-off one does not. Measured
        // against the same page answering the request - scripts/proofread/android_guessed.py
        // - letting every block judge its own rows put 123 of 160 words within half a word of
        // where they really are, and the longest block judging for all of them 149.
        if (toGuess.isNotEmpty()) {
            val longest = toGuess.maxByOrNull { (line, _) -> line.length }
            guessedRowTall = longest?.let { (line, rect) ->
                Placement.rowTall(line.length, RectF(rect))
            } ?: 0f
            for ((line, rect) in toGuess) {
                val guessed = Placement.evenly(
                    line.text.substring(
                        line.from,
                        (line.from + line.length).coerceAtMost(line.text.length),
                    ),
                    RectF(rect),
                    guessedRowTall,
                )
                if (guessed.isEmpty()) continue
                val from = boxes.size
                Placement.boxes(line.picks, guessed, line.from, boxes, readingSource)
                // The line keeps its own words, as every measured line does: boxes appended
                // here and left unowned belong to no line, and everything downstream works
                // from the line.
                line.boxes = boxes.subList(from, boxes.size).toList()
                line.measuredAt = rect
            }
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
        // Recency alone here, not the speed as well: this compensates a read that spanned a
        // movement, and a stale speed with no movement behind it made it correct a read that
        // had nothing to correct.
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
                // And where the line was taken to be, which is what every later pass measures
                // its shift against. Carrying the words forward without carrying that leaves
                // the two describing different moments, and the next pass puts the difference
                // straight back: a line's words move by the shift measured from a baseline
                // they no longer sit on.
                p.measuredAt?.offset(0, ahead.toInt())
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
        val stillEnough = !onTheMove()
        val unread = if (!stillEnough) emptyList()
        else planned.filter { it.boxes.isNotEmpty() && colours.wanted(it.text) }
        if (unread.isNotEmpty()) {
            // Every line of the screen, not only the ones that asked. What a capture costs is
            // the capture: the overlay comes down, a frame is taken, and it goes back up.
            // Reading another line out of the frame already in hand costs nothing, and a line
            // whose colours were read on a different screen of the same app - the same words
            // on another background, which is what a page of a chat or a list is full of -
            // has no other way of being told that it has moved on to something else.
            val all = planned.filter { it.boxes.isNotEmpty() }
            colours.read(all.map { colours.key(it.text) to colorRect(it) })
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
                impatient = onTheMove(),
            )
            val c = decision.colours
            if (c != null) p.boxes = p.boxes.map { it.copy(background = c.background, ink = c.ink) }
            // A word with no colours yet is still drawn while the page is moving.
            //
            // Withholding it is right on a page standing still: the colours are a moment away
            // and a word that flickers into a palette of ours and out again is worse than one
            // that arrives a moment late. Drawing first and repainting when the photograph
            // lands was tried, and the photograph is of a screen with our own paint on it:
            // the line's colours then come back as our own fallback gold. Holding the page
            // back for the capture instead cost the very thing the change was for, and the
            // two chasing each other cost seven "Phonetix isn't responding" in one run.
            //
            // But a screen read in the middle of a movement has no colours to be had - reading
            // them means photographing a still screen - so withholding there meant the read
            // that happens during a drag could paint nothing at all.
            if (c != null || decision.givenUp || onTheMove()) painted.addAll(p.boxes)
            if (BuildConfig.DEBUG && c == null && decision.givenUp) {
                android.util.Log.d("Phonetix", "DECIDE none givenUp for '${p.text.take(20)}'")
            }
        }

        // A screen whose words are not all on it yet is not a screen to leave alone, however
        // unchanged it is: the colours of a line are read from a photograph of a still screen,
        // and until they arrive that line carries nothing. Backing off here would have left a
        // screen bare for as long as seven seconds.
        if (painted.size < planned.sumOf { it.boxes.size }) sameAgain = 0

        if (PUT_THEM_WRONG_BY != 0f) {
            val wronged = putThemWrong(painted)
            painted.clear()
            painted.addAll(wronged)
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
            // And what is actually written over each of them. The line above is geometry; a
            // check that a reader is being told what a word means has to see the words.
            val said = StringBuilder("DRAWN ")
            for (b in painted) said.append(b.word).append('=').append(b.ipa).append(' ')
            android.util.Log.d("Phonetix", said.toString())
        }

        // How far apart the lines of this screen sit, which is what an item boundary in an
        // estimated scroll offset is worth. The gap between neighbours rather than a line's own
        // height: a message is one line of a list whether it wraps over one row or four, and it
        // is the message the list counts.
        val tops = planned.mapNotNull { it.measuredAt?.top }.sorted()
        if (tops.size >= 2) {
            val gaps = tops.zipWithNext { a, b -> (b - a).toFloat() }.filter { it > 1f }.sorted()
            if (gaps.isNotEmpty()) rowHeight = gaps[gaps.size / 2]
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
            if (SettingsStore.current.layer == "off") overlay.known(painted)
            else overlay.render(painted)
            android.util.Log.d(
                "Phonetix",
                "plan=${t1 - t0}ms (ipc=${stats.ipcNs / 1_000_000}ms in ${stats.calls} calls, ours=${stats.computeNs / 1_000_000}ms) nodes=${MAX_NODES - budget.nodes} " +
                    "unseen=${stats.unseen} cut=${stats.clipped} " +
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
    /**
     * Bring a line's rectangle up to the moment its characters were measured.
     *
     * Asking a line where its characters are makes the app lay that text out again, which
     * takes long enough that a page being dragged has moved on by the time the answer comes
     * back. The positions describe where the line is now; the rectangle read at the top of the
     * pass describes where it was. Where each character sits inside its line is remembered as
     * the difference between the two, so measuring against the older rectangle stores that
     * travel as though it were part of the layout - and it is kept, so the line is drawn that
     * far from its own text on every pass afterwards. One paragraph measured mid-drag was left
     * a fifth of a screen above its own words, in the same place, run after run.
     */
    private fun againstWhereItIsNow(p: Planned, at: android.graphics.Rect) {
        val now = android.graphics.Rect()
        p.node.getBoundsInScreen(now)
        if (now.isEmpty) return
        // The characters have just been measured where the line is now, and the boxes they
        // are about to be tested against were read when the screen was walked, which on a
        // page that is moving is a different screen. Carrying them by what the line itself
        // travelled puts both on the same one.
        //
        // Without it, a word measured during a movement is tested against the window its line
        // sat in a moment ago and thrown out for being outside it. That is why a page could
        // only be read while it was standing still: measuring one that was moving produced
        // character positions and then dropped every one of them. In a browser, whose page is
        // handed over only while it is being touched, that meant nothing was ever drawn at
        // all.
        val dx = now.left - p.walkedAt.left
        val dy = now.top - p.walkedAt.top
        if (dx != 0 || dy != 0) {
            p.clip.offset(dx, dy)
            p.viewport.offset(dx, dy)
            p.walkedAt.set(now)
        }
        at.set(now)
    }

    /**
     * Remember a cheaper node to ask this line's position from, if there is one.
     *
     * The box holding a line answers faster than the line does, and moves with it: two
     * milliseconds against one on a Compose page, which computes a node's answer when it is
     * asked for and has more to compute for text than for a row. Nearly all of a pass is spent
     * waiting for these answers.
     *
     * Only where the holder is a row rather than a column. A shift is measured from an edge,
     * and a view spanning the document has both its edges off the screen.
     */

    private fun rememberACheaperNode(p: Planned, at: android.graphics.Rect) {
        p.askNode = null
        p.askAt = null
        if (at.isEmpty) return
        val holder = runCatching { p.node.parent }.getOrNull() ?: return
        val around = android.graphics.Rect()
        holder.getBoundsInScreen(around)
        val room = p.viewport.height()
        if (around.isEmpty || room <= 0 || around.height() > room * HOLDER_SHARE) return
        if (!around.contains(at)) return
        p.askNode = holder
        p.askAt = around
    }

    /**
     * Put every transcription somewhere it does not belong, if a test has asked.
     *
     * Applied to what is about to be drawn and logged, after every filter that decides which
     * words are shown: injecting it earlier only made them be dropped for falling outside
     * their line, which proves nothing about whether a misplaced one would be noticed.
     */
    private fun putThemWrong(boxes: List<WordBox>): List<WordBox> {
        val by = PUT_THEM_WRONG_BY
        if (by == 0f) return boxes
        return boxes.map {
            it.copy(rect = RectF(it.rect.left, it.rect.top + by, it.rect.right, it.rect.bottom + by))
        }
    }

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
        // The band the words themselves stand in, not the whole row they came from.
        //
        // A row is taller than its text and in a real app holds more than one thing - a name,
        // a timestamp, an avatar - each its own colour. The ink is taken as the colours
        // furthest from the surface, so a brighter neighbour inside the same row wins against
        // the text being replaced: on a page of white lines with one amber line among them,
        // the amber line came back white and every word of it was drawn in a colour it never
        // had. Reading the words' own band leaves nothing in the sample but them and what
        // they stand on.
        // A line with no words of its own still has to be given a colour: the whole page is
        // replaced on a press of the mark, lines included that carry no transcription, and
        // reading the band of words that are not there crashed the service under the reader's
        // finger. Its own rectangle is the band in that case.
        val first = p.boxes.firstOrNull()?.rect
            ?: return RectF(p.measuredAt ?: p.clip)
        var left = first.left
        var right = first.right
        var top = first.top
        var bottom = first.bottom
        for (b in p.boxes) {
            left = minOf(left, b.rect.left); right = maxOf(right, b.rect.right)
            top = minOf(top, b.rect.top); bottom = maxOf(bottom, b.rect.bottom)
        }
        // Wider than the words, for enough of the surface to outweigh the letters, and a
        // little taller for the parts of a glyph that sit outside the reported box.
        // Half a line above and below as well: the parts of a glyph that fall outside the
        // reported box, and enough of what the line stands on to be told from the letters at
        // all. Too tight a band and a line whose text cannot be separated from its surface
        // has nothing to fall back on either, and its words are drawn in a palette of ours on
        // a page that is not that colour.
        val grow = (bottom - top)
        return RectF(left - grow, top - grow * 0.5f, right + grow, bottom + grow * 0.5f)
    }

    /**
     * Every line on screen as the page overlay takes them: where it sits now, what it says,
     * and the colours read off the app so it can be put back looking like itself.
     */
    /**
     * What a sweep actually asked about: the text of the page from the first word it took in
     * to the last.
     *
     * The words between them are part of the clause whether or not they were given a
     * transcription of their own, and a phrase card is about a clause. Where the run crosses
     * lines, each line is taken from where the run enters it to where it leaves, which is
     * what a reader dragging across a paragraph has selected.
     */
    private fun phraseOf(run: List<WordBox>): String {
        val fallback = run.joinToString(" ") { it.word }
        if (run.isEmpty()) return fallback
        // Which line each swept word came from, so a run can be put back in reading order:
        // the circle may have crossed the page in either direction.
        val lines = ArrayList<Pair<Planned, MutableList<WordBox>>>()
        for (box in run) {
            val owner = cachedPlan.firstOrNull { p ->
                p.boxes.any { it.at == box.at && it.to == box.to && it.rect == box.rect }
            } ?: continue
            val known = lines.firstOrNull { it.first === owner }
            if (known != null) known.second.add(box) else lines.add(owner to mutableListOf(box))
        }
        if (lines.isEmpty()) return fallback
        lines.sortWith(
            compareBy({ it.first.measuredAt?.top ?: 0 }, { it.first.measuredAt?.left ?: 0 }),
        )
        val said = StringBuilder()
        for ((line, words) in lines) {
            val from = words.minOf { it.at }.coerceAtLeast(0)
            val to = (words.maxOf { it.to } + 1).coerceAtMost(line.text.length)
            if (from >= to) continue
            if (said.isNotEmpty()) said.append(' ')
            said.append(line.text, from, to)
        }
        val text = said.toString().trim()
        if (text.isEmpty()) return fallback
        // Past this a reader is sweeping a page rather than a clause, and an engine handed a
        // page answers it as one sentence.
        return if (text.length > PHRASE_LIMIT) text.substring(0, PHRASE_LIMIT) else text
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
        /** Where the node itself was when the screen was walked, which is what says how far
         *  everything above has travelled since. The clip and the viewport are the ancestors'
         *  boxes, and on a page whose ancestors are its own scrolling elements - which is
         *  every web page - those move with the line rather than standing still. */
        val walkedAt: android.graphics.Rect,

    ) {
        /** Where the node sat when its characters were measured, and what came out. A
         *  scroll moves the line without moving the characters inside it, so the words can
         *  be carried by the difference instead of being measured again. */
        var measuredAt: android.graphics.Rect? = null
        var boxes: List<WordBox> = emptyList()

        /**
         * A cheaper node to ask where this line is, and where that node was when the line was
         * measured.
         *
         * Asking a line of text where it is costs more than asking the box that holds it -
         * two milliseconds against one on a Compose page, which computes a node's answer when
         * it is asked for and has more to compute for text than for a row. Nearly all of a
         * pass is spent waiting for these answers, so it is worth asking the cheaper thing.
         * Only where the holder is a row rather than a whole column: a column spans the
         * document, both its edges are cut off by the screen, and a shift cannot be measured
         * from an edge that is not there.
         */
        var askNode: AccessibilityNodeInfo? = null
        var askAt: android.graphics.Rect? = null
        /** How far this line was carried on the last pass, so the next one can tell whether
         *  it has moved since rather than whether it has moved at all. */
        var lastDx = 0f
        var lastDy = 0f

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
        // An even layout costs nothing but the rectangle already in hand, so it is not held
        // back by the rule that keeps a follow from asking an app to lay its text out again.
        // Held back, a line nobody will place had no words on a follow pass at all, and the
        // follow gave up on the whole screen for want of them.
        val guessing = placed == null && SettingsStore.current.layer == "off" &&
            p.length > 0 && !at.isEmpty
        if (placed == null && !allowedToAsk && !guessing) return false
        val rects = placed
            ?: (
                if (allowedToAsk) {
                    charRects(p.node, p.from, p.length)?.also { fresh ->
                        againstWhereItIsNow(p, at)
                        layouts.remember(p.text, p.from, p.length, at, p.viewport, fresh)
                    }
                } else {
                    null
                }
                )
            // A line nobody will place is laid out evenly across where it is now, the same way
            // the full read does it - and without asking the app anything, so a follow pass
            // stays as cheap as it was. Followed rather than dropped: a line with no words on
            // a follow pass took the whole screen's follow down with it.
            ?: if (guessing) {
                Placement.evenly(
                    p.text.substring(p.from, (p.from + p.length).coerceAtMost(p.text.length)),
                    RectF(at),
                    // The row height the full read settled on for this screen, so that a line
                    // followed onto the screen is laid out like the ones already on it.
                    guessedRowTall,
                ).takeIf { it.isNotEmpty() }
            } else {
                null
            }
            ?: return false
        val made = ArrayList<WordBox>(p.picks.size)
        Placement.boxes(p.picks, rects, p.from, made, readingSource)
        // Where the line is now, with its words brought along.
        //
        // A line placed from what it said last time has its words worked out against the
        // rectangle read at the top of this call, and the rectangle kept as the line's own is
        // read again here so that a later pass carries the words from as late a position as
        // possible. Those are two moments, and on a moving page the page has travelled between
        // them, so the words move by that difference too: without it one drag of six left a
        // line's transcriptions short of their words.
        if (remembered != null) {
            val moved = android.graphics.Rect()
            p.node.getBoundsInScreen(moved)
            if (!moved.isEmpty) {
                val by = (moved.top - at.top).toFloat()
                if (by != 0f && kotlin.math.abs(by) <= CARRY_LIMIT_PX) {
                    for (i in made.indices) {
                        val r = made[i].rect
                        made[i] = made[i].copy(
                            rect = RectF(r.left, r.top + by, r.right, r.bottom + by),
                        )
                    }
                }
                at.set(moved)
            }
        }
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
    /**
     * Show the card for a made-up word, in the window it really lives in.
     *
     * A reader opens one by holding a press on a word, and a check that has to reproduce that
     * gesture is a check of the gesture rather than of the card. This asks for the card itself,
     * so what is looked at is what a reader would see.
     */
    fun showCardFor(word: String, ipa: String, language: String = "") {
        if (!::tooltip.isInitialized) return
        val middle = resources.displayMetrics.widthPixels / 2f
        val box = WordBox(
            rect = android.graphics.RectF(middle - 120f, 400f, middle + 120f, 460f),
            ipa = ipa,
            full = ipa,
            word = word,
            // What the word is in, which is what the card looks it up as. Left out, a card
            // asked for about a German word was built in English, which is the fault this
            // exists to be able to look at.
            language = language,
        )
        main.post { tooltip.show(box) }
    }

    private fun rewriteRow(p: Planned, says: String, into: MutableList<WordBox>) {
        if (says.isBlank() || says.length > MAX_TEXT) return
        if (rewrittenThisPass >= MEASURE_MOVING_MAX || !Dictionary.ready) return
        // In the language the screen was read in and the mode the reader asked for, which is
        // what every other line of it gets. Read as English and always as a transcription, a
        // row recycled into view on a German list carried an English reading of a German
        // word, between rows that had been read in German.
        val settings = SettingsStore.current
        val source = readingSource
        val picks = Reading.annotate(
            listOf(says),
            source = source,
            target = settings.into.ifEmpty { source },
            mode = if (settings.layer == "off") "sound" else settings.layer,
            density = settings.density,
            narrow = settings.narrow,
            hideStress = settings.hideStress,
            accent = settings.accentFor(source),
        ).filter { it.inline }
            .mapNotNull { token ->
                val shown = when (settings.layer) {
                    "sound" -> token.ipa
                    "both" -> listOf(token.gloss, token.glossIpa).filter { it.isNotEmpty() }
                        .joinToString(" ")
                        .ifEmpty { token.ipa }
                    else -> token.gloss.ifEmpty { token.ipa }
                }
                if (shown.isEmpty()) null
                else Pick(token.start, token.end - 1, token.spelling, shown)
            }
        if (picks.isEmpty()) return
        rewrittenThisPass++
        val now = Planned(
            p.node, picks.first().start, picks.last().end - picks.first().start + 1,
            picks, says, android.graphics.Rect(p.clip), android.graphics.Rect(p.viewport), p.exit,
            android.graphics.Rect(p.walkedAt),
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


    /** A node's box and where it sits in draw order, for working out what covers what. */
    /**
     * Whether a box covers a word with room to spare above and below it.
     *
     * A full line's height of room, so that text sharing a visual line with the word - which
     * overlaps it by the nature of the flow - is not mistaken for something drawn over it.
     */
    private fun encloses(over: android.graphics.Rect, word: android.graphics.Rect): Boolean {
        val room = word.height()
        return over.left <= word.left && over.right >= word.right &&
            word.top - over.top >= room && over.bottom - word.bottom >= room
    }

    private class Painted(
        val enter: Int,
        val rect: android.graphics.Rect,
        /** Whether the node carries text of its own. Text does not hide text: two pieces of
         *  text in one flow share the visual line where one ends and the next begins, so
         *  their boxes overlap without either covering anything. */
        val holdsText: Boolean,
    )


    /** Every window the service can see, as "type at rectangle": what the keyboard is, and
     *  whether it is there at all, is otherwise invisible from outside. */
    private fun windowsSeen(): List<String> = runCatching {
        windows.map { window ->
            val at = android.graphics.Rect()
            window.getBoundsInScreen(at)
            "${window.type}:$at"
        }
    }.getOrDefault(emptyList())

    /**
     * What the windows now are: whether the app in front changed, and where the keyboard is.
     *
     * Runs away from the main thread because everything in it can block. What it decides is
     * posted back, because only the main thread may draw.
     */
    private fun lookAtTheWindows() {
        // The words of the app before come down as soon as another app's window is in front,
        // which is sooner than that app's own events arrive - they wait for its window to
        // finish animating in. Only taken down: the plan is left for the read that the app's
        // own events will ask for, because scheduling a read on every window change starves
        // the one that matters.
        val front = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        // Our own screen counts as another app here, though it is never read.
        //
        // Everywhere else this service asks whether an app is one to transcribe, and ours is
        // not - but the question here is only whose words are on the screen, and the answer
        // over our own screen is "the last app's". Left out of this, the settings screen came
        // up under a full page of the accessibility settings' transcriptions and kept them:
        // nothing else takes them down, because nothing reads our screen to replace them.
        val mine = front == packageName
        if (front != null && cachedPackage != null && front != cachedPackage &&
            !bystanders.contains(front) && (mine || SettingsStore.allows(front)) &&
            ::overlay.isInitialized
        ) {
            main.post { overlay.hideNow() }
        }
        // What the rest of this is for is the keyboard, so nothing is done unless it has
        // actually come or gone. Windows change constantly - every dialog, every system
        // window, every one of ours - and the button is only in the way of one of them.
        val top = keyboardTop()
        if (top != lastKeyboardTop) {
            lastKeyboardTop = top
            val now = SettingsStore.current
            if (now.enabled && now.lens) main.post { hover.show() }
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "KEYBOARD at $top")
            }
        }
    }

    /**
     * The top of the keyboard, or zero where none is open.
     *
     * A keyboard is a window of its own, so where it is has to be asked of the system rather
     * than worked out. What it is for is keeping the button above it: a button under an open
     * keyboard takes none of the touches meant for it, and cannot be moved out of the way
     * either, so a reader typing - exactly the reader who wants to ask about what they are
     * reading - is left without it.
     */
    private fun keyboardTop(): Int {
        val ime = runCatching {
            windows.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
        }.getOrNull() ?: return 0
        val at = android.graphics.Rect()
        ime.getBoundsInScreen(at)
        val tall = resources.displayMetrics.heightPixels
        // Only a keyboard along the foot of the screen counts. One that floats, or one that
        // reports nothing, is not something to stand clear of.
        return if (!at.isEmpty && at.bottom >= tall - 8 && at.top > tall / 3) at.top else 0
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
        /** Subtrees the walk refused to enter: one whose node said it could not be seen, and
         *  one whose node's box did not meet what its ancestors allow. Each takes everything
         *  below it with it. */
        var unseen: Int = 0,
        var clipped: Int = 0,
        /** Nodes holding text that was left out for being longer than [MAX_TEXT], and the
         *  longest piece of text the walk saw. An app that puts a whole message in one node -
         *  a chat, a reader - loses the message itself to that cap while its labels and its
         *  buttons are read, which from the outside is a mark with nothing to answer about. */
        var tooLong: Int = 0,
        var longest: Int = 0,
        /** Nodes carrying text that were planned, and nodes that ran out of budget. */
        var withText: Int = 0,
        var overBudget: Int = 0,
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

    /**
     * Which words of a planned screen are drawn, and where they sit in their line.
     *
     * One call for the whole screen: how often a word has already appeared is what decides
     * whether this occurrence is drawn, and a call per line would count each line from zero.
     * Lines the core found nothing in are dropped, so nothing later pays to measure them.
     */
    private fun chooseWords(
        planned: MutableList<Planned>,
        settings: Settings,
        screenLanguage: String?,
        budget: Budget,
    ) {
        if (planned.isEmpty()) return
        // What the screen is in, what the reader reads into, and what they asked to see over
        // a word. All three were hardcoded to English and a transcription once, which is how
        // an app whose whole point is translation showed nothing but pronunciations.
        val source = screenLanguage ?: Language.OURS
        // Kept for the words themselves, so that what the card asks about a word is asked in
        // the language the word was read in. A follow pass places words without reading the
        // screen again, and takes the source from here.
        readingSource = source
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "READING source=$source target=${settings.into} layer=${settings.layer} " +
                    "lines=${planned.size} first=${planned.firstOrNull()?.text?.take(40)}",
            )
        }
        // Nothing is replaced, and the mark still answers a word.
        //
        // The words are read and their answers kept - that is what the mark is dragged over -
        // and nothing is painted over the page. A reader who wants the product there to be
        // asked rather than answering over everything they read is the case this is for, and
        // without the reading there would be nothing under the mark to answer with.
        val silent = settings.layer == "off"
        val told = Reading.annotate(
            planned.map { it.text },
            source = source,
            target = settings.into.ifEmpty { source },
            mode = if (silent) "sound" else settings.layer,
            density = settings.density,
            narrow = settings.narrow,
            hideStress = settings.hideStress,
            accent = settings.accentFor(source),
        )
        val byRun = HashMap<Int, ArrayList<Pick>>(planned.size)
        for ((at, token) in told.withIndex()) {
            // What is drawn is what the reader asked for, in the same three modes the browser
            // offers. The chip is painted over the word and has the width of the word it
            // covers, so "both" is one line: what it means, then how to say that. Where the
            // language read into has no dictionary here there is no sound to give for the
            // meaning, and the chip carries the meaning alone.
            // Which words are kept.
            //
            // Ordinarily the ones the core says are worth replacing: the bar decides how many
            // and the mode decides which, and a word that is not replaced is not there to be
            // asked about either. With nothing being replaced that rule has nothing left to
            // stand on - the mark is the only way to ask, and the reader means to ask about
            // whatever they point at, not about the handful a bar would have drawn. So every
            // word the core read is kept, and none of them is painted.
            if (!silent && !token.inline) continue
            val shown = when (settings.layer) {
                "sound" -> token.ipa
                "both" -> listOf(token.gloss, token.glossIpa).filter { it.isNotEmpty() }
                    .joinToString(" ")
                    .ifEmpty { token.ipa }
                else -> token.gloss.ifEmpty { token.ipa }
            }
            if (shown.isEmpty()) continue
            byRun.getOrPut(token.run) { ArrayList(4) }
                .add(
                    Pick(
                        token.start,
                        token.end - 1,
                        token.spelling,
                        shown,
                        // What the core already used to decide this word, carried to the card
                        // so a tap asks the same question the line answered.
                        before = told.getOrNull(at - 1)
                            ?.takeIf { it.run == token.run }?.spelling.orEmpty(),
                    ),
                )
        }
        val kept = ArrayList<Planned>(byRun.size)
        for ((at, line) in planned.withIndex()) {
            val picks = byRun[at] ?: continue
            if (picks.isEmpty()) continue
            val from = picks.first().start
            val to = picks.last().end
            kept.add(
                Planned(
                    line.node, from, to - from + 1, picks, line.text, line.clip, line.viewport,
                    line.exit, line.walkedAt,
                ),
            )
            budget.words -= picks.size
        }
        planned.clear()
        planned.addAll(kept)
    }

    /** The app itself, from the mark on the panel. */
    private fun openApp() {
        val intent = android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
            .onFailure { android.util.Log.w("Phonetix", "the app would not open", it) }
    }

    /** The panel the mark opens, over whatever is being read. Nothing while it is down. */
    private var asking: AskPanel? = null
    private var listener: Dictation? = null

    /**
     * The other direction, asked over what the reader is looking at.
     *
     * A panel rather than a screen of the app's own: a word that exists only in the reader's
     * head is asked for in the middle of whatever they are reading, and opening an activity
     * puts that away. It is taken down by the way back, by a touch outside it, or by asking.
     */
    private fun openSay() {
        if (asking != null) return
        val settings = SettingsStore.current
        val into = settings.into
        // Which language the answer comes back in: what the reader said in this panel, and
        // until they say, the screen in front of them or a language they keep a dictionary
        // for. Every language is on offer, not only the ones a dictionary is held for - a
        // reader asking for the word for something usually has no dictionary for it, which is
        // why they are asking.
        val held = Packs.held(this)
        val learning = settings.learning.ifEmpty {
            lastScreenLanguage?.takeIf { it != into } ?: held.firstOrNull { it != into }.orEmpty()
        }
        // In the order worth offering: the one being asked in, then the ones asked in lately,
        // then the ones a dictionary is held for, and the rest of them by name behind those.
        val offered = (listOfNotNull(learning.takeIf { it.isNotEmpty() }) + settings.recent +
            held + Languages.all().sortedBy { Languages.english(it) })
            .distinct().filter { it.isNotBlank() && it != into }
        // The side of the palette the reader reads in: their own answer where they gave one,
        // and the device's where they left it to the device.
        val dark = when (settings.dark) {
            "light" -> false
            "dark" -> true
            else -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val panel = AskPanel(this, Tokens.palette(themeNamed(settings.theme), dark))
        // What the reader picks here is what they are learning, and it is remembered: this is
        // the one place they are thinking about it.
        var answering = learning
        fun offer(chosen: String) {
            panel.setLanguages(offered, chosen) { picked ->
                answering = picked
                SettingsStore.setLearning(picked)
                offer(picked)
                said(panel, picked, into, panel.field.text.toString())
            }
        }
        offer(learning)
        panel.onSubmit = { asked -> said(panel, answering, into, asked) }
        panel.onOpenApp = {
            closeSay()
            openApp()
        }
        panel.onClose = { closeSay() }
        // Saying it rather than typing it, where the phone can hear: the words go into the
        // field as they arrive and the finished phrase is asked for without being pressed.
        val hearing = Dictation(this)
        if (hearing.canListen()) {
            hearing.onPartial = { heard -> main.post { panel.heard(heard) } }
            hearing.onFinal = { phrase ->
                main.post {
                    panel.heard(phrase)
                    panel.listening(false)
                    said(panel, learning, into, phrase)
                }
            }
            hearing.onState = { on -> main.post { panel.listening(on) } }
            panel.onDictate = { hearing.start(into.ifEmpty { Language.OURS }) }
            listener = hearing
        } else {
            panel.onDictate = null
        }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable, because it is typed into; the touches it does not take go to the app
            // underneath, and one outside it closes the panel.
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            y = (72 * resources.displayMetrics.density).toInt()
            softInputMode =
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        val wm = getSystemService(android.view.WindowManager::class.java)
        runCatching { wm.addView(panel, lp) }
            .onFailure {
                android.util.Log.w("Phonetix", "the panel would not open", it)
                return
            }
        asking = panel
        panel.field.requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // From Android 13 the way back does not arrive as a key, so it is registered for
            // as well: a window that answers only one of the two cannot be closed on the other.
            val leave = android.window.OnBackInvokedCallback { closeSay() }
            panel.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                leave,
            )
            back = leave
        }
    }

    private var back: android.window.OnBackInvokedCallback? = null

    /**
     * What the panel answers with, off the thread it is typed on.
     *
     * Which language it was typed in is worked out rather than assumed: a reader asks for the
     * word for something in whatever language it came to them in, which is not always the one
     * this app was set up with. The machine on the phone turns it into the language they are
     * learning and fetches what that pair needs itself; the dictionary is then asked about the
     * word that came back, so the answer carries its entry and how it is said. Where there is
     * no entry - most of a vocabulary - the translation is still the answer, said by the
     * synthesiser, because a reader who asked for a word wants the word.
     */
    private fun said(panel: AskPanel, learning: String, into: String, asked: String) {
        val text = asked.trim()
        if (text.isEmpty()) return
        val wanted = learning.ifEmpty { lastScreenLanguage.orEmpty() }
        if (wanted.isEmpty()) {
            panel.saying(Wording.says["say-no-language"].orEmpty())
            return
        }
        panel.saying("…")
        val turn = ++asks
        // What this question is doing, for the state dump: a panel showing "…" says only that
        // something has not come back, and which step it is waiting in is the whole answer.
        askedFor = text
        askedInto = wanted
        askedAt = System.currentTimeMillis()
        askStage = "identifying the language"
        io.post {
            val held = Packs.held(this).toSet()
            val from = kotlinx.coroutines.runBlocking {
                Machine.language(text, held + into) ?: into.ifEmpty { Language.OURS }
            }
            askStage = if (from == wanted) "already in $wanted" else "translating $from to $wanted"
            val word = if (from == wanted) {
                text
            } else {
                kotlinx.coroutines.runBlocking { Machine.said(text, from, wanted) }
            }
            if (word.isNullOrBlank()) {
                askStage = "nothing came back"
                main.post {
                    if (asking === panel && turn == asks) {
                        panel.saying(Wording.says["say-nothing"].orEmpty())
                    }
                }
                return@post
            }
            askStage = "looking $word up in $wanted"
            // The entry for the word that came back, in the language it is in, read into the
            // one the reader asked in: what it means back is how a machine's answer is judged.
            Dictionary.ensure(this, wanted)
            Packs.openHeld(this)
            val entry = Reading.lookUp(word, wanted, from)?.takeIf { it.found }
            // No entry is the ordinary case - a dictionary holds a few thousand words - and
            // the word is still the answer. How it is said comes from the synthesiser, which
            // is what the overlay falls back to for every word no pack holds.
            val answer = entry ?: Answer.ofTranscription(
                word,
                Speech.phonemes(Accents.voiceOf(wanted, ""), listOf(word))[word].orEmpty(),
                wanted,
            )
            askStage = "answered with $word"
            main.post {
                if (asking !== panel || turn != asks) return@post
                panel.show(answer) { speaker.say(word, Accents.voiceOf(wanted, "")) }
            }
        }
    }

    /** When a drag that found nothing last wrote the state out. */
    private var lastNothingDump = 0L

    /** The last question put to the panel, and how far it got: see [dumpState]. */
    @Volatile private var askedFor: String? = null
    @Volatile private var askedInto: String? = null
    @Volatile private var askedAt = 0L
    @Volatile private var askStage: String? = null

    /** Which question is the current one: everything typed before it is stale. */
    private var asks = 0

    /** Take the panel down, and the keyboard with it. */
    private fun closeSay() {
        listener?.stop()
        listener = null
        val panel = asking ?: return
        asking = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            back?.let { panel.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
        }
        back = null
        // Removing the window does not always take the keyboard with it, and a keyboard left
        // up over a conversation with nothing to type into is the panel half gone.
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(panel.windowToken, 0)
        panel.done()
        runCatching { getSystemService(android.view.WindowManager::class.java).removeView(panel) }
    }

    /**
     * Open the translation engine for the direction the reader is reading in.
     *
     * From the model files this phone already has. Nothing is fetched: what the product can
     * answer is what it carries and what the reader has put there themselves.
     */
    private fun openTranslator() {
        val settings = SettingsStore.current
        val target = settings.into
        val source = lastScreenLanguage ?: Language.OURS
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "OPENING $source->$target")
        }
        if (target.isEmpty() || source == target) {
            missingDirection = null
            return
        }
        if (Translator.start(Packs.models(this), source, target)) {
            missingDirection = null
            readAgain()
        } else {
            // Asked to turn words into a language this phone cannot turn them into.
            //
            // The reader chose a language and a mode that translates, and every word then
            // comes back with no meaning to show - so the chip falls back to how the word
            // sounds and the screen looks exactly like pronunciation mode. Nothing anywhere
            // said why. Remembered here and reported on the settings screen, named, because
            // the reader is the only one who can do anything about it.
            missingDirection = "$source-$target"
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "NOMODEL $source->$target")
            }
        }
    }

    /**
     * Read the screen again from scratch.
     *
     * An engine that has just started can answer words the last pass could not, and those
     * words are already chosen: a scheduled pass takes the fast path, reuses what it decided
     * and draws the same blanks again. Both engines load in seconds, after the first screen
     * has been read, so without this the words they can answer stay bare until the reader
     * scrolls.
     */
    private fun readAgain() {
        // On the main thread, because that is the one the loop runs on: both engines start on
        // a background thread and a scheduling call made from there returned without doing
        // anything, so the words the engine could now answer stayed bare until the reader
        // scrolled and something else asked for a read.
        main.post {
            scrollOnly = false
            schedule(0L)
        }
    }

    private fun plan(
        node: AccessibilityNodeInfo?,
        out: MutableList<Planned>,
        budget: Budget,
        stats: Stats,
        inherited: android.graphics.Rect,
        painted: MutableList<Painted>,
    ) {
        if (node == null) return
        if (budget.nodes <= 0 || budget.words <= 0 || budget.visits <= 0) {
            stats.overBudget++
            return
        }
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
        // Counted rather than merely obeyed: this one return decides how much of a screen is
        // ever seen, and on a web page it refuses most of it. Descending anyway was measured
        // and is not worth it - the text under an invisible container is reported invisible
        // too, so it costs 381 calls against 101 and finds not one more word.
        if (!visible) { stats.unseen++; return }
        val clip = android.graphics.Rect(inherited)
        if (!bounds.isEmpty && !clip.intersect(bounds)) { stats.clipped++; return }

        // Anything with its own area can end up covering what was drawn before it. A layer
        // spanning most of the screen is a backdrop rather than something that hides a
        // word, so it is not counted. Only what can actually be seen covers anything.
        if (!bounds.isEmpty && !isBackdrop(bounds)) {
            painted.add(Painted(enter, android.graphics.Rect(bounds), !text.isNullOrBlank()))
        }

        if (!text.isNullOrBlank()) {
            if (text.length > stats.longest) stats.longest = text.length
            if (text.length > MAX_TEXT) stats.tooLong++
        }
        if (!text.isNullOrBlank() && text.length <= MAX_TEXT) {
            stats.withText++
            budget.nodes--
            // Every line counts towards what language the screen is in, including the ones
            // that hold nothing worth transcribing: a page's German is mostly in its labels
            // and its buttons.
            stats.tongue.add(text)
            // Which of its words are worth drawing is decided later, for the whole screen at
            // once: a word's turn depends on how often it has already appeared, and deciding
            // node by node would count from zero on every line.
            plannedHere = out.size
            out.add(
                Planned(
                    node, 0, 0, emptyList(), text,
                    android.graphics.Rect(clip), android.graphics.Rect(inherited), 0,
                    android.graphics.Rect(bounds),
                ),
            )
        }

        mark = System.nanoTime()
        val n = node.childCount
        stats.ipcNs += System.nanoTime() - mark
        if (BuildConfig.DEBUG && PROBE_TREE) {
            android.util.Log.d(
                "Phonetix",
                "NODE ${node.className} kids=$n vis=$visible bounds=$bounds " +
                    "text=${node.text?.toString()?.take(24)} extras=${node.availableExtraData}",
            )
        }
        for (i in 0 until n) {
            mark = System.nanoTime()
            val child = node.getChild(i)
            stats.ipcNs += System.nanoTime() - mark
            stats.calls++
            plan(child, out, budget, stats, clip, painted)
            if (budget.nodes <= 0 || budget.words <= 0 || budget.visits <= 0) break
        }
        // The subtree is finished, so record where it ended: whatever is visited from here
        // on is painted over this node.
        if (plannedHere >= 0 && plannedHere < out.size) {
            val was = out[plannedHere]
            out[plannedHere] = Planned(
                was.node, was.from, was.length, was.picks, was.text, was.clip, was.viewport,
                budget.order, was.walkedAt,
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
    private fun covered(word: RectF, exit: Int, within: android.graphics.Rect? = null): Boolean {
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
            // Text that flows around this line rather than over it.
            //
            // A paragraph on a web page is a run of sibling nodes - some text, a link, more
            // text - and each reports a box spanning every visual line it touches. Where one
            // ends and the next begins is a single visual line belonging to both, so their
            // boxes overlap and the later one, by draw order alone, covers the earlier one's
            // last words. Measured on an article, one to four words of every line were thrown
            // away for being behind the paragraph that follows them.
            //
            // What this has to catch is a bar the page scrolls under, and a bar is a
            // container: its text, if it has any, is in a child. So a box that carries text
            // itself is in the flow rather than over it.
            //
            // Unless it encloses the word outright. An app's own menu or bottom sheet carries
            // text and is drawn over the page inside the same window, so nothing else here
            // sees it: the words underneath are still in the tree, still reporting where they
            // are, and their transcriptions were painted on top of the sheet - which is what
            // a reader photographed. What tells the two apart is room: a neighbouring run of
            // text shares one visual line with this word, so its box begins and ends about
            // where the word does, while a sheet covering the page reaches well past it on
            // both sides. A line's own height is the measure of "well past".
            if (p.holdsText && !encloses(p.rect, w)) continue
            if (within != null && within.contains(p.rect)) continue
            if (android.graphics.Rect.intersects(p.rect, w)) {
                if (BuildConfig.DEBUG && PROBE_TREE) {
                    android.util.Log.d(
                        "Phonetix",
                        "OVER word=$w is under ${p.rect} entered=${p.enter} exit=$exit " +
                            "within=$within",
                    )
                }
                return true
            }
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
            // Only what is actually above the app being read. The notification shade stays in
            // the window list after it is closed, reporting the whole screen, and counting it
            // meant every word on the page was judged to be behind something and none was
            // drawn: the page came back bare from the shade and stayed that way.
            val above = windows
                .filter { it.isActive || it.isFocused }
                .maxOfOrNull { it.layer } ?: Int.MIN_VALUE
            for (w in windows) {
                val type = w.type
                val isOverlay = type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM ||
                    type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                if (!isOverlay || w.layer <= above) continue
                val r = android.graphics.Rect()
                w.getBoundsInScreen(r)
                // Ours is a window too, and it is a system overlay by type: the transcriptions
                // themselves, the card, and the lens. None of them blocks anything, because
                // they are what is being drawn.
                if (ours(w)) continue
                if (!r.isEmpty && r.height() > MIN_BLOCKER) found.add(r)
            }
        }
        blockers = found
    }

    /**
     * Whether what is in front takes the whole screen.
     *
     * The shade and the recents screen do, and over those there is nothing of the app to
     * read. A keyboard, the status bar and a notification that drops in do not, and the app
     * behind them is still what the reader is reading.
     */
    private fun coversTheScreen(): Boolean = runCatching {
        val screen = resources.displayMetrics
        val active = windows.firstOrNull { it.isActive } ?: return@runCatching true
        val r = android.graphics.Rect()
        active.getBoundsInScreen(r)
        r.height() >= screen.heightPixels * 0.7f && r.width() >= screen.widthPixels * 0.9f
    }.getOrDefault(true)

    /**
     * The app a system window is standing over, so it can be read while that window is up.
     *
     * The window list rather than the active window: while a keyboard has focus the active
     * window is the keyboard's, and the app the reader is reading is the topmost application
     * window under it.
     */
    private fun behindTheSystem(): android.view.accessibility.AccessibilityNodeInfo? =
        runCatching {
            windows
                .filter {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                }
                .sortedByDescending { it.layer }
                .firstNotNullOfOrNull { w ->
                    w.root?.takeIf { node ->
                        val pkg = node.packageName?.toString()
                        SettingsStore.allows(pkg) && !bystanders.contains(pkg)
                    }
                }
        }.getOrNull()

    /** Whether a window is one of ours, which nothing of ours is hidden by. */
    private fun ours(w: android.view.accessibility.AccessibilityWindowInfo): Boolean =
        runCatching { w.root?.packageName?.toString() == packageName }.getOrDefault(false)

    /**
     * The per-character screen rectangles for a node's text. Returns null when the app does
     * not report them - a Compose or Canvas-drawn surface that exposes text but no layout,
     * for instance - and those nodes are left alone rather than guessed at.
     */
    /** How tall one item of the list in front is, from the spacing of the lines just read.
     *  What an estimated offset's item boundaries are worth in pixels. */
    @Volatile private var rowHeight = 0f

    /** The last estimated offset a lazy list reported, and the pixels it was taken to mean. */
    @Volatile private var lastPseudo = -1
    @Volatile private var lastPseudoAt = 0L
    /** Which app the remembered offset belongs to. Two apps' offsets have nothing to do with
     *  each other, and a list's is meaningless once a different one is in front. */
    @Volatile private var lastPseudoPkg: String? = null

    /**
     * How far a list that reports no distance has actually moved, from the offset it estimates.
     *
     * A lazy list cannot say where it is scrolled to - its items are not all measured - so it
     * offers its first visible item's index times five hundred, plus how far into that item it
     * has gone, which is in pixels. Five hundred is a stand-in for an item's height, so the
     * estimate advances in real pixels within an item and jumps at every boundary. Undone with
     * the height the items actually are, which the lines on the screen give: an item boundary
     * is worth that height rather than five hundred.
     *
     * The offset is remembered across movements rather than only within one, because it is an
     * absolute position and not a step. A list's first scroll event of a fling arrives well
     * after the fling has started - measured, 471 pixels and 120 milliseconds in - and it is
     * the only news of that stretch there will ever be, since the bounds do not move either.
     * Held against the last offset seen, that first event accounts for the whole of it; held
     * against nothing, half a fling went unreported.
     *
     * Returns nothing when this app has not reported an offset before, when the list has
     * jumped further than a couple of screens (the plan is stale anyway and the next reading
     * settles it), or when no item height has been measured yet.
     */
    private fun fromPseudoScroll(pseudo: Int, pkg: String?): Float {
        val was = if (pkg == lastPseudoPkg) lastPseudo else -1
        lastPseudo = pseudo
        lastPseudoAt = android.os.SystemClock.uptimeMillis()
        lastPseudoPkg = pkg
        if (pseudo < 0 || was < 0 || pseudo == was) return 0f
        val height = rowHeight
        if (height <= 0f) return 0f
        fun real(p: Int): Float {
            val item = p / LAZY_ITEM_UNITS
            val into = p % LAZY_ITEM_UNITS
            return item * height + into.coerceAtMost(height.toInt()).toFloat()
        }
        val moved = real(pseudo) - real(was)
        return if (kotlin.math.abs(moved) >= SAID_TOO_FAR) 0f else moved
    }

    private fun charRects(node: AccessibilityNodeInfo, from: Int, length: Int): Array<RectF?>? {
        // Asked for under whichever name the node itself offers.
        //
        // There are two spellings of this in the wild. The platform's own constant is
        // "android.view.accessibility.extra...", and Chromium answers to the AndroidX one,
        // "android.core.view.accessibility.extra...", which is what every page in every
        // browser is drawn through. Asking under the platform name alone, the request simply
        // returns false there: no character positions, so no word can be located, so nothing
        // is drawn on any web page at all - standing still or scrolling.
        val offered = node.availableExtraData.orEmpty()
        val key = when {
            PLATFORM_CHARS in offered -> PLATFORM_CHARS
            ANDROIDX_CHARS in offered -> ANDROIDX_CHARS
            // An app that advertises neither may still answer; the platform name is the one
            // to try, and a refusal costs one call.
            else -> PLATFORM_CHARS
        }
        val prefix = key.removeSuffix("_KEY")
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, from)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
            // Under the matching spelling as well, because an app that names the key one way
            // reads its arguments the same way, and a request whose arguments it cannot find
            // is answered for character zero of length zero.
            putInt("${prefix}_ARG_START_INDEX", from)
            putInt("${prefix}_ARG_LENGTH", length)
        }
        val ok = runCatching { node.refreshWithExtraData(key, args) }.getOrDefault(false)
        val raw = if (!ok) null else node.extras?.getParcelableArray(key)
        if (BuildConfig.DEBUG && PROBE_TREE) {
            val here = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val first = (raw?.firstOrNull { it is RectF && !(it as RectF).isEmpty }) as? RectF
            android.util.Log.d(
                "Phonetix",
                "CHARS key=${key.takeLast(28)} ok=$ok got=${raw?.size} " +
                    "real=${raw?.count { it is RectF && !(it as RectF).isEmpty }} " +
                    "first=$first node=$here from=$from len=$length",
            )
        }
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

        /** How often the screen is read again while the mark is being dragged over it. */
        const val WHILE_DRAGGING_MS = 150L
        // A scroll is followed rather than waited out, so this only spaces the passes to
        // about one a frame.
        const val GAP_SCROLL_MS = 16L
        /** No scroll event for this long and the screen is considered to have stopped. */
        const val STILL_MS = 120L
        /** How long the following keeps checking after the last sign of movement. Long
         *  enough to cover the pause between two strokes of a scroll, because a reader who
         *  moves the page in short pushes stops for a moment between them, and a loop that
         *  gave up in that moment had to be woken by an event that arrives late. */
        /**
         * How long the loop keeps looking after the last thing that announced itself.
         *
         * It used to wait most of a second, because it was carrying the words along and a
         * page that goes quiet mid-scroll would have stranded them. Nothing is carried now -
         * the words are down while the page moves - so all this loop does on a still page is
         * ask it where its lines are, sixty times a second, for nothing. It stops as soon as
         * the page has been still for longer than the gap between a scroll's own events.
         */
        const val FOLLOW_IDLE_MS = 200L
        /** However well the following is going, a settled screen is read in full this often. */
        const val FULL_READ_MS = 900L
        /**
         * How long the words are left off the screen after the app in front changes, before
         * the new one is read. Long enough for the frame that takes them down to be drawn.
         */
        const val AFTER_A_SWITCH = 350L
        /**
         * The longest a screen that nothing is happening on goes unread.
         *
         * Short, because this is also how long a page can take to finish appearing. A screen
         * read while it is still laying out is read short - thirty-two words of a hundred and
         * forty-four - and the rest arrive on the next read. Backing off to seven seconds
         * meant a page that filled in over seven seconds, which is what a reader calls
         * absurdly slow, and the blocking call this was avoiding is no longer made on the
         * thread that draws.
         */
        const val FULL_READ_IDLE_MS = 1800L
        /** And a moving one this often, to pick up the words scrolling into it. */
        const val FULL_READ_MOVING_MS = 2500L
        /** How much of a plan's words have to survive the following for it to still describe
         *  the screen. Below this, most of what a reader is looking at has never been read. */
        /**
         * How many lines a single read may measure from scratch while the page is moving.
         *
         * None. Asking a line where its characters are makes the app lay that text out again,
         * and on a page of any length that answer takes about a quarter of a second: three of
         * them put a single following pass at a full second, during which the page travelled
         * seven hundred pixels and every word on the screen was carried on a guess. The
         * passes are what keeps the words on their text, and there were one to three of them
         * in a drag instead of six to twenty.
         *
         * Measured across the three shapes of page, transcriptions more than a line from
         * their own word: through a drag 81 to 26 percent on wrapped paragraphs, 78 to 42 on
         * a conversation, 49 to 16 on a list; once settled 55, 18 and 29 percent to none at
         * all. It costs coverage - a line that has never been measured is not drawn until the
         * page stops, and fewer are carrying a transcription through the movement - and that
         * is the trade: nothing on a word is better than somebody else's pronunciation on it.
         *
         * An earlier measurement put this at twelve. It was taken on fixtures barely taller
         * than the screen, where a drag ran off the end of the page a third of the way
         * through and the rest of it was judged as movement, and against a check that read
         * the positions the service worked out rather than the ones the layer drew.
         */
        @Volatile
        @JvmStatic
        var MEASURE_MOVING_MAX = 0

        /** How many painted rectangles are kept for working out what covers what. A screen
         *  holds a hundred or so; beyond that they are old ones from before a scroll. */
        const val MAX_PAINTED = 256

        /** How much text a phrase card will answer, the same as the browser's. */
        const val PHRASE_LIMIT = 240

        /** The service, while it is running, so a test can ask it to measure itself. */
        @Volatile
        @JvmStatic
        var running: PhonetixAccessibilityService? = null

        /** Which direction cannot be translated here, for the settings screen to report. */
        @JvmStatic
        fun cannotTranslate(): String? = running?.missingDirection

        /**
         * Whether the words are moved by what a view says it has just scrolled.
         *
         * Off. Only a scroll view and a framework list report the distance in pixels; a
         * Compose list reports a pseudo-offset of its own and a delta of one, so the speed
         * taken from it is a fiction, and the layer carrying the words at a fiction is worse
         * than carrying them at the speed the readings measure. Transcriptions more than a
         * line from their own word through a drag, ten drags each: 51 to 36 percent on a
         * conversation, 30 to 23 on wrapped paragraphs, 11 to 9 on a list - better on all
         * three, and the conversation's worst drag went from every transcription wrong to
         * under half.
         *
         * It was measured as helping when it was added. That was against a check reading the
         * positions the service worked out rather than the ones the layer drew, on pages a
         * drag ran off the end of.
         */
        @Volatile
        @JvmStatic
        var USE_SAID_SCROLL = true

        /**
         * Below this a reported scroll is a placeholder rather than a movement.
         *
         * A scroll view and a framework list report how far they moved in real pixels, and it
         * arrives without being asked for, which is the best signal there is: through a fling
         * the settings app reports 133, then 72, then 33, then 7, which is the deceleration
         * itself. A Compose list reports one, every event, whatever it did. Believing that
         * puts the speed at a fifth of a pixel a millisecond while the page is doing six, so
         * the words stand still and the text flies past them.
         *
         * Judged on two apps nobody wrote for the test, against uiautomator's own reading of
         * the screen: 3 of 171 transcriptions off their word with these deltas used, against
         * 26 of 189 with them ignored.
         */
        /** What a lazy list counts one item as, when it has no real height to give. Chosen by
         *  the toolkit, not by us: it is the five hundred in its own estimate. */
        /** What a scroll event carries when the view did not fill the distance in. */
        const val UNDEFINED_SCROLL = -1
        const val LAZY_ITEM_UNITS = 500
        const val SAID_TOO_SMALL = 2

        /** Past this, what a view says it scrolled by is not a scroll of a page: it is a
         *  jump, a relayout, or a number in units of its own. */
        const val SAID_TOO_FAR = 4000

        /** How much of the window a line scrolls in its holder may fill and still count as a
         *  row worth asking instead of the line. */
        const val HOLDER_SHARE = 0.6f

        /** How far every transcription is deliberately put from its word, so that a test can
         *  show it notices. Zero in anything but a test. */
        @Volatile
        @JvmStatic
        var PUT_THEM_WRONG_BY = 0f

        /** Two readings of the same text this close together are the same line. */
        const val LINE_SAME_PX = 24

        const val KEPT_ENOUGH = 0.7f
        /** However fast a screen turns over, it is not read again more often than this: a
         *  read costs tens of milliseconds during which nothing is followed at all. */
        const val FULL_READ_TURNOVER_MS = 350L


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

        /** How long after the last movement a page counts as still. Long enough not to read
         *  between the events of one fling, short enough that a reader does not wait. */
        const val AFTER_MOVING = 120L
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
        /** Whether the words carry the number of the line they came from, and the overlay
         *  paints it. For the check that is made of pixels alone. */
        @Volatile
        @JvmStatic
        var MARK_LINES = false

        /** Whether every node the walk visits is named in the log, which is how a tree that
         *  is not being handed to us at all is told from one being read and rejected. */
        @Volatile
        @JvmStatic
        var PROBE_TREE = false

        /** The two names this one request goes by. The platform defines the first; Chromium,
         *  and so every page in every browser, answers only to the second. */
        val PLATFORM_CHARS: String =
            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        const val ANDROIDX_CHARS =
            "android.core.view.accessibility.extra.DATA_TEXT_CHARACTER_LOCATION_KEY"

        const val MAX_NODES = 120
        const val MAX_VISITS = 400
        const val MAX_WORDS = 60

        /** And with nothing drawn, where every word is one the mark may be asked about. */
        const val MAX_WORDS_SILENT = 240

        /** The shortest gap between two dumps written by drags that found nothing. */
        const val NOTHING_DUMP_MS = 30_000L
        const val MAX_TEXT = 2000
    }
}
