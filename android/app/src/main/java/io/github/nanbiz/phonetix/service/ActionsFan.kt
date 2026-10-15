package io.github.nanbiz.phonetix.service

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the two actions offered round the finger go, as geometry alone so it can be checked
 * at every place a finger can be.
 *
 * They sit across the leash, one either side of the finger and square to the line the circle
 * is carried along, so neither covers the line the eye follows to the word nor the way the
 * hand came. Where a wall or the card is in the way they first bend back from the circle
 * together, then the pair turns away from what is in the way, and only where nothing fits at
 * all are they pulled in from the walls: an action off the screen cannot be reached.
 */
object ActionsFan {

    data class Spot(val x: Float, val y: Float)

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** How far the pair's axis is turned from the leash, nearest first. */
    private val TURNS = listOf(0f, 20f, -20f, 40f, -40f, 60f, -60f, 90f, -90f, 120f, -120f,
        150f, -150f, 180f)

    /** How far each action sits off that axis: square to it, bent back from the circle, and
     *  closer together where a corner leaves less room. */
    private val SPREADS = listOf(90f, 115f, 140f, 60f, 45f, 35f)

    /** The way the circle leans from the finger, in degrees, up where it has not swung away. */
    fun leash(finger: Spot, ball: Spot): Double =
        if (ball == finger) -90.0
        else Math.toDegrees(atan2((ball.y - finger.y).toDouble(), (ball.x - finger.x).toDouble()))

    /**
     * The two spots, play first, round a finger at [finger] whose circle is at [ball], each
     * [reach] from the finger and [half] wide either side, inside [screen] by [margin] and
     * clear of [card] where it can be.
     */
    fun around(
        finger: Spot,
        ball: Spot,
        reach: Float,
        half: Float,
        margin: Float,
        screen: Box,
        card: Box?,
    ): List<Spot> {
        val leash = leash(finger, ball)
        fun at(degrees: Float): Spot {
            val radians = Math.toRadians(leash + degrees)
            return Spot(finger.x + cos(radians).toFloat() * reach, finger.y + sin(radians).toFloat() * reach)
        }
        fun fits(p: Spot) = p.x - half >= screen.left + margin && p.x + half <= screen.right - margin &&
            p.y - half >= screen.top + margin && p.y + half <= screen.bottom - margin
        fun under(p: Spot): Float {
            val c = card ?: return 0f
            val w = minOf(p.x + half, c.right) - maxOf(p.x - half, c.left)
            val h = minOf(p.y + half, c.bottom) - maxOf(p.y - half, c.top)
            return if (w > 0f && h > 0f) w * h else 0f
        }
        val pairs = TURNS.flatMap { turn -> SPREADS.map { spread -> listOf(at(turn - spread), at(turn + spread)) } }
        // On the screen first and clear of the card next: one the card covers can still be seen
        // round its edge, one past a wall cannot.
        val clear = pairs.firstOrNull { pair -> pair.all { fits(it) && under(it) == 0f } }
        if (clear != null) return clear
        val onScreen = pairs.filter { pair -> pair.all(::fits) }
        if (onScreen.isNotEmpty()) return onScreen.minBy { pair -> pair.sumOf { under(it).toDouble() } }
        // Nowhere a pair fits whole: the one turned furthest from the walls, pulled in from them.
        fun past(p: Spot) = maxOf(0f, screen.left + margin + half - p.x) +
            maxOf(0f, p.x + half - (screen.right - margin)) +
            maxOf(0f, screen.top + margin + half - p.y) +
            maxOf(0f, p.y + half - (screen.bottom - margin))
        return pairs.minBy { pair -> pair.sumOf { past(it).toDouble() } }.map { p ->
            Spot(
                p.x.coerceIn(screen.left + margin + half, screen.right - margin - half),
                p.y.coerceIn(screen.top + margin + half, screen.bottom - margin - half),
            )
        }
    }
}
