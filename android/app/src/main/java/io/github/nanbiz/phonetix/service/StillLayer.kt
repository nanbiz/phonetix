package io.github.nanbiz.phonetix.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.view.View
import io.github.nanbiz.phonetix.core.WordBox

/**
 * A screenful of transcriptions on one window, for the words that do not have to be touched.
 *
 * A window per word is what lets one word be held for its card, and it is also a ceiling: a
 * device will not hand an app an unbounded number of windows, so the overlay stopped at
 * ninety-six and a page with more words than that was transcribed down to there and left bare
 * below - ninety-six of a hundred and forty-four on a page somebody was reading. Every read
 * also moved and repainted all of them, a call into the window manager each, which is most of
 * what made a page appear a piece at a time.
 *
 * This has no ceiling and costs one draw. What it cannot do is take a touch on one word:
 * Android has no public way to make a window touchable over part of itself, so a full-screen
 * window either swallows every scroll or takes nothing at all. It takes nothing. Where the
 * reader has asked to hold a word for its card, the words that answer a hold keep windows of
 * their own and this draws whatever is left over, so the page is whole either way.
 */
class StillLayer(context: Context, private val reveal: RevealState) : View(context) {

    private var boxes: List<WordBox> = emptyList()
    private val painter = ChipPainter()

    fun show(next: List<WordBox>) {
        boxes = next
        invalidate()
    }

    /** What it is drawing, so the overlay can say so without keeping a second copy. */
    fun drawn(): Int = boxes.size

    private val at = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        if (boxes.isEmpty()) return
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Where a word is, is where it is on the screen; where this draws is wherever the
        // window landed. They are the same only if the window starts at the very top left,
        // and it does not have to: a window laid out under the status bar puts every
        // transcription that far down the page, over the line below the one it belongs to.
        // Measured against the screen, eight of thirteen transcriptions changed nothing where
        // their word was.
        getLocationOnScreen(at)
        val shifted = at[0] != 0 || at[1] != 0
        if (shifted) {
            canvas.save()
            canvas.translate(-at[0].toFloat(), -at[1].toFloat())
        }
        for (b in boxes) {
            painter.draw(canvas, b.rect, b, dark, reveal.isRevealed(b.word))
        }
        if (shifted) canvas.restore()
    }
}
