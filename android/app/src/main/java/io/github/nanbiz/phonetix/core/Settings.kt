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
     * and scrolling is exactly as it was without the overlay. On, a press held on a word
     * opens its card, at the cost of the swipes that start on one.
     */
    val touchWords: Boolean = false,
    /**
     * The language the reader is reading into.
     *
     * Empty until they choose one, and with nothing chosen a word answers with how it is
     * said and nothing about what it means: the dictionary needs to know which language the
     * answer should come back in.
     */
    val target: String = "",
    /** What is drawn over a word: "ipa", "gloss", "gloss+ipa" or "replace". */
    val layer: String = "gloss+ipa",
    /** The accent to read in, where its difference from the standard is a rule. */
    val accent: String = "",
    /** Where the dictionaries come from. The reader's own, and nowhere in the source. */
    val packHost: String = "",
    /**
     * The lens: a small window dragged over a word to be told what it means.
     *
     * On by default, because it is the only way to ask about a word without the overlay
     * taking the screen's touches, and an app whose answers cannot be reached is an app that
     * only decorates.
     */
    val lens: Boolean = true,
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
)

/**
 * One store read by both the UI and the accessibility service. They run in the same
 * process, so a single in-memory flow keeps them in step; SharedPreferences is only the
 * durable copy, reloaded when the service starts cold without the activity.
 */
object SettingsStore {
    private const val FILE = "phonetix.settings"
    private const val K_ENABLED = "enabled"
    private const val K_DENSITY = "density"
    private const val K_APPS = "apps"
    private const val K_ALL = "all_apps"
    private const val K_TOUCH = "touch_words"
    private const val K_TARGET = "target"
    private const val K_LAYER = "layer"
    private const val K_HOST = "pack_host"
    private const val K_LENS = "lens"
    private const val K_ACCENT = "accent"
    private const val K_NARROW = "narrow"
    private const val K_STRESS = "hide_stress"

    private var prefs: android.content.SharedPreferences? = null
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _state.value = Settings(
            enabled = p.getBoolean(K_ENABLED, false),
            density = p.getInt(K_DENSITY, 12),
            apps = p.getStringSet(K_APPS, emptySet())?.toSet() ?: emptySet(),
            allApps = p.getBoolean(K_ALL, true),
            touchWords = p.getBoolean(K_TOUCH, false),
            target = p.getString(K_TARGET, "") ?: "",
            layer = p.getString(K_LAYER, "gloss+ipa") ?: "gloss+ipa",
            packHost = p.getString(K_HOST, "") ?: "",
            lens = p.getBoolean(K_LENS, true),
            accent = p.getString(K_ACCENT, "") ?: "",
            narrow = p.getBoolean(K_NARROW, false),
            hideStress = p.getBoolean(K_STRESS, true),
        )
    }

    private fun update(block: (Settings) -> Settings) {
        val next = block(_state.value)
        _state.value = next
        prefs?.edit()
            ?.putBoolean(K_ENABLED, next.enabled)
            ?.putInt(K_DENSITY, next.density)
            ?.putStringSet(K_APPS, next.apps)
            ?.putBoolean(K_ALL, next.allApps)
            ?.putBoolean(K_TOUCH, next.touchWords)
            ?.putString(K_TARGET, next.target)
            ?.putString(K_LAYER, next.layer)
            ?.putString(K_HOST, next.packHost)
            ?.putBoolean(K_LENS, next.lens)
            ?.putString(K_ACCENT, next.accent)
            ?.putBoolean(K_NARROW, next.narrow)
            ?.putBoolean(K_STRESS, next.hideStress)
            ?.apply()
    }

    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }
    fun setDensity(v: Int) = update { it.copy(density = v.coerceIn(Frequency.DMIN, Frequency.DMAX)) }
    fun setAllApps(v: Boolean) = update { it.copy(allApps = v) }
    fun setTouchWords(v: Boolean) = update { it.copy(touchWords = v) }
    fun setTarget(v: String) = update { it.copy(target = v) }
    fun setLayer(v: String) = update { it.copy(layer = v) }
    fun setPackHost(v: String) = update { it.copy(packHost = v.trim()) }
    fun setLens(v: Boolean) = update { it.copy(lens = v) }
    fun setAccent(v: String) = update { it.copy(accent = v) }
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
