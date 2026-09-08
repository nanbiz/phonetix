package io.github.nanbiz.phonetix

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nanbiz.phonetix.core.Lex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone against the core it is compiled from.
 *
 * The same three words the crate's own tests use, and the browser's copy is asked them too, so
 * that one implementation is demonstrably one implementation rather than three that happen to
 * agree today. These are real glosses, taken from kaikki on 2026-09-08.
 */
@RunWith(AndroidJUnit4::class)
class LexParityTest {

    @Test
    fun theLibraryLoads() {
        assertTrue("the native core is missing from this build", Lex.ready)
    }

    @Test
    fun theRightWordSharesMoreThanTheWrongOne() {
        val perro = "dog (the species Canis familiaris)"
        assertTrue(Lex.overlap(perro, "dog, hound") > Lex.overlap(perro, "male dog"))

        val silla = "chair"
        assertTrue(
            Lex.overlap(silla, "a chair (to sit on)") >
                Lex.overlap(silla, "armchair, easy chair (comfortable chair with arms)"),
        )

        // Both candidates offer "way", so one shared term decides nothing and the count is
        // what tells a road from a manner.
        val camino = "way, route"
        assertTrue(
            Lex.overlap(camino, "route, way (to get from one place to another)") >
                Lex.overlap(camino, "way, manner"),
        )
    }

    @Test
    fun picksTheBestAndRefusesATie() {
        val candidates = arrayOf("way, manner", "route, way (to get from one place to another)")
        assertEquals(1, Lex.bestOf("way, route", candidates, 1))
        assertEquals(-1, Lex.bestOf("chair", arrayOf("chair", "a chair (to sit on)"), 1))
        assertEquals(-1, Lex.bestOf("aardvark", arrayOf("chair", "way, manner"), 1))
    }
}
