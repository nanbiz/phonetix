package io.github.nanbiz.phonetix

import io.github.nanbiz.phonetix.core.IpaSymbols
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The tooltip must name a transcription's symbols exactly as the extension's does.
 *
 * Both the table and the rules for splitting a transcription and re-wording a marked symbol
 * live in `src/lib/ipa-symbols.ts`. The port reads the generated table and carries the two
 * small rules; this checks it against real transcriptions from the shipped dictionary and
 * against the composed forms - a tie bar, a precomposed vowel, a devoicing ring - where a
 * port is most likely to drift.
 */
class IpaSymbolsParityTest {

    private lateinit var root: JSONObject

    @Before
    fun load() {
        val path = System.getProperty("phonetix.symbols")
            ?: error("phonetix.symbols is not set; the Gradle test task provides it")
        val file = File(path)
        assertTrue("missing $path - regenerate with scripts/gen-ipa-symbols.ts", file.exists())
        root = JSONObject(file.readText())
        IpaSymbols.load(root)
    }

    @Test
    fun `splits transcriptions into the same symbols`() {
        val cases = root.getJSONArray("cases")
        assertTrue("no cases", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val ipa = c.getString("ipa")
            val expected = c.getJSONArray("tokens").let { a -> List(a.length()) { a.getString(it) } }
            assertEquals("tokenize(\"$ipa\")", expected, IpaSymbols.tokenize(ipa))
        }
    }

    @Test
    fun `names each symbol the same way`() {
        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val ipa = c.getString("ipa")
            val tokens = c.getJSONArray("tokens")
            val names = c.getJSONArray("names")
            for (j in 0 until tokens.length()) {
                val token = tokens.getString(j)
                val expected = if (names.isNull(j)) null else names.getString(j)
                assertEquals("describe(\"$token\") in \"$ipa\"", expected, IpaSymbols.describe(token)?.name)
            }
        }
    }
}
