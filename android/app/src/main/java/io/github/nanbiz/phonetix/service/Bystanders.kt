package io.github.nanbiz.phonetix.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager

/**
 * The apps a transcription has no business being on.
 *
 * The home screen is a grid of names, the status bar and the notification shade are
 * controls, and a keyboard is the thing being typed on: none of them is text anyone reads for
 * its pronunciation, and covering their words costs a read of the tree every time they move.
 * The reader turning the overlay on for "every app" means every app they read in, and these
 * are not that.
 *
 * Which packages those are differs by device, so they are looked up rather than listed: the
 * launcher is whatever answers the home intent, the keyboards are whatever input methods are
 * enabled. Looked up rarely, because they change about never.
 */
class Bystanders(private val context: Context) {

    @Volatile private var packages: Set<String> = emptySet()
    @Volatile private var lookedUpAt = 0L

    fun contains(pkg: String?): Boolean {
        if (pkg == null) return false
        val now = SystemClock.uptimeMillis()
        if (now - lookedUpAt > REFRESH_MS) refresh()
        return pkg in packages
    }

    private fun refresh() {
        lookedUpAt = SystemClock.uptimeMillis()
        val found = HashSet<String>(8)
        found.add("com.android.systemui")
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        }.getOrNull()?.let { found.add(it) }
        runCatching {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.forEach { found.add(it.packageName) }
        }
        packages = found
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}
