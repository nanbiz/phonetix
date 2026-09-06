package io.github.nanbiz.phonetix.core

import android.content.Context
import java.io.DataInputStream
import java.io.InputStream

/**
 * Which language a piece of text is in, from the shape of its characters.
 *
 * A port of eld (Nito T.M., Apache-2.0), the detector the browser extension uses, so that the
 * two agree about what language a line is in. It scores character ngrams against a table built
 * from a corpus of sixty languages: text is cut into words, each word into overlapping runs of
 * four bytes, and each run votes for the languages that use it, weighted by how few of them do.
 *
 * The alternative here was the three hundred commonest words of each language, which answers a
 * page of sentences and little else - a screen of labels contains no function words at all,
 * and "Video" is among Italian's commonest words and not among English's. This decides
 * "Speichern" and "Nächster Titel" on their own.
 *
 * The table is an asset: keys sorted and packed into longs, searched rather than hashed, so
 * fifty thousand ngrams cost one array and no objects.
 */
object Eld {

    /** What the text was found to be, and whether the finding is worth anything. */
    class Guess(val language: String?, val reliable: Boolean, val scores: Map<String, Float>)

    val nothing = Guess(null, false, emptyMap())

    @Volatile private var loaded = false

    /** Byte value to the character eld's dictionary gives it, then to its index in the model's
     *  alphabet. Both are needed: text becomes characters, characters become indices. */
    private val byteToIndex = IntArray(256) { -1 }

    private lateinit var codes: Array<String>
    /** What a language's ngrams score on average, which is what a result is held against
     *  before it counts as reliable. */
    private lateinit var averages: FloatArray
    private lateinit var keys: LongArray
    private lateinit var starts: IntArray
    private lateinit var langs: ByteArray
    private lateinit var scores: IntArray

    val ready: Boolean get() = loaded

    fun ensureLoaded(context: Context, then: (() -> Unit)? = null) {
        if (loaded) { then?.invoke(); return }
        synchronized(this) {
            if (!loaded) {
                runCatching { read(context) }
                    .onFailure { android.util.Log.w("Phonetix", "no language model", it) }
            }
        }
        then?.invoke()
    }

    /**
     * Read the model straight off the disk, for a test that has no Android around it.
     *
     * The parity test exists to hold this against the JavaScript it was ported from, and it
     * cannot ask an app for its assets.
     */
    fun loadForTest(file: java.io.File) {
        loaded = false
        read(java.util.zip.GZIPInputStream(file.inputStream()))
    }

    private fun open(context: Context): InputStream =
        runCatching {
            java.util.zip.GZIPInputStream(context.assets.open("eld.bin.gz")) as InputStream
        }.getOrElse { context.assets.open("eld.bin") }

    private fun read(context: Context) = read(open(context))

    private fun read(from: InputStream) {
        DataInputStream(java.io.BufferedInputStream(from, 1 shl 16)).use { source ->
            val magic = ByteArray(6)
            source.readFully(magic)
            check(String(magic, Charsets.US_ASCII) == "PXELD2") { "not a language model" }
            val languageCount = source.readUnsignedShortLE()
            val codesLength = source.readUnsignedShortLE()
            val codesBytes = ByteArray(codesLength)
            source.readFully(codesBytes)
            codes = String(codesBytes, Charsets.US_ASCII).split(",").toTypedArray()
            check(codes.size == languageCount) { "the model names a different number of languages" }

            averages = FloatArray(languageCount)
            for (i in 0 until languageCount) {
                averages[i] = java.lang.Float.intBitsToFloat(source.readIntLE())
            }
            val table = ByteArray(256)
            source.readFully(table)

            val ngrams = source.readIntLE()
            val pairs = source.readIntLE()
            val alphabetLength = source.readUnsignedShortLE()
            val alphabetBytes = ByteArray(alphabetLength)
            source.readFully(alphabetBytes)
            // The alphabet is read but not kept: what a lookup needs is the index a byte
            // maps to, and the model says that outright.
            check(alphabetBytes.isNotEmpty()) { "the model has no alphabet" }
            for (b in 0 until 256) byteToIndex[b] = table[b].toInt() and 0xFF

            keys = LongArray(ngrams)
            for (i in 0 until ngrams) keys[i] = source.readLongLE()
            starts = IntArray(ngrams + 1)
            for (i in 0..ngrams) starts[i] = source.readIntLE()
            langs = ByteArray(pairs)
            source.readFully(langs)
            scores = IntArray(pairs)
            for (i in 0 until pairs) scores[i] = source.readUnsignedShortLE()
            loaded = true
        }
    }

