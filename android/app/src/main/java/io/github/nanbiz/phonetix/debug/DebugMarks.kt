package io.github.nanbiz.phonetix.debug

/**
 * Marks that make the overlay and the page findable in a photograph of the screen.
 *
 * Every other test here reads what the service says it did. That is the wrong witness for the
 * one question that matters - whether a reader looking at the screen sees a transcription on
 * its word - because a service can report a position it never drew, draw it a frame late, or
 * draw it and have the window land somewhere else. So a test can ask for these instead: each
 * transcription paints a short bar in one colour and each line of the test page paints one in
 * another, and a capture of the real screen then says where both of them actually are, with
 * nothing of ours in between.
 *
 * Off unless a test switches it on, so the build a reader is holding looks as it should.
 */
object DebugMarks {

    @Volatile
    var on = false

    /** The bar every transcription wears, in the frames a test captures. */
    const val CHIP = 0xFFFF00FF.toInt()

    /** And the one every line of the test page wears, at its left edge, where no
     *  transcription can be: the page is padded and its words start well right of it. */
    const val LINE = 0xFF00FFFF.toInt()

    /** How tall the bars are.
     *
     *  A capture of the screen arrives scaled down - a 1080px screen comes back 320px wide -
     *  so a bar of a few pixels is a fraction of one in the picture and disappears from some
     *  rows and not others. Thick enough to survive that, which is thicker than it looks. */
    const val THICK = 16f

    /** How wide a line's bar is.
     *
     *  Narrow enough to stay inside the page's padding, where no transcription can reach. A
     *  wider one was covered by the transcription of the line's own first word - which is
     *  drawn over that word, and the word starts where the padding ends - so exactly the
     *  lines that had a transcription at their left edge reported no line at all. */
    const val LINE_WIDE = 26f

    /** The space kept clear at the left of every line for that bar. Wider than the bar, so
     *  the first word of a line - and the transcription covering it - starts right of it. */
    const val GUTTER = 40
}
