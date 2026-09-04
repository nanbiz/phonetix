package io.github.nanbiz.phonetix

import io.github.nanbiz.phonetix.core.Frequency
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Android port must answer exactly what the extension answers.
 *
 * Which words get transcribed, and how the frequency bar maps onto that, is defined once in
 * `src/lib/sprinkle.ts`. Kotlin cannot import TypeScript, so the extension writes down what
 * that module answers for a spread of inputs (`scripts/gen-sprinkle-vectors.ts` ->
 * `shared/sprinkle-vectors.json`) and this asserts the port against every case. Change the
 * rule on one side only and this fails, instead of the same setting quietly meaning two
 * different things on a phone and in a browser.
 */
class SprinkleParityTest {

    private fun vectors(): JSONObject {
        val path = System.getProperty("phonetix.vectors")
            ?: error("phonetix.vectors is not set; the Gradle test task provides it")
        val file = File(path)
        assertTrue("missing $path - regenerate it with scripts/gen-sprinkle-vectors.ts", file.exists())
        return JSONObject(file.readText())
    }

    @Test
    fun `the same words are chosen as in the extension`() {
        val cases = vectors().getJSONArray("picks")
        assertTrue("no cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val word = c.getString("word")
            val occurrence = c.getInt("occurrence")
            val density = c.getInt("density")
            assertEquals(
                "picks(\"$word\", occurrence=$occurrence, density=$density)",
                c.getBoolean("picked"),
                Frequency.picks(word, occurrence, density),
            )
        }
    }

    @Test
    fun `the frequency bar maps to the same densities`() {
        val v = vectors()
        assertEquals(v.getInt("densityMin"), Frequency.DMIN)
        assertEquals(v.getInt("densityMax"), Frequency.DMAX)

        val curve = v.getJSONArray("curve")
        for (i in 0 until curve.length()) {
            val c = curve.getJSONObject(i)
            val pos = c.getDouble("pos").toFloat()
            assertEquals(
                "densityForPos($pos)",
                c.getInt("density"),
                Frequency.densityForPos(pos),
            )
        }
    }

    @Test
    fun `a stored density puts the thumb in the same place`() {
        val inverse = vectors().getJSONArray("inverse")
        for (i in 0 until inverse.length()) {
            val c = inverse.getJSONObject(i)
            val density = c.getInt("density")
            assertEquals(
                "posForDensity($density)",
                c.getDouble("pos").toFloat(),
                Frequency.posForDensity(density),
                1e-6f,
            )
        }
    }
}
