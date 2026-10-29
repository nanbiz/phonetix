package io.github.nanbiz.phonetix.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val enabled: Boolean = false,
    /** Transcribe one word in every N. */
    val density: Int = 12,
    /** Empty means every app; otherwise only these packages. */
    val apps: Set<String> = emptySet(),
    val allApps: Boolean = true,
    /**
     * Whether the transcriptions themselves can be touched.
     *
     * They are windows lying over the words, so a finger that comes down on one lands on it
     * and not on the app underneath - and a window that has taken a gesture keeps it, so the
     * swipe that touch began is lost entirely and the page does not move. Most of a page of
     * text is covered in transcriptions. Off, they are a picture: every touch reaches the app
     * and scrolling is exactly as it was without the overlay. On, a tap on a word opens its
     * card, at the cost of the swipes that start on one - which is what the product is for,
     * so it is what it does unless the reader says otherwise.
     */
    val touchWords: Boolean = true,
    /**
     * The language the reader is reading into.
     *
     * Empty until they choose one, and with nothing chosen a word answers with how it is
     * said and nothing about what it means: the dictionary needs to know which language the
     * answer should come back in.
     */
    val target: String = "",
    /**
     * The languages the reader chose to read as they are, which nothing translates: a word in
     * one of these is only said. Nothing until they choose, and then [untranslated] answers.
     */
    val known: Set<String>? = null,
    /**
     * The language the reader is learning, which is neither their own nor one they read: a
     * card for a word of a language they read says what it is in it. Empty where they learn
     * none. Their choice in settings, which the panel's last language never changes.
     */
    val learn: String = "",
    /** The other languages the reader is learning, after [learn], which is the one of them a
     *  word of a language they read is put into. Kept for the settings to show. */
    val alsoLearn: List<String> = emptyList(),
    /** The language last asked in in the panel, which is what a word they are looking for
     *  comes back in. */
    val learning: String = "",
    /** Which of the panel's two languages it last answered in: the way its arrow points when it
     *  opens, and so the language a word said into it is heard in. Empty until it answered. */
    val into: String = "",
    /** The languages they have asked in, most recent first: a reader asks in two or three,
     *  and a list of fifty in alphabetical order makes them hunt for one of them every time. */
    val recent: List<String> = emptyList(),
    /** The palette every surface of ours is drawn in, by the name the tokens key it under. */
    val theme: String = "phonetix",
    /** Which side of it: "system", "light" or "dark". */
    val dark: String = "system",
    /**
     * Which accent to read each language in, as language tag to accent tag: en → en-us.
     *
     * Per language rather than one for everything, because an accent is only meaningful
     * relative to a language: a screen in German has nothing to do with the reader's choice
     * between British and American English, and one field for both meant choosing an accent
     * for one language threw away the choice made for every other.
     */
    val accents: Map<String, String> = emptyMap(),
    /** Where the dictionaries come from. The reader's own, and nowhere in the source. */
    val packHost: String = "",
    /** Which side of the screen the mark rests on: "right", "left", or "free" for neither,
     *  where it is left wherever it was last put down. The hand is taken to
     *  come onto the screen from that corner, and the circle the mark carries is held away
     *  from it, so the hand is never over the word it is on. */
    val side: String = "right",
    /** Whether the button waits at the height the reader chose rather than down by the hand.
     *  Off is the ordinary thing: it sits low on its side, where a thumb is. */
    val pin: Boolean = false,
    /** How far down its side the button waits when it is pinned, as a share of the screen,
     *  so it means the same place however big the screen is. */
    val restY: Float = 0.8f,
    /**
     * Narrow transcriptions rather than broad ones.
     *
     * Broad is the sounds that tell words apart; narrow keeps the detail of how they are
     * actually said - aspiration, devoicing. The same choice the extension offers, decided in
     * the same place: the core strips the detail, not the surface drawing it.
     */
    val narrow: Boolean = false,
    /** Leave the stress marks off the line over a word. The card always shows them. */
    val hideStress: Boolean = true,
    /**
     * What a card says, part by part, the extension's same switches: how the word is said, what
     * it means, and with the meaning, which form it is and the other words its spelling can be.
     */
    val showSound: Boolean = true,
    val showMeaning: Boolean = true,
    val showGrammar: Boolean = true,
    val showOthers: Boolean = true,
    /** A description in the reader's language under every word, not only under those their
     *  language has no word for or says the same way. */
    val describeAll: Boolean = false,
) {
    /** Which accent this language is read in: the reader's choice, or the language's default,
     *  since a reading in no accent in particular is a mix of them. */
    fun accentFor(lang: String): String =
        // Only one the language still offers: Portugal was once stored as plain "pt".
        accents[lang]?.takeIf { chosen -> Accents.of(lang).any { it.id == chosen } }
            ?: Accents.defaultOf(lang)

    /**
     * The languages nothing translates besides the reader's own: the ones they chose, or until
     * they choose, the languages the phone is set to, as the browser takes the ones it is set
     * to - but never the one they are learning, which is the language they most want read.
     */
    val untranslated: Set<String>
        get() = known ?: run {
            val locales = android.os.LocaleList.getDefault()
            (0 until locales.size()).map { locales[it].language }
                .filter { it.isNotEmpty() && it != target && it != learning }
                .toSet()
        }

    /** Whether a word in [lang] is translated, or left as it is because the reader reads it. */
    fun translates(lang: String): Boolean =
        lang.isNotEmpty() && lang != target && lang !in untranslated

    /** Whether the reader reads [lang] as it is: their own, or one of the others they read. */
    fun reads(lang: String): Boolean = lang.isNotEmpty() && !translates(lang)

    /**
     * The language a word in [lang] is read into: the reader's own for one they do not read,
     * the one they are learning for one they do, which is practice, and nothing where they
     * read it and learn nothing.
     */
    fun intoFor(lang: String): String = when {
        translates(lang) -> target
        reads(lang) && learn.isNotEmpty() && learn != lang -> learn
        else -> ""
    }

    /**
     * Whether nothing is painted over the page, which is always: the words of a screen are
     * read so the side button can answer about the one it is dragged over, and the page itself
     * is left as its app drew it.
     */
    val quiet: Boolean get() = true
}

