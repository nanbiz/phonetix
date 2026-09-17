package io.github.nanbiz.phonetix.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/**
 * The whole screen in the reader's own language, on a press of the mark.
 *
 * The other half of what this is for. A word at a time answers "what is that": a page of a
 * language somebody is still learning is a different question, and a reader who has to ask it
 * word by word has stopped reading. Carried over from Taplex, where a tap on the mark laid the
 * page's own lines over it translated and the next tap took them away.
 *
 * The lines are drawn where the app drew them, in the colours the app used, so the screen
 * keeps its shape and only reads differently. Nothing here takes a touch: the app underneath
 * is still the thing being scrolled, and the mark is the only way back out.
 */
class PageController(
    private val context: Context,
    /** What the lines say in the reader's language, asked off the main thread. */
    private val translate: (List<String>) -> List<String>,
    /** Told when the page goes up or comes down, since nothing else of ours draws while it is. */
    private val onShown: (Boolean) -> Unit = {},
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: PageOverlayView? = null

    /** What has already been translated, so a scroll does not ask the engine again. */
    private val said = HashMap<String, String>()

    val showing: Boolean get() = view != null

    /** The press that puts the page into the reader's language, and the one that puts it back. */
    fun toggle(lines: List<PageOverlayView.Line>) {
        if (view != null) {
            hide()
            return
        }
        // Nothing to replace is not a replaced page.
        //
        // A press with no lines to hand put an empty sheet up: nothing was drawn, and
        // everything that asks whether the page has been replaced - the mark above all -
        // answered yes and refused to say anything about any word. The reader is left
        // dragging a circle over a screen of text that answers nothing, with no way back
        // except another press they have no reason to make.
        if (lines.isEmpty()) {
            if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                android.util.Log.d("Phonetix", "PAGE nothing to replace")
            }
            return
        }
        show(lines)
    }

    /**
     * Ask the engine what these lines say, off the main thread.
     *
     * Separate from drawing because translating a screen is a round trip per line through the
     * engine, and the drawing happens on the main thread: asked there, the first press of the
     * mark froze the screen it was replacing. Everything already seen is answered from the
     * cache, so a scroll only pays for the lines that have just come into view.
     */
    fun prepare(lines: List<PageOverlayView.Line>) {
        val wanted = lines.map { it.text }.filter { it.isNotBlank() && it !in said }.distinct()
        if (wanted.isEmpty()) return
        val answers = translate(wanted)
        wanted.forEachIndexed { at, text ->
            val answer = answers.getOrNull(at).orEmpty().trim()
            if (answer.isNotEmpty()) said[text] = answer
        }
    }

    /**
     * Draw these lines, in what they say.
     *
     * Called again on every pass while the page is up, because the page moves: a translation
     * pinned where a line used to be is a translation over the wrong words.
     */
    fun draw(lines: List<PageOverlayView.Line>) {
        val page = view ?: return
        page.show(dressed(lines))
    }

    private fun show(lines: List<PageOverlayView.Line>) {
        val page = PageOverlayView(context)
        runCatching { wm.addView(page, params()) }
            .onSuccess {
                view = page
                onShown(true)
                page.show(dressed(lines))
                if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) {
                    android.util.Log.d("Phonetix", "PAGE up with ${lines.size} lines")
                }
            }
            .onFailure { android.util.Log.w("Phonetix", "the page did not go up", it) }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        onShown(false)
        if (io.github.nanbiz.phonetix.BuildConfig.DEBUG) android.util.Log.d("Phonetix", "PAGE down")
    }

    /** The same lines, saying what they say in the reader's language. Whatever has no answer
     *  yet is left out rather than shown in the language the reader cannot read: the next pass
     *  has it, and a line of the original standing among the translated ones reads as a line
     *  the translation got wrong. */
    private fun dressed(lines: List<PageOverlayView.Line>): List<PageOverlayView.Line> =
        lines.mapNotNull { line ->
            val answer = said[line.text] ?: return@mapNotNull null
            line.copy(text = answer)
        }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            // The page underneath is still the thing being read and scrolled; the mark is the
            // only way back out, and it has a window of its own.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
    }
}
