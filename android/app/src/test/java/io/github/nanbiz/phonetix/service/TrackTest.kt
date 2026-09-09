package io.github.nanbiz.phonetix.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the fit has to get right is the shape of a real scroll: a finger that accelerates, a
 * lift, and a fling that decelerates to nothing. The samples it gets are sparse, because a
 * view reports its scrolling about ten times a second however often it moves, so the answers
 * that matter are the ones between the samples and just past the last one.
 */
class TrackTest {

    /** A fling as Android runs one: a starting speed decaying towards nothing. */
    private fun fling(from: Long, speed: Float, decay: Float, at: Long): Float {
        val t = (at - from).toFloat()
        return speed * (1f - kotlin.math.exp(-decay * t)) / decay
    }

    @Test
    fun a_steady_movement_is_carried_at_its_own_speed() {
        val track = Track()
        for (step in 0..9) {
            val at = 1000L + step * 100L
            track.add(at, step * 200f)
        }
        // Two hundred pixels every hundred milliseconds, so fifty milliseconds past the last
        // sample the page is a hundred pixels further on than it was.
        assertEquals(1900f, track.at(1950L), 12f)
        assertEquals(2.0f, track.speed(1900L), 0.15f)
    }

    @Test
    fun a_movement_that_is_slowing_is_not_carried_at_the_speed_it_had() {
        // A fling decelerating hard, sampled every hundred milliseconds, asked about two
        // hundred past the last sample. That is the case a real page puts the layer in: a view
        // reports its scrolling about ten times a second whatever it is doing, so the news is
        // always old, and the speed it implies is always the speed of a moment that is over.
        val track = Track()
        for (step in 0..5) {
            val at = 1000L + step * 100L
            track.add(at, fling(1000L, 5f, 0.01f, at))
        }
        val when_ = track.latestAt + 200L
        val truth = fling(1000L, 5f, 0.01f, when_)
        val fitted = track.at(when_)
        val flat = track.latest + track.speed(track.latestAt) * 200f
        assertTrue(
            "the fit should be nearer the truth than carrying the last speed on: " +
                "fit ${fitted - truth}, flat ${flat - truth}",
            kotlin.math.abs(fitted - truth) < kotlin.math.abs(flat - truth),
        )
        assertEquals(truth, fitted, 40f)
    }

    @Test
    fun a_page_that_has_stopped_is_not_carried_backwards() {
        // The end of a fling: the samples flatten out. A parabola fitted to them turns round,
        // and the words would walk back up the page.
        val track = Track()
        for (step in 0..9) {
            val at = 1000L + step * 100L
            track.add(at, fling(1000L, 4f, 0.01f, at))
        }
        val settled = track.at(track.latestAt)
        for (ahead in listOf(50L, 120L, 240L)) {
            val later = track.at(track.latestAt + ahead)
            assertTrue(
                "carried backwards by ${settled - later} after ${ahead}ms",
                later >= settled - 1f,
            )
        }
    }

    @Test
    fun nothing_is_claimed_past_what_the_samples_can_speak_to() {
        val track = Track()
        track.add(1000L, 0f)
        track.add(1100L, 200f)
        track.add(1200L, 400f)
        // A page that has said nothing for half a second has done something the samples cannot
        // describe, so the words stay where they were last known to be.
        assertEquals(track.latest, track.at(1700L), 0.01f)
    }

    @Test
    fun one_sample_says_only_where_that_sample_was() {
        val track = Track()
        track.add(1000L, 500f)
        assertEquals(500f, track.at(1000L), 0.01f)
        assertEquals(500f, track.at(1100L), 0.01f)
        assertEquals(0f, track.speed(1100L), 0.01f)
    }

    @Test
    fun an_empty_track_is_a_page_that_has_not_moved() {
        val track = Track()
        assertEquals(0f, track.at(1234L), 0.01f)
        assertEquals(0f, track.speed(1234L), 0.01f)
    }

    @Test
    fun a_report_that_arrives_late_does_not_move_the_page_back() {
        val track = Track()
        track.add(1000L, 0f)
        track.add(1100L, 200f)
        track.add(1050L, 100f)
        assertEquals(200f, track.latest, 0.01f)
        assertEquals(1100L, track.latestAt)
    }

    @Test
    fun a_wild_sample_cannot_throw_the_words_across_the_screen() {
        val track = Track()
        track.add(1000L, 0f)
        track.add(1100L, 100f)
        track.add(1200L, 1_000_000f)
        // Whatever the samples say, the speed and the acceleration are held to what a page can
        // actually do, so one corrupt report costs a frame rather than the screen.
        assertTrue(kotlin.math.abs(track.speed(1200L)) <= 12.01f)
        // Twelve pixels a millisecond for a hundred milliseconds, and the acceleration bound
        // on top of it, is the most a hundred milliseconds of anything can be worth.
        assertTrue(track.at(1300L) - track.latest <= 12.01f * 100f + 0.5f * 0.2f * 100f * 100f)
    }

    @Test
    fun samples_are_counted_within_the_window_only() {
        val track = Track()
        track.add(1000L, 0f)
        track.add(1400L, 100f)
        track.add(1500L, 150f)
        assertEquals(3, track.samples(600L))
        assertEquals(2, track.samples(200L))
    }

    @Test
    fun the_ring_keeps_the_newest_samples_when_it_fills() {
        val track = Track()
        for (step in 0..199) {
            track.add(1000L + step * 10L, step * 10f)
        }
        assertEquals(1990f, track.latest, 0.01f)
        // Ten pixels every ten milliseconds is one a millisecond, and the fit sees only the
        // recent end whatever came before it.
        assertEquals(1.0f, track.speed(track.latestAt), 0.1f)
    }
}