/**
 * One store read by both the UI and the accessibility service. They run in the same
 * process, so a single in-memory flow keeps them in step; SharedPreferences is only the
 * durable copy, reloaded when the service starts cold without the activity.
 */
object SettingsStore {
    private const val FILE = "phonetix.settings"

    /** How many languages are worth remembering: a reader asks in two or three. */
    private const val RECENT = 5
    private const val K_ENABLED = "enabled"
    private const val K_DENSITY = "density"
    private const val K_APPS = "apps"
    private const val K_ALL = "all_apps"
    private const val K_TOUCH = "touch_words"
    private const val K_TARGET = "target"
    private const val K_LEARN = "learn"
    private const val K_ALSO_LEARN = "also_learn"
    private const val K_LEARNING = "learning"
    private const val K_INTO = "into"
    private const val K_KNOWN = "known"
    private const val K_RECENT = "recent"
    private const val K_HOST = "pack_host"
    private const val K_SIDE = "side"
    private const val K_PIN = "pinned"
    private const val K_REST_Y = "rest_y"
    private const val K_ACCENTS = "accents"
    private const val K_NARROW = "narrow"
    private const val K_STRESS = "hide_stress"
    private const val K_THEME = "theme"
    private const val K_DARK = "dark"
    private const val K_SOUND = "show_sound"
    private const val K_MEANING = "show_meaning"
    private const val K_GRAMMAR = "show_grammar"
    private const val K_OTHERS = "show_others"
    private const val K_DESCRIBE = "describe_all"

