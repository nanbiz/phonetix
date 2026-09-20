package io.github.nanbiz.phonetix.service

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import io.github.nanbiz.phonetix.BuildConfig
import io.github.nanbiz.phonetix.core.WordBox

/**
 * How a transcription is painted over a word, in one place.
 *
 * Two things draw them - the small window each word gets while the screen is still, and the
 * single layer they all ride on while it is moving - and the two looking even slightly
 * different would show as a flicker at the moment one takes over from the other.
 */
class ChipPainter {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }

    /** A scratch paint for asking how wide a word would be in a given face. */
    private val ruler = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faces = listOf(
        Typeface.SANS_SERIF,
        Typeface.SERIF,
        Typeface.MONOSPACE,
        Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
        Typeface.create(Typeface.SERIF, Typeface.BOLD),
    )
    private val matched = HashMap<String, Typeface>(64)

    /**
     * Which face the app drew this word in, worked out from how wide it drew it.
     *
     * Accessibility carries no typeface, but it does carry the exact rectangle the word
     * occupies, and the word itself. Setting each candidate at the line's own size and
     * measuring the same word in it gives a width to compare against the real one: the face
     * that comes closest is the face on screen, or near enough that the replacement stops
     * looking pasted on. Serif against sans is a difference of several percent in a word of
     * any length, and bold against regular more, so the comparison is not delicate.
     */
    private fun faceFor(word: String, size: Float, width: Float): Typeface {
        if (word.isEmpty() || width <= 0f) return Typeface.SANS_SERIF
        val key = word + "|" + size.toInt() + "|" + width.toInt()
        matched[key]?.let { return it }
        ruler.textSize = size
        var best = Typeface.SANS_SERIF
        var bestErr = Float.MAX_VALUE
        for (face in faces) {
            ruler.typeface = face
            val err = abs(ruler.measureText(word) - width)
            if (err < bestErr) { bestErr = err; best = face }
        }
        if (matched.size > 512) matched.clear()
        matched[key] = best
        return best
    }

    fun draw(
        canvas: Canvas,
        where: RectF,
        box: WordBox,
        dark: Boolean,
        revealed: Boolean,
    ) {
        val height = where.height()
        val width = where.width()
        if (height <= 0f || width <= 0f) return
        // Lifted off the page: nothing is drawn at all, and what shows through is the app's
        // own word in the app's own type. Painting the word again over its own patch was the
        // same word twice - ours a shade brighter and a weight heavier than the page it sat
        // in - where drawing nothing is the page itself.
        if (revealed) return

        // The word's own colours, read off the screen, are what make a replacement look like
        // the text it stands in for. The palette below is only for when they could not be
        // read at all.
        val sampled = box.background != 0 && box.ink != 0
        val chip = when {
            sampled -> box.background
            else -> if (dark) Color.rgb(0x1B, 0x14, 0x10) else Color.rgb(0xF7, 0xEF, 0xDD)
        }
        val fg = when {
            sampled -> box.ink
            else -> if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0xB4, 0x53, 0x09)
        }

        // Square, and rounded only when the colour had to be guessed: when the surface came
        // off the screen the patch is the colour of what surrounds it, and a rounded corner
        // is the one thing that would give it away as a patch.
        bg.color = chip
        val r = if (sampled) 0f else height * 0.18f
        canvas.drawRoundRect(where, r, r, bg)

        // A bar a capture of the real screen can find, so that where a transcription actually
        // ended up can be measured from the screen rather than from what this service says
        // about itself. Only when a test has asked for it.
        if (BuildConfig.DEBUG && io.github.nanbiz.phonetix.debug.DebugMarks.on) {
            // Down the middle of the chip, not along its top edge: a chip is a little taller
            // than the letters it covers, so its top is not where its word is, and its middle
            // is. The page marks the middle of each of its rows to match.
            bg.color = io.github.nanbiz.phonetix.debug.DebugMarks.CHIP
            val middle = where.centerY()
            val half = io.github.nanbiz.phonetix.debug.DebugMarks.THICK / 2f
            canvas.drawRect(where.left, middle - half, where.right, middle + half, bg)
            bg.color = chip
        }

        val label = box.ipa
        if (label.isEmpty()) return

        // The width is the space the original word occupied, so the type is what gives:
        // sized to the line, then shrunk until it fits, so a replacement never pushes into
        // the words on either side.
        var size = height * 0.80f
        // In the face the app itself used, deduced from the width it gave the word.
        ink.typeface = faceFor(box.word, size, width)
        ink.textSize = size
        val room = width - height * 0.12f
        val measured = ink.measureText(label)
        if (measured > room && measured > 0f) {
            size *= room / measured
            ink.textSize = size
        }

        ink.color = fg
        val fm = ink.fontMetrics
        canvas.drawText(label, where.centerX(), where.centerY() - (fm.ascent + fm.descent) / 2f, ink)
        // Last, so the chip's own background does not cover it.
        markLine(canvas, where, box)
    }

    /**
     * A patch of colour naming the line this word came from, drawn on the word itself.
     *
     * The page paints the same number into its own background, so a photograph of the screen
     * holds both: what the overlay believes and what is actually there. Comparing them needs no
     * log and no reading of the text, which is what makes it the one check here that cannot
     * agree with a mistake the overlay is making.
     */
    private fun markLine(canvas: Canvas, where: RectF, box: WordBox) {
        if (!io.github.nanbiz.phonetix.BuildConfig.DEBUG) return
        if (box.line < 0) return
        mark.color = Marks.believed(box.line)
        canvas.drawRect(
            where.left, where.top, where.left + Marks.SIZE, where.top + Marks.SIZE, mark,
        )
    }

    private val mark = android.graphics.Paint()
}

/** How a line's number is written into a colour, and read back out of one. */
object Marks {
    /** Big enough to survive a screenshot being looked at, small enough not to cover the word. */
    const val SIZE = 10f

    /** How many numbers one channel carries, and how far apart they are. */
    private const val STEPS = 12
    private const val APART = 18

    /** The colour a page paints behind the line numbered this. Dark, because the fixture's
     *  text is white. */
    fun page(line: Int): Int = android.graphics.Color.rgb(
        24 + (line % STEPS) * APART, 24, 24 + ((line / STEPS) % STEPS) * APART,
    )

    /** The colour the overlay paints on a word it believes came from the line numbered this.
     *  Full green, which no page colour has, so the two are told apart by that alone. */
    fun believed(line: Int): Int = android.graphics.Color.rgb(
        24 + (line % STEPS) * APART, 220, 24 + ((line / STEPS) % STEPS) * APART,
    )
}
