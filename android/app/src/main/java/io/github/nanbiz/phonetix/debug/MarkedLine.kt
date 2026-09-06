package io.github.nanbiz.phonetix.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView

/**
 * A line of the test page that says where it is in the picture, not only in the log.
 *
 * It paints a short bar at its left edge, inside the page's padding, where no transcription
 * can be - the page's words start well right of it. A capture of the real screen then holds
 * both this and the bar every transcription wears, so a test can ask whether a transcription
 * is on a line of text without believing a word the service says about itself.
 */
class MarkedLine(context: Context) : TextView(context) {

    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DebugMarks.LINE }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!DebugMarks.on) return
        // One bar per row of text, not one per view. A line of this page wraps, and a
        // transcription sits on whichever row its word is on: marking only the top of the
        // view left every second row of a wrapped line unmarked, and the transcriptions
        // standing on those rows looked like transcriptions standing on nothing.
        val text = layout ?: return
        for (row in 0 until text.lineCount) {
            // The middle of the letters, which is what a transcription covering them is the
            // middle of too. Their tops are not comparable: a transcription is drawn a little
            // taller than the word it replaces, so that the word cannot show around it.
            val metrics = text.paint.fontMetrics
            val middle = paddingTop + text.getLineBaseline(row) +
                (metrics.ascent + metrics.descent) / 2f
            val half = DebugMarks.THICK / 2f
            canvas.drawRect(0f, middle - half, DebugMarks.LINE_WIDE, middle + half, mark)
        }
    }
}
