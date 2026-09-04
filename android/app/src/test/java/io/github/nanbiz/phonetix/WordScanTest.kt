package io.github.nanbiz.phonetix

import io.github.nanbiz.phonetix.core.Transcriber
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hand-rolled scanner must match the regex it replaced, exactly.
 *
 * It was swapped in for speed - a regex allocates a match object per word, on every piece
 * of text on screen, on every pass - and speed is no reason to change which characters
 * count as a word. This holds the loop to `[\p{L}][\p{L}'’]*` over text chosen to hit the
 * places the two could disagree: apostrophes at both ends, digits against letters, accents,
 * non-Latin scripts, and characters outside the basic plane.
 */
class WordScanTest {

    private val regex = Regex("[\\p{L}][\\p{L}'’]*")

    private fun scanned(text: String): List<String> {
        val out = ArrayList<String>()
        Transcriber.scanWords(text) { i, j -> out.add(text.substring(i, j)) }
        return out
    }

    private fun matched(text: String): List<String> = regex.findAll(text).map { it.value }.toList()

    private fun assertSame(text: String) =
        assertEquals("scanning ${'"'}$text${'"'}", matched(text), scanned(text))

    @Test
    fun `matches the regex it replaced`() {
        listOf(
            "",
            "   ",
            "one",
            "a",
            "Reading a paragraph teaches pronunciation quietly.",
            "don't stop; it's 'quoted' and ‘curly’ too",
            "trailing' apostrophe and leading 'one",
            "2024 was a year, 3D and x86_64 and h2o",
            "für größer naïve café",
            "πρωί ελληνικά, Привет мир, 日本語のテキスト",
            "hyphen-separated words and em—dashes",
            "e.g. i.e. U.S.A.",
            "emoji 😀 between 🎉 words",
            "𝕬 mathematical letter and a surrogate pair",
            "tabs\tand\nnewlines\r\nbetween",
            "'''",
            "’’’",
            "MiXeD CaSe and ALLCAPS",
        ).forEach(::assertSame)
    }

    @Test
    fun `spans line up with the text they name`() {
        val text = "don't read 2 paragraphs, read ‘pronunciation’ instead"
        val spans = ArrayList<Pair<Int, Int>>()
        Transcriber.scanWords(text) { i, j -> spans.add(i to j) }
        for ((i, j) in spans) {
            // Every span must start on a letter and stop before a non-word character.
            assert(Character.isLetter(text[i])) { "span at $i does not start on a letter" }
            assert(j > i && j <= text.length) { "span $i..$j is out of range" }
        }
        assertEquals(matched(text), spans.map { text.substring(it.first, it.second) })
    }
}
