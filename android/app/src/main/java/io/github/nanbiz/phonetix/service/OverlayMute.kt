package io.github.nanbiz.phonetix.service

import android.view.View
import android.view.accessibility.AccessibilityEvent

/**
 * Keeps our own windows out of the accessibility stream.
 *
 * The service hears the events of every app, its own included, and everything it draws is a
 * window: a transcription appearing counted as the screen changing, which asked for another
 * reading, which drew again. The overlay was reading the screen over and over because it
 * could see itself, and never got as far as a full read - so the words kept the colours they
 * had never been given.
 */
object OverlayMute : View.AccessibilityDelegate() {

    override fun sendAccessibilityEvent(host: View, eventType: Int) {}

    override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {}

    /** Silence this view and everything under it. */
    fun apply(view: View) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.accessibilityDelegate = this
    }
}
