package io.github.nanbiz.phonetix.service

import android.os.Build
import android.view.WindowManager

/**
 * Lay a window out over the whole screen, the status bar and a display cutout included.
 *
 * Words are placed where they are on the screen, and a window the platform fits inside the
 * system bars starts below the status bar: a full-screen layer drew everything above its top
 * edge off the window, so the first line of a page that starts close under the status bar
 * showed only the bottom of its transcriptions with the page's own words above them.
 */
fun overScreen(params: WindowManager.LayoutParams) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        params.layoutInDisplayCutoutMode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        params.fitInsetsTypes = 0
        params.isFitInsetsIgnoringVisibility = true
    }
}
