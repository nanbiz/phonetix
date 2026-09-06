package io.github.nanbiz.phonetix

import io.github.nanbiz.phonetix.core.Eld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The Kotlin detector against the JavaScript one it was ported from.
 *
 * The point of porting eld rather than inventing something was that the app and the extension
 * should agree about what language a line is in. That is only true if the port answers the
 * same questions the same way, and the only way to know is to ask both: the expected answers
 * here were produced by running eld itself over these texts
 * (`node scripts/build-eld-model.mjs` writes the model, and the same script's sibling wrote
 * this list), and this holds the port to them.
 */
class EldParityTest {

    private class Case(
        val text: String,
        val language: String,
        val reliable: Boolean,
        val score: Float,
    )

    companion object {
        private lateinit var cases: List<Case>

        @BeforeClass
        @JvmStatic
        fun load() {
            val json = File("src/test/resources/eld-cases.json").readText()
            // A handful of flat objects; a parser would be more than this needs.
            cases = Regex(
                """\{\s*"text":\s*"((?:[^"\\]|\\.)*)",\s*"language":\s*"([a-z]*)",\s*""" +
                    """"reliable":\s*(true|false),\s*"score":\s*([\d.]+)\s*}""",
            ).findAll(json).map { m ->
                Case(
                    text = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\"),
                    language = m.groupValues[2],
                    reliable = m.groupValues[3] == "true",
                    score = m.groupValues[4].toFloat(),
                )
            }.toList()
            check(cases.size >= 20) { "expected the generated cases, found ${cases.size}" }
            Eld.loadForTest(File("src/main/assets/eld.bin.gz"))
        }
    }

    @Test
    fun `names the same language as eld does`() {
        val wrong = cases.filter { Eld.detect(it.text).language.orEmpty() != it.language }
        assertEquals(
            "these came out as a different language than eld gives: " +
                wrong.joinToString { "${it.text.take(28)} -> ${Eld.detect(it.text).language} " +
                    "not ${it.language}" },
            emptyList<Case>(),
            wrong,
        )
    }

    @Test
    fun `agrees with eld about which answers are worth anything`() {
        val wrong = cases.filter { Eld.detect(it.text).reliable != it.reliable }
        assertEquals(
            "these disagree about reliability: " +
                wrong.joinToString { "${it.text.take(28)} -> ${Eld.detect(it.text).reliable} " +
                    "not ${it.reliable}" },
            emptyList<Case>(),
            wrong,
        )
    }

    @Test
    fun `scores what it scores to within a rounding error`() {
        for (case in cases) {
            if (case.language.isEmpty()) continue
            val got = Eld.detect(case.text).scores[case.language] ?: 0f
            assertTrue(
                "${case.text.take(30)}: scored $got where eld scores ${case.score}",
                kotlin.math.abs(got - case.score) <= 0.01f,
            )
        }
    }
}
