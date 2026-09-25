package io.github.nanbiz.phonetix.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager

/**
 * The apps a transcription has no business being on.
 *
 * The home screen is a grid of names, the status bar and the notification shade are
 * controls, a keyboard is the thing being typed on, and this app's own screen is the switches
 * that work it: none of them is text anyone reads for its pronunciation, and covering their
 * words costs a read of the tree every time they move. The reader turning the overlay on for
 * "every app" means every app they read in, and these are not that.
 *
 * Which packages those are differs by device, so they are looked up rather than listed: the
 * launcher is whatever answers the home intent, the keyboards are whatever input methods are
 * enabled. Looked up rarely, because they change about never.
 */
class Bystanders(private val context: Context) {

    @Volatile private var packages: Set<String> = emptySet()

    /** The keyboards among them, which cover the app being read rather than replacing it. */
    @Volatile private var typing: Set<String> = emptySet()
    @Volatile private var lookedUpAt = 0L

    /** Whether this is a keyboard: the app behind one is still the app the reader is reading. */
    fun isKeyboard(pkg: String?): Boolean {
        if (pkg == null) return false
        val now = SystemClock.uptimeMillis()
        if (now - lookedUpAt > REFRESH_MS) refresh()
        return pkg in typing
    }

    fun contains(pkg: String?): Boolean {
        if (pkg == null) return false
        // Except the surface the checks drive, which is ours and is there to be annotated.
        if (pkg == context.packageName && OwnScreen.readable) return false
        val now = SystemClock.uptimeMillis()
        if (now - lookedUpAt > REFRESH_MS) refresh()
        return pkg in packages
    }

    private fun refresh() {
        lookedUpAt = SystemClock.uptimeMillis()
        val found = HashSet<String>(8)
        found.add("com.android.systemui")
        // This app itself. Its screen is controls, and transcribing them covers the words a
        // reader works it by: "I read into" came back as "I read ɪntʊ" with the choices under
        // it half in IPA. The one window of ours that is meant to be read says so itself.
        found.add(context.packageName)
        // Whatever answers the home intent, unless what answers it is not a launcher.
        //
        // A device with no launcher installed - an emulator, a freshly flashed phone - answers
        // it with the settings app's own FallbackHome, and a device with several answers it
        // with the chooser. Adding either meant an entire ordinary app was ignored: on the
        // emulator the settings app produced no transcriptions at all, and nothing said why,
        // because a bystander's events are dropped before anything is logged about them.
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager
                .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
            val name = resolved?.name.orEmpty()
            if (resolved != null && NOT_A_LAUNCHER.none { name.endsWith(it) }) {
                resolved.packageName
            } else {
                null
            }
        }.getOrNull()?.let { found.add(it) }
        val keyboards = HashSet<String>(4)
        // The keyboards are kept apart rather than among the rest: a keyboard's package can be an
        // app as well - Google voice typing ships inside the Google app, which is where Gemini
        // runs - and counted by package, the whole of that app was never read.
        runCatching {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.forEach { keyboards.add(it.packageName) }
        }
        typing = keyboards
        packages = found
    }

    private companion object {
        const val REFRESH_MS = 60_000L

        /** Activities that answer the home intent without being anyone's home screen: the
         *  placeholder a device shows before a launcher is installed, and the chooser a
         *  device shows when it has several. */
        val NOT_A_LAUNCHER = listOf("FallbackHome", "ResolverActivity")
    }
}