    /**
     * What language this text is in.
     *
     * Follows eld: the text is cut at everything that is not a letter, each word is walked in
     * four-byte steps of three, and a run that few languages use counts for more than one they
     * all share.
     */
    fun detect(text: CharSequence): Guess {
        if (!loaded || text.isEmpty()) return nothing
        val counts = HashMap<Long, Int>(64)
        var total = 0
        forEachWord(text) { word ->
            total += ngramsOf(word) { key -> counts[key] = (counts[key] ?: 0) + 1 }
        }
        if (total == 0) return nothing
        val totals = FloatArray(codes.size)
        for ((key, count) in counts) {
            val at = search(key)
            if (at < 0) continue
            // The frequency the model was built at, scaled the way eld scales it.
            val frequency = count.toFloat() / total * FREQUENCY
            val from = starts[at]
            val until = starts[at + 1]
            val spoken = until - from
            val relevancy = when {
                spoken == 1 -> ALONE
                spoken < 16 -> (16 - spoken) / 2f + 1f
                else -> 1f
            }
            for (i in from until until) {
                val world = scores[i].toFloat()
                val share = if (frequency > world) world / frequency else frequency / world
                totals[langs[i].toInt() and 0xFF] += share * relevancy + 2f
            }
        }
        val divisor = counts.size * DIVISOR
        var best = -1
        var bestScore = 0f
        val out = HashMap<String, Float>(8)
        for (i in totals.indices) {
            if (totals[i] <= 0f) continue
            val score = totals[i] / divisor
            out[codes[i]] = score
            if (score > bestScore) { bestScore = score; best = i }
        }
        if (best < 0) return nothing
        // eld's own rule: enough ngrams to be worth judging, and a winner that scores at least
        // a quarter of what that language usually scores per ngram. Short text - a tab, a
        // button, a name - is what this rules out, and the caller answers for those by other
        // means, the way the extension answers for them with the page's language.
        val reliable = counts.size >= ENOUGH_NGRAMS &&
            averages[best] * RELIABLE_SHARE <= bestScore / counts.size
        return Guess(codes[best], reliable, out)
    }

    /** Where this ngram is in the table, or -1. */
    private fun search(key: Long): Int {
        var low = 0
        var high = keys.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val there = keys[middle]
            if (there < key) low = middle + 1 else if (there > key) high = middle - 1 else return middle
        }
        return -1
    }

    /** The words of a text, lowercased, cut at everything that is not a letter. */
    private inline fun forEachWord(text: CharSequence, emit: (ByteArray) -> Unit) {
        val builder = StringBuilder(32)
        var seen = 0
        var i = 0
        while (i < text.length && seen < MAX_TEXT) {
            val cp = Character.codePointAt(text, i)
            val size = Character.charCount(cp)
            i += size
            seen += size
            val letter = Character.isLetter(cp) || cp == '\''.code || cp == '’'.code
            if (letter) {
                builder.appendCodePoint(Character.toLowerCase(cp))
            } else if (builder.isNotEmpty()) {
                emit(builder.toString().toByteArray(Charsets.UTF_8))
                builder.setLength(0)
            }
        }
        if (builder.isNotEmpty()) emit(builder.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * The ngrams of one word, as eld cuts them: four bytes at a time stepping three, the first
     * with a space before it and the last with a space after, so that where a word begins and
     * ends counts for as much as what is in the middle of it.
     */
    private inline fun ngramsOf(word: ByteArray, emit: (Long) -> Unit): Int {
        val length = if (word.size > MAX_WORD) MAX_WORD else word.size
        var made = 0
        var j = 0
        while (j + 4 < length) {
            var key = if (j == 0) space() else 0L
            for (k in j until j + 4) key = (key shl 8) or index(word[k])
            emit(key)
            made++
            j += 3
        }
        var key = if (j == 0) space() else 0L
        val from = if (length != 3) length - 4 else 0
        for (k in (if (from < 0) 0 else from) until length) key = (key shl 8) or index(word[k])
        key = (key shl 8) or space()
        emit(key)
        return made + 1
    }

    private fun index(b: Byte): Long = byteToIndex[b.toInt() and 0xFF].toLong()

    private fun space(): Long = byteToIndex[' '.code].toLong()

    /** eld multiplies the frequency of an ngram in the text by this before comparing it with
     *  the frequency in the model, which was built at fifteen thousand. */
    private const val FREQUENCY = 13200f

    /** What an ngram only one language uses is worth, against the one to eight of a shared
     *  one. Handpicked in eld, kept here so the two agree. */
    private const val ALONE = 27f

    /** Brings the total into roughly nought to one. */
    private const val DIVISOR = 3.2f

    /** How many ngrams a text has to have before its answer means anything, and how much of
     *  a language's usual per-ngram score the winner has to reach. Both eld's. */
    private const val ENOUGH_NGRAMS = 3
    private const val RELIABLE_SHARE = 0.24f

    /** eld reads at most this much of a text, and this much of any one word. */
    private const val MAX_TEXT = 1000
    private const val MAX_WORD = 70

    private fun DataInputStream.readUnsignedShortLE(): Int {
        val low = read()
        val high = read()
        return (high shl 8) or low
    }

    private fun DataInputStream.readIntLE(): Int {
        var value = 0
        for (shift in 0 until 4) value = value or (read() shl (shift * 8))
        return value
    }

    private fun DataInputStream.readLongLE(): Long {
        var value = 0L
        for (shift in 0 until 8) value = value or (read().toLong() shl (shift * 8))
        return value
    }
}
