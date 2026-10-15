package io.github.nanbiz.phonetix.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The actions round the finger, at every place a finger can be on a phone and with the circle
 * leaning every way: on the screen whole, apart from each other and from the finger, and square
 * to the leash wherever there is room for that.
 */
class ActionsFanTest {

    /** A 1080x2400 phone at 2.75 pixels a dp: 76dp out, 52dp wide, 8dp from a wall. */
    private val screen = ActionsFan.Box(0f, 0f, 1080f, 2400f)
    private val reach = 76f * 2.75f
    private val half = 52f * 2.75f / 2f
    private val margin = 8f * 2.75f

    private fun turnOff(spot: ActionsFan.Spot, finger: ActionsFan.Spot, leash: Double): Double {
        val at = Math.toDegrees(atan2((spot.y - finger.y).toDouble(), (spot.x - finger.x).toDouble()))
        return ((at - leash + 540.0) % 360.0) - 180.0
    }

    @Test
    fun the_actions_stay_on_the_screen_and_apart_wherever_the_finger_is() {
        // A word can be anywhere a finger can reach, the edges included.
        for (x in 0..1080 step 30) for (y in 0..2400 step 40) for (degrees in 0 until 360 step 15) {
            val finger = ActionsFan.Spot(x.toFloat(), y.toFloat())
            val radians = Math.toRadians(degrees.toDouble())
            val ball = ActionsFan.Spot(
                (x + cos(radians) * 150).toFloat(), (y + sin(radians) * 150).toFloat(),
            )
            val (play, article) = ActionsFan.around(finger, ball, reach, half, margin, screen, null)
            for (spot in listOf(play, article)) {
                assertTrue("off the screen at $spot for $finger", spot.x - half >= margin - 0.5f)
                assertTrue("off the screen at $spot for $finger", spot.x + half <= 1080f - margin + 0.5f)
                assertTrue("off the screen at $spot for $finger", spot.y - half >= margin - 0.5f)
                assertTrue("off the screen at $spot for $finger", spot.y + half <= 2400f - margin + 0.5f)
            }
            val apart = hypot(play.x - article.x, play.y - article.y)
            assertTrue("on top of each other at $play $article for $finger", apart >= half * 2)
            for (spot in listOf(play, article)) {
                assertTrue("under the finger at $spot for $finger", hypot(spot.x - x, spot.y - y) >= half * 1.5f)
            }
            if (abs(hypot(play.x - x, play.y - y) - reach) > 1f ||
                abs(hypot(article.x - x, article.y - y) - reach) > 1f) {
                // Only in a corner, where a pair at full reach has no room between two walls.
                val room = reach + half + margin
                val byASide = x < room || x > 1080 - room
                val byAnEnd = y < room || y > 2400 - room
                assertTrue("pulled in from a wall at $finger, leaning $degrees", byASide && byAnEnd)
            }
        }
    }

    @Test
    fun with_room_they_sit_square_to_the_leash() {
        val finger = ActionsFan.Spot(540f, 1200f)
        for (degrees in 0 until 360 step 15) {
            val radians = Math.toRadians(degrees.toDouble())
            val ball = ActionsFan.Spot(
                (540 + cos(radians) * 150).toFloat(), (1200 + sin(radians) * 150).toFloat(),
            )
            val leash = ActionsFan.leash(finger, ball)
            val (play, article) = ActionsFan.around(finger, ball, reach, half, margin, screen, null)
            assertEquals(-90.0, turnOff(play, finger, leash), 1.0)
            assertEquals(90.0, turnOff(article, finger, leash), 1.0)
        }
    }

    @Test
    fun by_a_wall_they_turn_away_from_it_rather_than_through_it() {
        // Near the top, the circle carried up and to the left: square to the leash would put
        // one past the top.
        val finger = ActionsFan.Spot(540f, 150f)
        val ball = ActionsFan.Spot(440f, 50f)
        val (play, article) = ActionsFan.around(finger, ball, reach, half, margin, screen, null)
        assertTrue(play.y - half >= margin && article.y - half >= margin)
        assertEquals(reach, hypot(play.x - finger.x, play.y - finger.y), 1f)
        assertEquals(reach, hypot(article.x - finger.x, article.y - finger.y), 1f)
    }

    @Test
    fun they_keep_clear_of_the_card_where_they_can() {
        val finger = ActionsFan.Spot(540f, 1200f)
        val ball = ActionsFan.Spot(540f, 1050f)
        // The card to the left of the finger, where square to an upward leash puts play.
        val card = ActionsFan.Box(0f, 900f, 450f, 1500f)
        val spots = ActionsFan.around(finger, ball, reach, half, margin, screen, card)
        for (spot in spots) {
            assertTrue("under the card at $spot", spot.x - half >= 450f || spot.y + half <= 900f || spot.y - half >= 1500f)
        }
    }
}
