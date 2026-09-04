package io.github.nanbiz.phonetix.service

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import io.github.nanbiz.phonetix.core.ChipStyle
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

    fun draw(
        canvas: Canvas,
        where: RectF,
        box: WordBox,
        style: ChipStyle,
        dark: Boolean,
        revealed: Boolean,
    ) {
        val height = where.height()
        val width = where.width()
        if (height <= 0f || width <= 0f) return

        // The word's own colours, read off the screen, are what make a replacement look like
        // the text it stands in for. The palette below is only for when they could not be
        // read at all.
        val sampled = box.background != 0 && box.ink != 0
        val chip = when {
            sampled -> box.background
            style == ChipStyle.SOFT ->
                if (dark) Color.argb(0xE6, 0x1B, 0x14, 0x10) else Color.argb(0xE6, 0xF7, 0xEF, 0xDD)
            else -> if (dark) Color.rgb(0x1B, 0x14, 0x10) else Color.rgb(0xF7, 0xEF, 0xDD)
        }
        val fg = when {
            revealed && sampled -> box.ink
            revealed -> if (dark) Color.rgb(0xF5, 0xED, 0xE0) else Color.rgb(0x2B, 0x21, 0x17)
            sampled -> box.ink
            else -> if (dark) Color.rgb(0xFB, 0xBF, 0x24) else Color.rgb(0xB4, 0x53, 0x09)
        }

        // Square, and rounded only when the colour had to be guessed: when the surface came
        // off the screen the patch is the colour of what surrounds it, and a rounded corner
        // is the one thing that would give it away as a patch.
        bg.color = chip
        val r = if (sampled) 0f else height * 0.18f
        canvas.drawRoundRect(where, r, r, bg)

        val label = if (revealed) box.word else box.ipa
        if (label.isEmpty()) return

        // The width is the space the original word occupied, so the type is what gives:
        // sized to the line, then shrunk until it fits, so a replacement never pushes into
        // the words on either side.
        var size = height * 0.80f
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
    }
}
