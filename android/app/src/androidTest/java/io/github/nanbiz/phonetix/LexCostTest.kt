package io.github.nanbiz.phonetix

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nanbiz.phonetix.core.Lex
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What crossing into the core costs on a phone.
 *
 * The overlay reads the accessibility tree many times a second, so the question is not whether
 * the boundary is fast but whether it can be crossed where it is crossed: once per batch of
 * text, never per word inside the following loop.
 */
@RunWith(AndroidJUnit4::class)
class LexCostTest {

    private val source = "way, route"
    private val candidates = arrayOf(
        "way, manner",
        "route, way (to get from one place to another)",
        "a chair (to sit on)",
        "dog, hound",
    )

    @Test
    fun whatOneCrossingCosts() {
        repeat(20_000) { Lex.overlap(source, candidates[1]) }

        val n = 200_000
        var t = System.nanoTime()
        repeat(n) { Lex.overlap(source, candidates[it and 3]) }
        val overlapNs = (System.nanoTime() - t) / n.toDouble()

        val m = 50_000
        t = System.nanoTime()
        repeat(m) { Lex.bestOf(source, candidates, 1) }
        val bestNs = (System.nanoTime() - t) / m.toDouble()

        Log.i("LexCost", "overlap ${overlapNs.toInt()} ns, bestOf ${bestNs.toInt()} ns, " +
            "a 40 word screen ${"%.2f".format(bestNs * 40 / 1e6)} ms")
    }
}
