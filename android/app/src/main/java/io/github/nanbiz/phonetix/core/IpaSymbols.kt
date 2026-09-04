package io.github.nanbiz.phonetix.core

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/** What a single symbol of a transcription is called, and a word it is heard in. */
data class SymbolInfo(val token: String, val name: String, val example: String)

/**
 * The names behind the symbols in a transcription, as the extension's tooltip gives them.
 *
 * The table, the marks a symbol can carry and the suprasegmentals that stand alone are all
 * generated from src/lib/ipa-symbols.ts into shared/ipa-symbols.json rather than written
 * again here. Only the splitting and the re-wording are ported, and
 * IpaSymbolsParityTest holds both to what the extension produces for the same input.
 */
object IpaSymbols {

    private val symbols = HashMap<String, SymbolInfo>(160)
    private val diacritics = HashMap<String, String>(48)
    private val standalone = HashSet<Char>(8)

    @Volatile
    var ready = false
        private set

    fun ensureLoaded(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            runCatching {
                val raw = context.applicationContext.assets.open("ipa-symbols.json")
                    .bufferedReader().use { it.readText() }
                load(JSONObject(raw))
            }
            ready = true
        }
    }

    /** Split out so the test can feed it the same file without an Android context. */
    fun load(root: JSONObject) {
        val s = root.getJSONObject("symbols")
        for (token in s.keys()) {
            val o = s.getJSONObject(token)
            symbols[token] = SymbolInfo(token, o.getString("name"), o.optString("example", ""))
        }
        val d = root.getJSONObject("diacritics")
        for (mark in d.keys()) diacritics[mark] = d.getString(mark)
        val m = root.getJSONArray("standaloneModifiers")
        for (i in 0 until m.length()) m.getString(i).firstOrNull()?.let { standalone.add(it) }
    }

    private fun isCombining(c: Char) = (c in '̀'..'ͯ') || c == '͡' || c == '͜'
    private fun isModifier(c: Char) = c in 'ʰ'..'˿'

    /**
     * A transcription split into the symbols a reader would name.
     *
     * A symbol takes the marks that follow it with it, because a mark describes the sound
     * before it rather than being a sound of its own; a tie bar takes the character after
     * it too, since it joins two into one. Stress and length sit in the same range as those
     * marks but are symbols in their own right, and stand alone.
     */
    fun tokenize(ipa: String): List<String> {
        val out = ArrayList<String>(ipa.length)
        var i = 0
        while (i < ipa.length) {
            val token = StringBuilder().append(ipa[i])
            i++
            while (i < ipa.length) {
                val ch = ipa[i]
                if (isCombining(ch) || (isModifier(ch) && ch !in standalone)) {
                    token.append(ch)
                    i++
                    if ((ch == '͡' || ch == '͜') && i < ipa.length) {
                        token.append(ipa[i])
                        i++
                    }
                } else break
            }
            out.add(token.toString())
        }
        return out
    }

    /**
     * What a symbol is called, marks and all.
     *
     * A symbol the table names outright is answered directly. Otherwise it is taken apart -
     * which also separates a vowel that arrived precomposed, like "ã", into a vowel and a
     * mark on it - and the marks are named in front of the base sound. A voicing mark
     * replaces the base's own voicing rather than stacking on it, so z̥ is voiceless, not
     * "voiceless voiced".
     */
    fun describe(token: String): SymbolInfo? {
        symbols[token]?.let { return it }

        val marks = ArrayList<String>(2)
        val base = StringBuilder()
        for (ch in Normalizer.normalize(token, Normalizer.Form.NFD)) {
            val mark = diacritics[ch.toString()]
            if (mark != null) marks.add(mark) else base.append(ch)
        }
        val info = symbols[Normalizer.normalize(base, Normalizer.Form.NFC)] ?: return null
        if (marks.isEmpty()) return info

        var baseName = info.name
        if (marks.contains("voiceless") || marks.contains("voiced")) {
            baseName = baseName.removePrefix("voiceless ").removePrefix("voiced ")
        }
        val joined = marks.joinToString(", ")
        return SymbolInfo(token, "$joined $baseName", "${info.example} ($joined)")
    }

    /** Every symbol of a transcription, with the ones we have no name for left out. */
    fun explain(ipa: String): List<SymbolInfo> = tokenize(ipa).mapNotNull { describe(it) }
}
