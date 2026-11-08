package io.github.nanbiz.phonetix.service

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.nanbiz.phonetix.BuildConfig
import io.github.nanbiz.phonetix.core.Answer
import io.github.nanbiz.phonetix.ui.wiktionary
import io.github.nanbiz.phonetix.core.Language
import io.github.nanbiz.phonetix.core.Packs
import io.github.nanbiz.phonetix.core.Eld
import io.github.nanbiz.phonetix.core.Languages
import io.github.nanbiz.phonetix.core.Reading
import io.github.nanbiz.phonetix.core.Fetch
import io.github.nanbiz.phonetix.core.Speech
import io.github.nanbiz.phonetix.core.SettingsStore
import io.github.nanbiz.phonetix.core.Settings
import io.github.nanbiz.phonetix.core.Accents
import io.github.nanbiz.phonetix.core.Wiktionary
import io.github.nanbiz.phonetix.core.IpaSymbols
import io.github.nanbiz.phonetix.ui.GlanceCard
import io.github.nanbiz.phonetix.ui.glanceHasSomething
import io.github.nanbiz.phonetix.ui.Tokens
import io.github.nanbiz.phonetix.ui.themeNamed
import io.github.nanbiz.phonetix.core.SymbolInfo
import io.github.nanbiz.phonetix.core.WordBox
import io.github.nanbiz.phonetix.core.wikimediaFileUrl
import io.github.nanbiz.phonetix.core.wikimediaThumbUrl
import java.net.URL
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * The card a tap opens: everything the extension's tooltip offers about a word.
 *
 * The word and its whole transcription, the word spoken aloud, and every symbol in it named,
 * exemplified, sounded and drawn. There is no hovering on a phone, so a tap does what a
 * hover does in the browser. The names, articles, recordings and diagrams all come from the
 * extension's own table, generated into the app rather than written again, so the two say
 * the same things about a sound.
 */