    private var prefs: android.content.SharedPreferences? = null
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    /** The app, for what has to be read out of it when a setting changes: the interface's words. */
    private var app: Context? = null

    fun init(context: Context) {
        if (prefs != null) return
        app = context.applicationContext
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _state.value = Settings(
            enabled = p.getBoolean(K_ENABLED, false),
            density = p.getInt(K_DENSITY, 12),
            apps = p.getStringSet(K_APPS, emptySet())?.toSet() ?: emptySet(),
            allApps = p.getBoolean(K_ALL, true),
            touchWords = p.getBoolean(K_TOUCH, true),
            target = p.getString(K_TARGET, "") ?: "",
            learn = p.getString(K_LEARN, "") ?: "",
            alsoLearn = (p.getString(K_ALSO_LEARN, "") ?: "").split(',').filter { it.isNotBlank() },
            learning = p.getString(K_LEARNING, "") ?: "",
            into = p.getString(K_INTO, "") ?: "",
            known = p.getStringSet(K_KNOWN, null)?.toSet(),
            recent = (p.getString(K_RECENT, "") ?: "").split(',').filter { it.isNotBlank() },
            theme = p.getString(K_THEME, "phonetix") ?: "phonetix",
            dark = p.getString(K_DARK, "system") ?: "system",
            packHost = p.getString(K_HOST, "") ?: "",
            side = p.getString(K_SIDE, "right") ?: "right",
            pin = p.getBoolean(K_PIN, false),
            restY = p.getFloat(K_REST_Y, 0.8f),
            // Stored as one entry per language, because a set of strings is what preferences
            // can hold and a map is what the rest of this asks for.
            accents = (p.getStringSet(K_ACCENTS, emptySet()) ?: emptySet())
                .mapNotNull { row ->
                    val at = row.indexOf('=')
                    if (at <= 0) null else row.take(at) to row.substring(at + 1)
                }
                .toMap(),
            narrow = p.getBoolean(K_NARROW, false),
            hideStress = p.getBoolean(K_STRESS, true),
            showSound = p.getBoolean(K_SOUND, true),
            showMeaning = p.getBoolean(K_MEANING, true),
            showGrammar = p.getBoolean(K_GRAMMAR, true),
            showOthers = p.getBoolean(K_OTHERS, true),
            describeAll = p.getBoolean(K_DESCRIBE, false),
        )
        Interface.follow(context.applicationContext, _state.value.target)
    }

