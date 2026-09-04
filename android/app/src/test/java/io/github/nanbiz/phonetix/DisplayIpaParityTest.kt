package io.github.nanbiz.phonetix

import io.github.nanbiz.phonetix.core.DisplayIpa
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A word must read the same over a page as it does over an app.
 *
 * `src/lib/display-ipa.ts` decides what a transcription looks like in running text - which
 * marks are dropped, and that a syllable break and the brackets around an optional length
 * are noise mid-sentence. This holds the port to the same answers for the same input.
 */
class DisplayIpaParityTest {

    @Test
    fun `strips a transcription the same way as the extension`() {
        val path = System.getProperty("phonetix.symbols")
            ?: error("phonetix.symbols is not set; the Gradle test task provides it")
        val file = File(path)
        assertTrue("missing $path", file.exists())
        val cases = JSONObject(file.readText()).getJSONArray("display")
        assertTrue("no cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val ipa = c.getString("ipa")
            assertEquals("broad \"$ipa\"", c.getString("broad"), DisplayIpa.display(ipa))
            assertEquals(
                "narrow \"$ipa\"",
                c.getString("narrow"),
                DisplayIpa.display(ipa, narrow = true),
            )
            assertEquals(
                "with stress \"$ipa\"",
                c.getString("withStress"),
                DisplayIpa.display(ipa, hideStress = false),
            )
        }
    }
}