class TooltipController(
    private val context: Context,
    private val speaker: Speaker,
    private val io: Executor,
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    /** What lets a Compose view live in a window this service put up. One at a time, because
     *  one card is open at a time. */
    private var host: OverlayHost? = null

    /**
     * What the card actually laid out, and where.
     *
     * An overlay window is invisible to a tool reading the screen, so without this a check can
     * know the card was asked for and never that it drew anything or where its buttons ended
     * up. The bounds are the ones the layout settled on, reported as each piece is placed and
     * written out once the placing has stopped.
     */
    private val placed = LinkedHashMap<String, android.graphics.Rect>()

    /** What the marks that say where a sound comes from report themselves under. */
    private val SOUND_MARK = "sound="

    /** Where the window itself is, so what is reported is where a finger has to go rather than
     *  where a piece sits inside a window nothing outside can see. */
    private val onScreen = IntArray(2)

    private val writeOut = Runnable {
        val out = StringBuilder("CARD ")
        view?.let { v ->
            val at = IntArray(2)
            v.getLocationOnScreen(at)
            out.append("card@").append(at[0]).append(',').append(at[1]).append(',')
                .append(v.width).append(',').append(v.height)
                .append(" scrollable=0 ")
        }
        for ((key, r) in placed) {
            val text = key.substringBeforeLast('@')
            out.append('[').append(text.replace(' ', '\u00b7')).append('@')
                .append(r.left).append(',').append(r.top).append(',')
                .append(r.width()).append(',').append(r.height()).append("] ")
        }
        android.util.Log.d("Phonetix", out.toString())
    }

    private val laidOut: (String, androidx.compose.ui.geometry.Rect) -> Unit = { text, where ->
        view?.getLocationOnScreen(onScreen)
        // A card carries one sound mark, so the one that arrives replaces the one before it.
        // A recording is looked for while the card is already up, so the mark changes from
        // the machine's to the person's by recomposition - and without this both stayed in
        // the report, at the same coordinates, leaving it saying the audio was both.
        if (text.startsWith(SOUND_MARK)) {
            placed.keys.removeAll { it.startsWith(SOUND_MARK) }
        }
        // Keyed by where it landed as well as by what it says: a transcription repeats its
        // symbols, and keying by the text alone kept only the last of each, which reads as a
        // transcription with letters missing.
        placed["$text@${where.left.roundToInt()},${where.top.roundToInt()}"] =
            android.graphics.Rect(
                where.left.roundToInt() + onScreen[0],
                where.top.roundToInt() + onScreen[1],
                where.right.roundToInt() + onScreen[0],
                where.bottom.roundToInt() + onScreen[1],
            )
        // Once, after the last piece has landed, rather than once per piece.
        view?.let { v ->
            v.removeCallbacks(writeOut)
            v.postDelayed(writeOut, 120)
        }
    }
    /** Which side of the card the arrow is on, once the card has been placed. */
    private var pointsDown: androidx.compose.runtime.MutableState<Boolean>? = null

    /** The scrolling list of symbols, refilled in place when a row opens or closes. */
    private var list: LinearLayout? = null
    private var scroller: ScrollView? = null
    private var expanded: String? = null
    private var shown: WordBox? = null

    /**
     * Views of ours that must not report themselves to accessibility.
     *
     * The card lives in an overlay window of the same process as the service, and a service
     * hears the events of every app including its own. The list scrolling therefore arrived
     * as a scroll of the screen, which takes the transcriptions down and the card with them:
     * the card could not be scrolled at all, because scrolling it was what closed it.
     */
    private val mute = object : View.AccessibilityDelegate() {
        override fun sendAccessibilityEvent(host: View, eventType: Int) {}
        override fun sendAccessibilityEventUnchecked(
            host: View,
            event: android.view.accessibility.AccessibilityEvent,
        ) {}
    }

    /** What the card that is up is about and where it ended up, for [StateDump]: a card is an
     *  overlay window, which nothing reading the screen can see. */
    fun state(): org.json.JSONObject {
        val card = view
        val at = IntArray(2)
        card?.getLocationOnScreen(at)
        return org.json.JSONObject()
            .put("up", card != null)
            .put("about", StateDump.box(shown))
            .put("answerGiven", given != null)
            .put("expanded", expanded ?: org.json.JSONObject.NULL)
            .put("clearOfHandAt", hand)
            .put(
                "card",
                if (card == null) org.json.JSONObject.NULL else org.json.JSONObject()
                    .put("x", at[0]).put("y", at[1])
                    .put("width", card.width).put("height", card.height),
            )
    }

    /**
     * Where the finger is, and with it the side button under it, so the card opens clear of
     * both. Zero while nothing is being dragged.
     */
    fun clearOf(handX: Int, handY: Int) {
        handAtX = handX
        hand = handY
    }

    private var hand = 0
    private var handAtX = 0

    fun show(box: WordBox) {
        // The same word again, where its box has only moved: the screen under the card is
        // read several times a second and a word that reflows by a pixel arrives as a new
        // box. Rebuilding the card for it tore the window down and put another up over and
        // over - a card that flickered, a tick under the thumb for each one, and on a screen
        // whose own content keeps changing, that without end. It is moved instead.
        val again = given == null && shown?.word == box.word && (view != null || pending == box.word)
        if (BuildConfig.DEBUG && !again) {
            android.util.Log.d("Phonetix", "TOOLTIP show ${box.word} shown=${shown?.word} pending=$pending up=${view != null}")
        }
        if (BuildConfig.DEBUG && !again) {
            android.util.Log.d(
                "Phonetix",
                "TOOLTIP open word=${box.word} ipa=${box.full} " +
                    "symbols=${IpaSymbols.explain(box.full).size}",
            )
        }
        shown = box
        if (again) {
            // Still being answered: the card goes where this box is once it is.
            if (view != null) moveTo(box)
            return
        }
        expanded = null
        given = null
        // The card for the word before is about the word before: down now, and the new one up
        // once its answer is in. The answer is worked out off the thread that draws, because a
        // dictionary lookup waits for the core, and the core can be busy - a lookup made here
        // once held the drawing thread long enough for the phone to call the app not
        // responding, with the circle on the word and no card.
        if (view != null) takeDown()
        val asked = box
        pending = asked.word
        asker.execute {
            val first = runCatching { answerFor(asked, null) }
                .onFailure { android.util.Log.w("Phonetix", "no answer for ${asked.word}", it) }
                .getOrNull()
            main.post {
                if (pending == asked.word) pending = null
                if (first != null && stillOn(asked)) render(shown!!, first)
            }
            // Then which of its meanings the line is about, where the line can be translated:
            // "land" in "the largest land animals" is Land, though the dictionary's first
            // reading of it is the verb. The card is redrawn only if that changes the answer.
            val settings = SettingsStore.current
            val source = asked.language.ifEmpty { Language.OURS }
            val into = settings.intoFor(source)
            // Read in its own language, the sentence as written says which sense it is: "bank"
            // by a river is its edge. Redrawn only where that changes what the card leads with.
            if (first != null && into.isEmpty() && asked.sentence.isNotBlank()) {
                val own = runCatching { answerFor(asked, asked.sentence) }.getOrNull()
                if (own != null && own.glosses.firstOrNull() != first.glosses.firstOrNull()) {
                    main.post { if (stillOn(asked) && view != null) render(shown!!, own) }
                }
                return@execute
            }
            if (first == null || into.isEmpty()) return@execute
            // A word no dictionary here holds is translated by the engine on this phone, and
            // the card says a machine did it: a card that only said how such a word sounds told
            // the reader nothing about what it means. Where the direction is not here yet it is
            // fetched for the next word, rather than kept waiting for under this one.
            // So is one only another language's dictionary held: "libre" on a Spanish line is
            // Spanish, and the English dictionary's entry for it said it the English way and
            // meant nothing. That dictionary answers only where the engine says the word back
            // unchanged, as it does for "feat" in a German song title.
            val borrowed = first.source.isNotEmpty() && first.source != source
            if ((first.says.isEmpty() && first.glosses.isEmpty()) || borrowed) {
                val guessed = guessFor(asked.word, source, into, first)
                if (guessed == null) {
                    Fetch.model(context, source, into)
                } else {
                    main.post { if (stillOn(asked) && view != null) render(shown!!, guessed) }
                }
                return@execute
            }
            // What the word is, said in the reader's language, where the card is to say it.
            val every = settings.showMeaning && settings.describeAll
            fun describe(answer: Answer) {
                if (!settings.showMeaning) return
                val told = describedFor(answer, into, every)
                if (told !== answer) main.post { if (stillOn(asked) && view != null) render(shown!!, told) }
            }
            if (asked.sentence.isBlank()) return@execute describe(first)
            val said = Reading.lineSaid(asked.sentence, source, into)
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "TOOLTIP line ${asked.sentence.take(60)} => $said")
            }
            if (said == null) return@execute describe(first)
            val better = runCatching { answerFor(asked, said) }.getOrNull()
                ?: return@execute describe(first)
            if (better.says == first.says && better.pos == first.pos) return@execute describe(first)
            if (BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "TOOLTIP line says ${better.says} for ${asked.word}")
            }
            main.post { if (stillOn(asked) && view != null) render(shown!!, better) }
            describe(better)
        }
    }

    /**
     * [answer] with what its word is, said in [into]: in place of a word that language has none
     * for ("sophomore"), under one it says the same way ("brunch" is "brunch" in Spanish too),
     * and under every word and every other reading where [every]. The dictionary's definitions
     * are English whatever the word's language, so each is put into [into] by the engine on
     * this phone; one it cannot translate is left out. The same answer where nothing changed.
     */
    private fun describedFor(answer: Answer, into: String, every: Boolean): Answer {
        if (into.isEmpty() || into == "en" || answer.source == into) return answer
        if (answer.state == Answer.State.Phrase || answer.state == Answer.State.Guess) return answer
        fun said(glosses: List<String>): String? = glosses.firstOrNull()
            ?.let(::sentenceOfGloss)?.takeIf { it.isNotBlank() }
            ?.let { describedIn(into, it) }
        val lead = answer.says.firstOrNull().orEmpty()
        val wanted = every || lead.isBlank() || lead.equals(answer.spelling, ignoreCase = true)
        val described = if (wanted) said(answer.glosses) else null
        var changed = described != null
        val readings = answer.readings.mapIndexed { at, reading ->
            if (at == 0 || !(every || reading.says.isEmpty())) return@mapIndexed reading
            val told = said(reading.glosses) ?: return@mapIndexed reading
            changed = true
            reading.copy(described = told)
        }
        return if (!changed) answer else answer.copy(described = described, readings = readings)
    }

    /**
     * A definition put into [into], as the extension's host asks it: a verb's as what a word
     * means, "Meaning: to eat brunch.", with the label cut off what comes back. Asked as it is,
     * the engine read its "to" as a purpose ("Para comer brunch."), and without it as an order.
     */
    private fun describedIn(into: String, text: String): String? {
        val verb = Regex("^to\\s", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val asked = if (verb) "Meaning: " + text.replaceFirstChar { it.lowercase() } else text
        val said = Reading.lineSaid(asked, "en", into) ?: return null
        if (!verb) return said
        val at = said.indexOf(':')
        return (if (at >= 0) said.substring(at + 1) else said).trim().replaceFirstChar { it.uppercase() }
    }

    /**
     * What a definition says the thing is, as the engine is to read it, as the extension's host
     * cuts it: its first sentence, without a bracket or a clause after "often" or "especially".
     */
    private fun sentenceOfGloss(gloss: String): String {
        var text = gloss.replace(Regex("\\s*\\([^()]*\\)"), "")
        Regex("[.;](\\s|$)").find(text)?.range?.first?.takeIf { it > 0 }?.let { text = text.substring(0, it) }
        Regex(",\\s+(often|especially|usually|typically|generally|chiefly|mainly|sometimes)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.range?.first?.takeIf { it > 0 }?.let { text = text.substring(0, it) }
        text = text.trim()
        return if (text.isEmpty()) "" else text.replaceFirstChar { it.uppercase() } + "."
    }

    /**
     * [known] with what the engine on this phone makes of [word] in [into], marked as a
     * machine's answer, or nothing where the engine has no such direction or says the word back.
     */
    private fun guessFor(word: String, source: String, into: String, known: Answer): Answer? {
        val said = Reading.lineSaid(word, source, into)?.trim()?.trim('.', ',', '!', '?')
            ?.takeIf { it.isNotBlank() && !it.equals(word, ignoreCase = true) }
            ?: return null
        // Said the way the screen's language says it, which a dictionary of another language
        // the answer came from does not.
        val spoken = if (known.source == source) known.ipa else {
            voiced(word, source).takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
        }
        return known.copy(
            state = Answer.State.Guess,
            says = listOf(said),
            glosses = emptyList(),
            ipa = spoken,
            symbols = spoken.firstOrNull()?.let(IpaSymbols::explain).orEmpty(),
            // What another language's dictionary said the word is belongs to that language.
            lemma = known.lemma.takeIf { known.source == source },
            form = known.form.takeIf { known.source == source },
            pos = known.pos.takeIf { known.source == source },
            readings = emptyList(),
            provenance = Answer.Provenance.Guess("bergamot"),
            source = source,
            target = into,
            lead = null,
        )
    }

    /** Whether the finger is still on [asked]'s word, with no other answer handed in. */
    private fun stillOn(asked: WordBox): Boolean =
        shown?.word == asked.word && given == null

    /** The word whose answer is being worked out, while it is. */
    private var pending: String? = null

    /** Where a word's answer is worked out: one at a time and in order, so the answer for
     *  the word the finger is on now is never overtaken by one for a word it has left. */
    private val asker = java.util.concurrent.Executors.newSingleThreadExecutor { job ->
        Thread(job, "phonetix-card").apply { isDaemon = true }
    }

    /** Where the card that is up was put, so the same card can be moved rather than rebuilt. */
    private var where: WindowManager.LayoutParams? = null

    /** The card follows the word it is about, without being built again. */
    private fun moveTo(box: WordBox) {
        val card = view ?: return
        val lp = where ?: return
        place(card, lp, box)
    }

    /**
     * Several words, answered as one.
     *
     * The answer is handed in rather than looked up here: a phrase is the engine's to answer
     * and the engine is not something to wait for on the thread that draws. [box] is where the
     * run sat and what it says, which is what the card points at and takes its colours from.
     */
    fun showPhrase(box: WordBox, answer: Answer) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "TOOLTIP phrase=${box.word} says=${answer.says}")
        }
        expanded = null
        shown = box
        given = answer
        render(box, answer)
    }

    /** An answer somebody else worked out, for the card that cannot work it out itself. */
    private var given: Answer? = null

    fun hide() {
        if (BuildConfig.DEBUG && view != null) {
            android.util.Log.d("Phonetix", "TOOLTIP closed")
        }
        speaker.stop()
        // Before the view leaves the window: a Compose view torn down after its lifecycle has
        // ended leaks the composition it was holding.
        host?.hidden()
        host = null
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        where = null
        list = null
        scroller = null
        shown = null
        pending = null
        expanded = null
        given = null
    }

    /** The card's window down, leaving what it was asked about as it is. */
    private fun takeDown() {
        host?.hidden()
        host = null
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
        where = null
        placed.clear()
    }

    /** What the card on screen says, for the actions offered round the finger. */
    private var answered: Pair<WordBox, Answer>? = null

    /** Where the card is on the screen, or nothing while none is up. */
    fun cardRect(): android.graphics.Rect? {
        val card = view ?: return null
        val at = IntArray(2)
        card.getLocationOnScreen(at)
        if (card.width <= 0) return null
        return android.graphics.Rect(at[0], at[1], at[0] + card.width, at[1] + card.height)
    }

    /**
     * Do one of the card's actions for [box]'s word, chosen round the finger after the card has
     * gone: "play" says it in the accent the reader reads its language in, "article" opens its
     * Wiktionary page at the section for that language, the same page the extension opens.
     */
    fun act(action: String, box: WordBox) {
        val answer = answered?.takeIf { it.first.word == box.word }?.second
        val source = answer?.source?.ifEmpty { null } ?: box.language.ifEmpty { Language.OURS }
        if (BuildConfig.DEBUG) {
            android.util.Log.d("Phonetix", "ACTED $action ${answer?.spelling ?: box.word} in $source")
        }
        when (action) {
            "play" -> speaker.say(
                answer?.spelling ?: box.word,
                Accents.voiceOf(source, SettingsStore.current.accentFor(source)),
            )
            "article" -> open(wiktionary(answer ?: Answer.ofTranscription(box.word, "", source)))
        }
    }

    private fun render(box: WordBox, answer: Answer) {
        answered = box to answer
        takeDown()
        val card = build(box, answer) ?: return
        card.accessibilityDelegate = mute
        val metrics = context.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // An accessibility overlay, not an application one. A window put up with
            // SYSTEM_ALERT_WINDOW is hidden by the platform over any screen that asks for it -
            // the settings app asks, and so does every permission dialog, to stop a window from
            // covering what the reader is agreeing to. Over those screens the windows were still
            // there, still visible, still in the right places, and nothing was painted: a real
            // app that showed no transcriptions at all while the log said it had drawn eighteen.
            // This type belongs to the service that is already reading the screen, is exempt
            // from that hiding, and needs no permission of its own.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Takes nothing: the card is read while the finger is on the side button and is
            // gone when it lifts, so every touch belongs to the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = (box.rect.centerX() - metrics.widthPixels * 0.3f).roundToInt()
                .coerceAtLeast(dp(8).roundToInt())
            // Where it will stay, as nearly as can be known before it has been measured.
            //
            // How tall it is depends on how many symbols the word has, and that is only known
            // once it has been laid out - so this uses the height of the card before it,
            // which is within a line or two of the next one. Placed below the word and moved
            // above a frame later, the reader saw it appear under the word and jump over the
            // circle; held invisible until placed, they saw nothing at all while the circle
            // was moving, because each card was torn down for the next word before the frame
            // that would have shown it, and one finally appeared as the finger lifted.
            y = spotFor(box, lastWidth, lastHeight).y
        }
        runCatching { wm.addView(card, lp) }
            .onSuccess {
                view = card
                where = lp
                host?.shown()
                card.post {
                    place(card, lp, box)
                    // What the card actually laid out, once it has been measured and moved
                    // to where it will stay: an overlay window is invisible to uiautomator,
                    // so without this a test can only know that the card was asked for,
                    // never that it drew anything or where its buttons ended up. Reported
                    // after the move, because a position read before it is the position the
                    // card no longer has.
                    if (BuildConfig.DEBUG) card.post { describe(card) }
                }
            }
            .onFailure { android.util.Log.w("Phonetix", "tooltip addView failed", it) }
    }

    /**
     * Put the measured card where it fits: below the word, above it, or as far up as it can
     * go when it is taller than either gap.
     *
     * Guessing the height beforehand put a long card half off the bottom of the screen, with
     * the buttons that close it out of reach.
     */
    /** The card's left edge on screen, so the arrow can be put where the word is. */
    private fun cardLeft(): Float =
        ((context.resources.displayMetrics.widthPixels -
            context.resources.displayMetrics.widthPixels * 0.88f) / 2f)

    private fun density(): Float = context.resources.displayMetrics.density

    /** How big the last card was, as the guess for where the next one goes. */
    private var lastHeight = 0
    private var lastWidth = 0

    /**
     * Where a card this big goes beside [box]: clear of the word and the circle on it, and
     * clear of the side button under the finger.
     *
     * On the side of the word away from the hand first, then the other side, then beside the
     * word; where none of those is clear, whichever covers least of the button. Put below the
     * word whatever the hand was doing, the card covered the button the reader was holding.
     */
    private fun spotFor(box: WordBox, width: Int, height: Int): android.graphics.Point {
        val metrics = context.resources.displayMetrics
        val margin = dp(8).roundToInt()
        val gap = dp(12).roundToInt()
        val w = if (width > 0) width else dp(200).roundToInt()
        val h = if (height > 0) height else dp(100).roundToInt()
        // The word with the circle drawn round it, which is a little wider than the word.
        val ring = dp(30).roundToInt()
        val word = android.graphics.Rect(
            (box.rect.centerX() - maxOf(box.rect.width() / 2f, ring.toFloat())).roundToInt(),
            (box.rect.centerY() - maxOf(box.rect.height() / 2f, ring.toFloat())).roundToInt(),
            (box.rect.centerX() + maxOf(box.rect.width() / 2f, ring.toFloat())).roundToInt(),
            (box.rect.centerY() + maxOf(box.rect.height() / 2f, ring.toFloat())).roundToInt(),
        )
        val button = dp(36).roundToInt()
        val hand = if (hand > 0) android.graphics.Rect(
            handAtX - button, this.hand - button, handAtX + button, this.hand + button,
        ) else null
        val centredX = (word.centerX() - w / 2).coerceIn(margin, (metrics.widthPixels - w - margin).coerceAtLeast(margin))
        val midY = word.centerY() - h / 2
        val above = android.graphics.Point(centredX, word.top - gap - h)
        val below = android.graphics.Point(centredX, word.bottom + gap)
        val left = android.graphics.Point(word.left - gap - w, midY.coerceAtLeast(margin))
        val right = android.graphics.Point(word.right + gap, midY.coerceAtLeast(margin))
        val handBelow = hand != null && hand.centerY() > word.centerY()
        val order = if (handBelow || hand == null && word.top > metrics.heightPixels / 2) {
            listOf(above, below, left, right)
        } else {
            listOf(below, above, left, right)
        }
        fun rectOf(p: android.graphics.Point) = android.graphics.Rect(p.x, p.y, p.x + w, p.y + h)
        fun onScreen(r: android.graphics.Rect) = r.left >= margin && r.top >= margin &&
            r.right <= metrics.widthPixels - margin && r.bottom <= metrics.heightPixels - margin
        fun covers(r: android.graphics.Rect, other: android.graphics.Rect?): Int {
            if (other == null) return 0
            val cut = android.graphics.Rect()
            return if (cut.setIntersect(r, other)) cut.width() * cut.height() else 0
        }
        order.firstOrNull { p ->
            val r = rectOf(p)
            onScreen(r) && covers(r, word) == 0 && covers(r, hand) == 0
        }?.let { return it }
        // Nothing is clear: kept on the screen and off the word, covering as little of the
        // button as can be had.
        return order
            .map { p ->
                android.graphics.Point(
                    p.x.coerceIn(margin, (metrics.widthPixels - w - margin).coerceAtLeast(margin)),
                    p.y.coerceIn(margin, (metrics.heightPixels - h - margin).coerceAtLeast(margin)),
                )
            }
            .minBy { p -> covers(rectOf(p), word) * 4 + covers(rectOf(p), hand) }
    }

    private fun place(card: View, lp: WindowManager.LayoutParams, box: WordBox) {
        if (card.width > 0) lastWidth = card.width
        if (card.height > 0) lastHeight = card.height
        val spot = spotFor(box, card.width, card.height)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "Phonetix",
                "CARDAT ${box.word} at=${spot.x},${spot.y} size=${card.width}x${card.height} " +
                    "word=${box.rect.left.toInt()},${box.rect.top.toInt()} hand=$handAtX,$hand",
            )
        }
        if (spot.x == lp.x && spot.y == lp.y) return
        lp.x = spot.x
        lp.y = spot.y
        runCatching { wm.updateViewLayout(card, lp) }
    }

    /** Every piece of text the card put on screen, with where it ended up. */
    private fun describe(root: View) {
        val out = StringBuilder("CARD ")
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        out.append("card@").append(at[0]).append(',').append(at[1]).append(',')
            .append(root.width).append(',').append(root.height).append(' ')
        scroller?.let { sv ->
            // How much of the list is out of sight, so a test knows whether there is
            // anything to scroll before it insists that scrolling moved something.
            val room = (sv.getChildAt(0)?.height ?: 0) - sv.height
            out.append("scrollable=").append(room.coerceAtLeast(0)).append(' ')
        }
        fun walk(v: View) {
            if (v is TextView && v.text.isNotEmpty()) {
                v.getLocationOnScreen(at)
                out.append('[').append(v.text.toString().replace(' ', '\u00b7')).append('@')
                    .append(at[0]).append(',').append(at[1]).append(',')
                    .append(v.width).append(',').append(v.height).append(']')
            }
            if (v is ImageView && v.drawable != null) {
                v.getLocationOnScreen(at)
                out.append("[image@").append(at[0]).append(',').append(at[1]).append(',')
                    .append(v.width).append(',').append(v.height).append(']')
            }
            if (v is LinearLayout) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            if (v is ScrollView) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        android.util.Log.d("Phonetix", out.toString())
    }

    private fun dp(v: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    )

    /** The card's own colours, taken from the surface the word was sitting on. */
    private class Palette(
        val surface: Int,
        val onSurface: Int,
        val muted: Int,
        val accent: Int,
        val line: Int,
    )

    private fun palette(box: WordBox): Palette {
        val dark = box.background == 0 || isDark(box.background)
        return Palette(
            surface = if (dark) Color.rgb(0x24, 0x1C, 0x17) else Color.rgb(0xFF, 0xFB, 0xF2),
            onSurface = if (dark) Color.rgb(0xF5, 0xED, 0xE0) else Color.rgb(0x2B, 0x21, 0x17),
            muted = if (dark) Color.rgb(0xC9, 0xB7, 0x9C) else Color.rgb(0x6B, 0x5B, 0x45),
            accent = if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0xB4, 0x53, 0x09),
            line = if (dark) Color.rgb(0x3A, 0x2E, 0x25) else Color.rgb(0xE3, 0xD7, 0xBE),
        )
    }

    /**
     * The card, drawn from one Answer and the generated tokens, which is the same card the
     * browser draws from the same values.
     *
     * The transcription alone is what a build with no dictionary pack has, and it is a real
     * answer to how a word is said even though it answers nothing about meaning. A tap on a
     * symbol says what that sound is on the card's own line for it, so the word stays in sight
     * and the card does not change height under the finger.
     */
    /**
     * What the card says about [box]'s word, asked of the cascade in the languages the reader
     * is reading between. Where no pack answers, another dictionary this phone holds is asked,
     * and failing that the voice says how the word sounds. [said] is the word's line in the
     * reader's language, where it has been translated. Called off the thread that draws.
     */
    private fun answerFor(box: WordBox, said: String?): Answer {
        val settings = SettingsStore.current
        val source = box.language.ifEmpty { Language.OURS }
        val answer = Reading.lookUp(
            box.word, source, settings.intoFor(source).ifEmpty { source },
            settings.accentFor(source), box.before, said ?: box.decided,
        )
            ?.takeIf { it.found }
            ?: elsewhere(box.word, source, settings)?.also { found ->
                // Answered by a dictionary this phone holds for a language on none of the
                // reader's lists: met all the same.
                val lang = found.source
                if (lang.isNotEmpty() && !settings.reads(lang) && lang != settings.learn &&
                    lang !in settings.alsoLearn
                ) SettingsStore.meet(lang, screen)
            }
            ?: unlisted(box, source, settings)?.let { lang ->
                // A language on none of the reader's lists, which they are met in: said in it,
                // and offered to them in the settings once they keep meeting it.
                SettingsStore.meet(lang, screen)
                return Answer.ofTranscription(box.word, voiced(box.word, lang), lang)
            }
            ?: return Answer.ofTranscription(box.word, voiced(box.word, source), source)
        // A form the dictionary lists only under its lemma has no transcription of its own:
        // the voice says it, rather than the card going without or borrowing the lemma's.
        if (answer.ipa.isNotEmpty()) return answer
        val spoken = voiced(box.word, answer.source.ifEmpty { source })
        if (spoken.isBlank()) return answer
        return answer.copy(ipa = listOf(spoken), symbols = IpaSymbols.explain(spoken))
    }

    private fun build(box: WordBox, answer: Answer): View? {
        // Light or dark by the app it is drawn over rather than by the system setting: a card
        // is read against the screen it lands on. Which palette is the product's own, so the
        // card and the app that switches it on are one set of colours.
        val dark = box.background == 0 || isDark(box.background)
        val palette = Tokens.palette(themeNamed(SettingsStore.current.theme), dark)
        val settings = SettingsStore.current
        // The card says how a word sounds and what it means, except that a word in a language
        // the reader reads is not translated: it is only said.
        val lang = answer.source.ifEmpty { box.language }
        // Into the reader's own language, or for a word of one they read into the one they
        // are learning, which is practice.
        // What the card means as well as how it is said, where the reader left the meaning on.
        val meaning = settings.intoFor(lang).isNotEmpty() && settings.showMeaning
        if (!glanceHasSomething(answer, meaning, settings.showSound)) return null
        val fresh = OverlayHost(context)
        host = fresh
        fresh.view.setContent {
            GlanceCard(
                answer = answer,
                palette = palette,
                meaning = meaning,
                report = if (BuildConfig.DEBUG) laidOut else null,
                narrow = settings.narrow,
                hideStress = settings.hideStress,
                sound = settings.showSound,
                grammar = settings.showMeaning && settings.showGrammar,
                others = settings.showMeaning && settings.showOthers,
                // The dictionaries define every word in English, so an English word the reader
                // reads gets its definition, under what it is in the language they learn where
                // they learn one; any other word of a language they read is only said.
                defining = settings.reads(lang) && lang == "en" && settings.showMeaning,
            )
        }
        return fresh.view
    }

    /**
     * The word in another dictionary this phone holds, where the screen's own has nothing:
     * "feat" in a song title on a German screen is English, and a card that opened on nothing
     * was no card at all.
     */
    private fun elsewhere(word: String, source: String, settings: Settings): Answer? =
        (listOf(Language.OURS) + Packs.held(context))
            .distinct()
            .filter { it != source }
            .firstNotNullOfOrNull { lang ->
                Reading.lookUp(word, lang, settings.intoFor(lang).ifEmpty { lang })?.takeIf {
                    it.found && it.state != Answer.State.None && it.state != Answer.State.NoPack
                }
            }

    /** Where the reader is, as a language they are met in counts it: the app in front. */
    @Volatile
    var screen: String = ""

    /**
     * The language a word no dictionary here has is most likely in, judged on its clause,
     * where that is a language this product knows and on none of the reader's lists; or none.
     * A Spanish question quoted in an English caption is Spanish however little of it there is.
     */
    private fun unlisted(box: WordBox, source: String, settings: Settings): String? {
        val clause = clauseOf(box.sentence.ifBlank { box.word }, box.word)
        val lang = Eld.detect(clause).language ?: return null
        if (lang == source || lang !in Languages.all()) return null
        if (settings.reads(lang) || lang == settings.learn || lang in settings.alsoLearn) return null
        return lang
    }

    /** The stretch of a line a word is in, between the marks that end or open a clause. */
    private fun clauseOf(line: String, word: String): String {
        val at = line.indexOf(word)
        if (at < 0) return word
        val stops = ".!?¿¡;:\"“”«»()[]"
        var from = at
        while (from > 0 && line[from - 1] !in stops) from--
        var to = at + word.length
        while (to < line.length && line[to] !in stops) to++
        return line.substring(from, to).trim()
    }

    /** How the voice for [lang] says [word], where no dictionary does. */
    private fun voiced(word: String, lang: String): String =
        Speech
            .phonemes(Accents.voiceOf(lang, ""), listOf(word))[word].orEmpty()

    /** Sagittal sections are SVGs, so Wikimedia is asked to raster one at the right width. */
    private fun loadDiagram(file: String, into: ImageView) {
        val width = (context.resources.displayMetrics.widthPixels * 0.7f).roundToInt()
        io.execute {
            // With timeouts: a host that never answers would otherwise hold this thread for
            // as long as the system's default, which is minutes.
            val bmp = runCatching {
                val connection = URL(wikimediaThumbUrl(file, width)).openConnection().apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                connection.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) main.post { into.setImageBitmap(bmp) }
        }
    }

    private fun pill(label: String, fg: Int, surface: Int, line: Int, onTap: () -> Unit): View =
        TextView(context).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12).roundToInt(), dp(6).roundToInt(), dp(12).roundToInt(), dp(6).roundToInt())
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(14)
                setStroke(dp(1).roundToInt(), line)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(8).roundToInt()
            layoutParams = lp
            setOnClickListener { onTap() }
        }

    private fun divider(color: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1).roundToInt(),
        ).apply { topMargin = dp(10).roundToInt(); bottomMargin = dp(4).roundToInt() }
        setBackgroundColor(color)
    }

    private fun open(url: String) {
        hide()
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun isDark(c: Int): Boolean =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) < 140f
}

/**
 * A scrolling list that takes the height it needs, up to a limit.
 *
 * A ScrollView given a fixed height leaves a gap under a short list; given a free one it
 * grows past the bottom of the screen and takes the card's own buttons with it.
 */
private class BoundedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
        )
    }
}
