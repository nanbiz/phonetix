package io.github.nanbiz.phonetix.service

import kotlin.math.hypot

/**
 * Which word a point means, when the point is not on one.
 *
 * The circle the reader drags is wider than a word and sits between two of them as often as on
 * one, so what it is over has to be decided rather than read off. Kept here as plain numbers
 * rather than inside the overlay, because it is the rule a card about the wrong word comes
 * from and it should be answerable without a phone.
 */
object Pointing {

    /** A word's rectangle on screen, in pixels. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    /**
     * The index of the word a point means, or -1 where it means none of them.
     *
     * A point inside a box means that word. Otherwise the nearest, measured from the edges of
     * a box rather than from its middle - a long word's middle is a long way from a point
     * directly over its end, and measuring from middles let a short word on the line above
     * win against the word actually being pointed at.
     *
     * Reach: half a line above or below, and to either side the wider of a line's height and
     * the word's own width. The vertical reach used to be a whole line, which is how a point
     * between two lines took the wrong one; the sideways reach used to be a line's height
     * alone, which on a grid of short labels - a home screen, a row of shortcuts - is thirty
     * pixels, so a circle passing beside the labels found none of them.
     */
    fun nearest(boxes: List<Box>, x: Float, y: Float): Int {
        var best = -1
        var bestFar = Float.MAX_VALUE
        for ((at, box) in boxes.withIndex()) {
            if (x >= box.left && x <= box.right && y >= box.top && y <= box.bottom) return at
            val across = gap(x, box.left, box.right)
            val down = gap(y, box.top, box.bottom)
            val reach = maxOf(box.height, box.right - box.left)
            if (down > box.height / 2f || across > reach) continue
            val far = hypot(across, down)
            if (far < bestFar) {
                bestFar = far
                best = at
            }
        }
        return best
    }

    /** How far a point lies outside a span, and nothing when it is inside it. */
    private fun gap(at: Float, from: Float, to: Float): Float =
        when {
            at < from -> from - at
            at > to -> at - to
            else -> 0f
        }
}
