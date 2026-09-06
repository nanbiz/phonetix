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
            ?.apply()
    }

    fun setEnabled(v: Boolean) = update { it.copy(enabled = v) }
    fun setDensity(v: Int) = update { it.copy(density = v.coerceIn(Frequency.DMIN, Frequency.DMAX)) }
    fun setAllApps(v: Boolean) = update { it.copy(allApps = v) }
    fun setTouchWords(v: Boolean) = update { it.copy(touchWords = v) }
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
