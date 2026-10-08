package io.github.nanbiz.phonetix.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which word the circle means, put as the geometry a reader actually produces: a message of
 * wrapped lines, and a circle that lands between two of them as often as on one.
 *
 * The case this exists for is a card about a word the reader was not pointing at, which is
 * what "the lens does not work" looks like from the outside.
 */
class PointingTest {

    /** Two lines of a message bubble, as a chat lays them out: 63px tall, 72px apart. */
    private val line1 = listOf(
        Pointing.Box(60f, 500f, 190f, 563f),    // 0 "What"
        Pointing.Box(200f, 500f, 260f, 563f),   // 1 "the"
        Pointing.Box(270f, 500f, 700f, 563f),   // 2 "words you read" - a long one
    )
    private val line2 = listOf(
        Pointing.Box(60f, 572f, 200f, 635f),    // 3 "mean"
        Pointing.Box(210f, 572f, 280f, 635f),   // 4 "and"
        Pointing.Box(290f, 572f, 380f, 635f),   // 5 "how"
    )
    private val bubble = line1 + line2

    @Test
    fun a_point_on_a_word_is_that_word() {
        assertEquals(4, Pointing.nearest(bubble, 240f, 600f))
    }

    @Test
    fun a_point_under_a_long_word_is_that_word_and_not_a_short_one_below() {
        // Two pixels under "words you read" and directly beneath its middle, seven pixels
        // above the line below. Measured from the middles of boxes, the short word on the
        // line below won - which is a card about a word the reader is not pointing at, and
        // what "the lens does not work" looks like from the outside.
        assertEquals(2, Pointing.nearest(bubble, 270f, 565f))
    }

    @Test
    fun a_point_over_a_word_is_that_word_and_not_its_neighbour() {
        // Above "What", inside its width. The neighbour's middle used to be nearer.
        assertEquals(0, Pointing.nearest(bubble, 180f, 469f))
    }

    @Test
    fun a_point_under_a_word_is_that_word_and_not_its_neighbour() {
        // Two pixels under "mean", inside its width, with "and" beside it.
        assertEquals(3, Pointing.nearest(bubble, 190f, 637f))
    }

    @Test
    fun a_point_just_under_a_line_stays_on_that_line() {
        // Ten pixels below "and": still that word, not the line below.
        assertEquals(4, Pointing.nearest(bubble, 240f, 645f))
    }

    @Test
    fun a_point_between_two_lines_takes_the_nearer_line() {
        // In the gap, four pixels under line one and five above line two: line one.
        assertEquals(1, Pointing.nearest(bubble, 230f, 567f))
        // And the other way about.
        assertEquals(4, Pointing.nearest(bubble, 230f, 570f))
    }

    @Test
    fun a_point_a_line_away_is_no_word_at_all() {
        // A whole line below the last line: the reader is pointing at nothing, and a card
        // about the nearest word would be about a word they are not looking at.
        assertEquals(-1, Pointing.nearest(bubble, 240f, 720f))
    }

    /** A row of shortcuts on a home screen: short labels with wide gaps between them. */
    private val shortcuts = listOf(
        Pointing.Box(60f, 500f, 190f, 535f),    // 0 "Google"
        Pointing.Box(260f, 500f, 430f, 535f),   // 1 "Wikipedia"
        Pointing.Box(500f, 500f, 640f, 535f),   // 2 "YouTube"
    )

    @Test
    fun a_point_beside_a_short_label_still_takes_it() {
        // Sixty pixels to the right of "YouTube", which is less than the label's own width.
        // Reaching only a line's height - thirty-five pixels here - the circle passing beside
        // a grid of shortcuts found none of them, which is what a reader sees as the mark
        // doing nothing on a home screen.
        assertEquals(2, Pointing.nearest(shortcuts, 700f, 517f))
    }

    @Test
    fun a_point_between_two_labels_takes_the_nearer() {
        assertEquals(0, Pointing.nearest(shortcuts, 210f, 517f))
        assertEquals(1, Pointing.nearest(shortcuts, 245f, 517f))
    }

    @Test
    fun a_point_far_to_the_side_is_no_word_at_all() {
        assertEquals(-1, Pointing.nearest(bubble, 900f, 600f))
    }

    @Test
    fun a_circle_on_a_word_stays_on_it_across_the_gap_to_the_next() {
        // Three pixels past "What" and seven short of "the", with the circle already on
        // "the": nearest alone says "What", and a leash swinging over that gap would carry
        // the circle and the card back and forth between the two.
        assertEquals(0, Pointing.nearest(bubble, 193f, 530f))
        assertEquals(1, Pointing.settle(bubble, 1, 193f, 530f))
    }

    @Test
    fun a_circle_on_a_word_stays_on_its_line_between_two_lines() {
        // Five pixels under "the" and four above "and": the nearer line is the second, but
        // the circle is on the first and the point has not reached the second yet.
        assertEquals(4, Pointing.nearest(bubble, 230f, 568f))
        assertEquals(1, Pointing.settle(bubble, 1, 230f, 568f))
    }

    @Test
    fun a_circle_leaves_its_word_for_one_the_point_is_inside() {
        assertEquals(4, Pointing.settle(bubble, 1, 230f, 580f))
    }

    @Test
    fun a_circle_leaves_its_word_once_the_point_is_plainly_past_it() {
        // Fifty-five pixels past "Wikipedia", more than half a line, and nearer "YouTube":
        // the circle moves on.
        assertEquals(2, Pointing.settle(shortcuts, 1, 485f, 517f))
        // And a line below the last line is no word, whatever the circle was on.
        assertEquals(-1, Pointing.settle(bubble, 4, 240f, 720f))
    }

    @Test
    fun nothing_on_screen_is_nothing_pointed_at() {
        assertEquals(-1, Pointing.nearest(emptyList(), 100f, 100f))
    }
}
