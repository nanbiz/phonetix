package io.github.nanbiz.phonetix.service

import android.graphics.Rect
import android.graphics.RectF

/**
 * What each line said about where its characters are, kept so it need not be asked twice.
 *
 * Asking an app for the character positions of a line makes it lay that text out again,
 * which is the single most expensive thing the overlay does - a fifth of a second for a
 * screenful - and the answer does not change when the page scrolls: the line moves, and the
 * characters keep their places inside it. So the answer is held relative to the line's own
 * top left corner and reused wherever the line turns up next.
 */
class LineLayouts {

    /** A line's character boxes as offsets inside it, and the size it had when measured. */
    class Layout(val width: Int, val height: Int, val rects: Array<RectF?>)

    private val kept = object : LinkedHashMap<String, Layout>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Layout>?) =
            size > CAPACITY
    }

    private fun key(text: String, from: Int, length: Int): String =
        text.length.toString() + ":" + text.hashCode() + ":" + from + ":" + length

    fun recall(text: String, from: Int, length: Int): Layout? = kept[key(text, from, length)]

    /**
     * Keep a line's character boxes, as offsets inside the line.
     *
     * Only a line that is whole on screen can be remembered: the rectangle of a line half
     * scrolled off is cut at the edge of its list, and offsets taken from it would put every
     * character in the wrong place.
     */
    fun remember(text: String, from: Int, length: Int, at: Rect, viewport: Rect, rects: Array<RectF?>) {
        if (at.isEmpty || at.top <= viewport.top + 1 || at.bottom >= viewport.bottom - 1) return
        val relative = Array<RectF?>(rects.size) { i ->
            rects[i]?.let {
                RectF(it.left - at.left, it.top - at.top, it.right - at.left, it.bottom - at.top)
            }
        }
        kept[key(text, from, length)] = Layout(at.width(), at.height(), relative)
    }

    /**
     * The remembered boxes, moved to where the line is now, or null when they cannot be
     * trusted for it.
     *
     * A line of a different width has been laid out again. And only a line reported whole is
     * placed: a line cut by the edge of its list reports the part of itself that shows, and
     * a corner taken from that is a few pixels out - enough to set a transcription over the
     * line above or below it. Those lines are asked directly instead, which costs one request
     * at each edge of the screen.
     */
    fun place(layout: Layout?, at: Rect, viewport: Rect): Array<RectF?>? {
        if (layout == null || at.isEmpty || at.width() != layout.width) return null
        if (at.top <= viewport.top + 1 || at.bottom >= viewport.bottom - 1) return null
        if (at.height() != layout.height) return null
        return Array(layout.rects.size) { i ->
            layout.rects[i]?.let {
                RectF(it.left + at.left, it.top + at.top, it.right + at.left, it.bottom + at.top)
            }
        }
    }

    private companion object {
        /** About two screens' worth of distinct lines either side of the one being read. */
        const val CAPACITY = 200
    }
}
