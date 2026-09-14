package io.github.nanbiz.phonetix

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.github.nanbiz.phonetix.core.Dictionary
import io.github.nanbiz.phonetix.core.SettingsStore
import io.github.nanbiz.phonetix.service.PhonetixAccessibilityService
import io.github.nanbiz.phonetix.ui.AppEntry
import io.github.nanbiz.phonetix.ui.AppsScreen
import io.github.nanbiz.phonetix.ui.SettingsWeb
import io.github.nanbiz.phonetix.ui.PhonetixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** Bumped on resume: both permissions are granted in Settings, outside this app, so
     *  the only honest moment to re-read them is when the user comes back. */
    private var resumeTick by mutableStateOf(0)
    private var dictReady by mutableStateOf(false)
    /** The screen something outside asked the app to open on, with the moment it was asked:
     *  the same screen can be asked for twice, and the second ask must be a change. */
    private var opening by mutableStateOf("")
    /** Whether the synthesiser is up, so the preview can draw the words no dictionary holds. */
    private var voiceReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SettingsStore.init(this)
        Dictionary.ensureLoaded(this) { runOnUiThread { dictReady = true } }
        dictReady = Dictionary.ready
        // The synthesiser, for this screen's own sake. It was started only by the
        // accessibility service, so with the overlay switched off the preview of what a
        // setting does had nothing to fill a word with and showed plain English - the one
        // place in the app that exists to show a transcription, showing none.
        Thread {
            if (io.github.nanbiz.phonetix.core.Speech.start(this)) {
                runOnUiThread { voiceReady = true }
            }
        }.start()

        asked(intent)
        setContent {
            PhonetixTheme {
                // The window itself, not only what is drawn in it. Edge to edge, the bands
                // behind the status bar and the gesture bar show the window's own background,
                // which is the system's idea of a light app: a dark palette came up with a
                // white strip at each end of it. The icons in those bands are told which way
                // round they are for the same reason.
                val ground = MaterialTheme.colorScheme.background
                val lightBars = ground.luminance() > 0.5f
                androidx.compose.runtime.LaunchedEffect(ground) {
                    window.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(ground.toArgb()),
                    )
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                        .apply {
                            isAppearanceLightStatusBars = lightBars
                            isAppearanceLightNavigationBars = lightBars
                        }
                }
                Surface(
                    Modifier.fillMaxSize(),
                    color = ground,
                ) {
                    Scaffold { inner ->
                        Root(
                            modifier = Modifier.padding(inner),
                            resumeTick = resumeTick,
                            dictReady = dictReady || voiceReady,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        asked(intent)
    }

    /** Which screen the app was opened on, where it was opened by something other than the
     *  reader tapping its icon. */
    private fun asked(intent: Intent?) {
        val wanted = intent?.getStringExtra("view").orEmpty()
        opening = if (wanted.isEmpty()) "" else "$wanted:${android.os.SystemClock.uptimeMillis()}"
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        dictReady = Dictionary.ready
    }

    /** Whether the reader has already been sent to a system screen this time the app was
     *  opened, so coming back from one does not send them straight out again. */
    private var alreadyAsked = false

    @Composable
    private fun Root(modifier: Modifier, resumeTick: Int, dictReady: Boolean) {
        val settings by SettingsStore.state.collectAsState()
        var showApps by remember { mutableStateOf(false) }
        var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

        // Re-read on every return from Settings; the key makes the read happen again.
        val accessibilityOn = remember(resumeTick) { accessibilityEnabled() }
        val overlayOn = remember(resumeTick) { AndroidSettings.canDrawOverlays(this) }

        // Asked for rather than described. Neither permission can be granted inside this app
        // - both are screens of the system's - and a reader who has just installed something
        // that cannot do anything until they visit two of them should be taken to the first
        // one, not handed a list of what they have not done. Once per opening: coming back
        // from a screen they decided against must not push them into it again.
        LaunchedEffect(accessibilityOn, overlayOn) {
            if (accessibilityOn && overlayOn) {
                alreadyAsked = false
                return@LaunchedEffect
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d(
                    "Phonetix",
                    "PERMISSIONS reading=$accessibilityOn overlay=$overlayOn",
                )
            }
            if (alreadyAsked) return@LaunchedEffect
            alreadyAsked = true
            if (!accessibilityOn) openAccessibilitySettings() else openOverlaySettings()
        }

        LaunchedEffect(showApps) {
            if (showApps && apps.isEmpty()) apps = withContext(Dispatchers.IO) { launcherApps() }
        }

        Box(modifier) {
            if (showApps) {
                AppsScreen(
                    settings = settings,
                    apps = apps,
                    onBack = { showApps = false },
                    onAllApps = SettingsStore::setAllApps,
                    onToggle = SettingsStore::toggleApp,
                )
            } else {
                // The product's own settings screen, which is the extension's: one view, built
                // from src/ui/settings and drawn here. What it cannot do for itself - the two
                // permissions, the dictionaries, the app list - is behind the bridge.
                SettingsWeb(
                    permissions = { accessibilityOn to overlayOn },
                    dictionaryReady = dictReady,
                    open = opening,
                    onOpenReading = { openAccessibilitySettings() },
                    onOpenOverlay = { openOverlaySettings() },
                    onOpenApps = { showApps = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /**
     * Whether our service is among the ones the user switched on.
     *
     * Asked of the system's own list rather than read out of the setting it writes: a device
     * can name a service there in a form that does not unflatten to what we compared against,
     * and the app then told a reader to go and allow something their phone's own screen said
     * was already on. Our own bound service settles it where there is one, since a service
     * that is running is a service that was allowed.
     */
    private fun accessibilityEnabled(): Boolean {
        if (PhonetixAccessibilityService.running != null) return true
        val manager = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val listed = runCatching {
            manager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
            )
        }.getOrNull()
        if (listed != null && listed.any { it.resolveInfo?.serviceInfo?.packageName == packageName }) {
            return true
        }
        val want = ComponentName(this, PhonetixAccessibilityService::class.java)
        val enabled = AndroidSettings.Secure.getString(
            contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any {
            val c = ComponentName.unflattenFromString(it)
            c != null && c.packageName == want.packageName && c.className == want.className
        }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    /** Everything with a launcher entry, which is what a reader thinks of as "an app". */
    private fun launcherApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                AppEntry(pkg, ri.loadLabel(pm)?.toString() ?: pkg)
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}