    private fun update(block: (Settings) -> Settings) {
        val next = block(_state.value)
        _state.value = next
        app?.let { Interface.follow(it, next.target) }
        prefs?.edit()
            ?.putBoolean(K_ENABLED, next.enabled)
            ?.putInt(K_DENSITY, next.density)
            ?.putStringSet(K_APPS, next.apps)
            ?.putBoolean(K_ALL, next.allApps)
            ?.putBoolean(K_TOUCH, next.touchWords)
            ?.putString(K_TARGET, next.target)
            ?.putString(K_LEARN, next.learn)
            ?.putString(K_ALSO_LEARN, next.alsoLearn.joinToString(","))
            ?.putString(K_LEARNING, next.learning)
            ?.putString(K_INTO, next.into)
            ?.putStringSet(K_KNOWN, next.known)
            ?.putString(K_RECENT, next.recent.joinToString(","))
            ?.putString(K_HOST, next.packHost)
            ?.putString(K_SIDE, next.side)
            ?.putBoolean(K_PIN, next.pin)
            ?.putFloat(K_REST_Y, next.restY)
            ?.putStringSet(K_ACCENTS, next.accents.map { (lang, id) -> "$lang=$id" }.toSet())
            ?.putBoolean(K_NARROW, next.narrow)
            ?.putBoolean(K_STRESS, next.hideStress)
            ?.putString(K_THEME, next.theme)
            ?.putString(K_DARK, next.dark)
            ?.putBoolean(K_SOUND, next.showSound)
            ?.putBoolean(K_MEANING, next.showMeaning)
            ?.putBoolean(K_GRAMMAR, next.showGrammar)
            ?.putBoolean(K_OTHERS, next.showOthers)
            ?.putBoolean(K_DESCRIBE, next.describeAll)
            ?.apply()
    }

    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }
    fun setDensity(v: Int) = update { it.copy(density = v.coerceIn(Frequency.DMIN, Frequency.DMAX)) }
    fun setAllApps(v: Boolean) = update { it.copy(allApps = v) }
    fun setTouchWords(v: Boolean) = update { it.copy(touchWords = v) }
    fun setShowSound(v: Boolean) = update { it.copy(showSound = v) }
    fun setShowMeaning(v: Boolean) = update { it.copy(showMeaning = v) }
    fun setShowGrammar(v: Boolean) = update { it.copy(showGrammar = v) }
    fun setShowOthers(v: Boolean) = update { it.copy(showOthers = v) }
    fun setDescribeAll(v: Boolean) = update { it.copy(describeAll = v) }

    /** The reader's own language, which everything foreign is translated into. */
    fun setTarget(v: String) = update { it.copy(target = v) }
    /** The languages left as they are, or nothing to go back to the phone's own. */
    fun setKnown(v: Set<String>?) = update { it.copy(known = v) }
    /** A language asked in where nothing is translated: kept at the top of the recent ones,
     *  and taken as the one being learned only where it is not the reader's own. */
    fun askedIn(v: String) = update {
        it.copy(
            learning = if (v != it.target) v else it.learning,
            recent = (listOf(v) + it.recent).filter { lang -> lang.isNotBlank() }
                .distinct()
                .take(RECENT),
        )
    }

    /** The language being learnt, or nothing. */
    fun setLearn(v: String) = update { it.copy(learn = v) }

    /** The other languages being learnt, in the reader's order. */
    fun setAlsoLearn(v: List<String>) = update { it.copy(alsoLearn = v) }

    /** What they are learning now, and the few they have asked in before it. */
    fun setLearning(v: String) = update {
        it.copy(
            learning = v,
            recent = (listOf(v) + it.recent).filter { lang -> lang.isNotBlank() }
                .distinct()
                .take(RECENT),
        )
    }
    /** The language the panel answered in last. */
    fun setInto(v: String) = update { it.copy(into = v) }
    fun setTheme(v: String) = update { it.copy(theme = v) }
    fun setDark(v: String) = update { it.copy(dark = v) }
    fun setPackHost(v: String) = update { it.copy(packHost = v.trim()) }
    /** Whether the button waits where it was put rather than down by the hand. */
    fun setPin(v: Boolean) = update { it.copy(pin = v) }

    /** How far down its side the button waits: on the screen whatever is asked for. */
    fun setRestY(y: Float) = update { it.copy(restY = y.coerceIn(0f, 1f)) }

    fun setSide(v: String) = update {
        it.copy(side = if (v == "left" || v == "free") v else "right")
    }

    /** Read one language in one accent, leaving the choice made for every other alone. */
    fun setAccent(lang: String, accent: String) = update {
        val next = it.accents.toMutableMap()
        if (accent.isEmpty()) next.remove(lang) else next[lang] = accent
        it.copy(accents = next)
    }
    fun setNarrow(v: Boolean) = update { it.copy(narrow = v) }
    fun setHideStress(v: Boolean) = update { it.copy(hideStress = v) }
    fun toggleApp(pkg: String) = update {
        it.copy(apps = if (pkg in it.apps) it.apps - pkg else it.apps + pkg)
    }

    fun allows(pkg: String?): Boolean {
        val s = _state.value
        if (!s.enabled) return false
        if (s.allApps) return true
        return pkg != null && pkg in s.apps
    }
}
