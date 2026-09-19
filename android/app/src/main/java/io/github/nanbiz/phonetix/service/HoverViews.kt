package io.github.nanbiz.phonetix.service

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import io.github.nanbiz.phonetix.ui.themeNamed
import io.github.nanbiz.phonetix.ui.Tokens
import io.github.nanbiz.phonetix.core.SettingsStore
import android.content.res.Configuration

/**
 * The two things the hover draws that are not the thread: the mark it rests as, and the word
 * it is over.
 *
 * Carried over from Taplex, where this gesture was built.
 */
open class HoverBubbleView(context: Context) : View(context) {

    /**
     * The strip the mark occupies belongs to the mark, not to the system.
     *
     * It rests against the edge of the screen, which is where a swipe inwards means "back".
     * Dragging it therefore went back instead of picking it up - the activity being read
     * closed under the reader's finger - depending on how straight the first few pixels were.
     * Claiming the strip stops the system reading a drag that starts on the mark as a gesture
     * of its own.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
    }

    private companion object {
        /** The two inks the mark is drawn in, for a dark surface and for a bright one. */
        const val LIGHT = 0xFFFFFFFF.toInt()
        const val DARK = 0xFF10161D.toInt()
    }

    // The handle is the mark in one colour and see-through: parked over a conversation it
    // stays quiet, a shape rather than a full-colour badge sitting on someone's words.
    // The glyph alone, without the brackets the launcher icon sets it between: at the size of
    // a fingertip those are two bars with something small in the middle of them.
    private val mark = ContextCompat.getDrawable(context, io.github.nanbiz.phonetix.R.mipmap.ic_mark)

    /** Whether a finger is on it: parked it stays quieter than the conversation under it. */
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Whether what the mark is sitting on is bright.
     *
     * The mark is one colour and see-through, which on a dark conversation means a pale
     * shape and on a bright page means very nearly nothing at all. It is drawn in whichever
     * of the two stands against what is behind it.
     */
    var onLight: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** While a drag is on, the mark has become the thread; the handle draws nothing. */
    var masked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Coming back, it comes back into view: the thread has just drawn itself into
            // this spot, and a mark that blinks into being there instead undoes that.
            animate().cancel()
            if (value) {
                alpha = 1f
            } else {
                alpha = 0f
                animate().alpha(1f).setDuration(220).start()
            }
            invalidate()
        }

    /**
     * Whether the words on the page are being replaced.
     *
     * The button is what turns that on and off, so it is also the only thing that can say
     * which it is: a reader who pressed it and saw the page not change has no way of telling
     * a gesture that did nothing from a gesture that turned the replacing off. Shown as a
     * ring around the mark, because the mark itself has a job already.
     */
    var replacing: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (masked) return
        val icon = mark ?: return
        icon.setBounds(0, 0, width, height)
        // The mark wears the palette's own accent while the words are being replaced, and the
        // ink of whatever it is sitting on when they are not. One shape in two colours rather
        // than a shape with something added to it: the button is small, and a ring around it
        // is a second thing to look at for an answer the mark itself can give.
        icon.setTint(
            if (replacing) {
                val dark = (resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                Tokens.palette(themeNamed(SettingsStore.current.theme), dark).accent.toInt()
            } else {
                if (onLight) DARK else LIGHT
            },
        )
        // Solid enough to find, faint enough to read through; a touch stronger under the
        // finger so it answers the press. Dark ink on a bright page needs less of it to be
        // seen than pale ink on a dark one.
        // Solid in the accent, because a colour that says something has to be seen to say it;
        // quieter in plain ink, where the mark is only a handle over somebody's words.
        icon.alpha = when {
            replacing -> if (active) 255 else 230
            active -> 210
            onLight -> 150
            else -> 130
        }
        icon.draw(canvas)
    }
}

/**
 * The word being answered, and the circle aimed at it.
 *
 * The circle belongs here rather than to the thing being dragged: it exists only while a
 * finger is down, it sits well above that finger, and it must not take a touch. Drawing it
 * in the layer that already covers the screen keeps it out of the way of both.
 */
class HoverHighlightView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private companion object {
        /** How long the mark takes to appear or to go. */
        const val FADE_MS = 130L
        /** And to slide from the word it was on to the next one. */
        const val SLIDE_MS = 150L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 31, 111, 235)
    }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(52, 31, 111, 235)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.argb(255, 31, 111, 235)
    }
    private val pip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(255, 31, 111, 235)
    }

    /** Where the mark is drawn right now, which trails where it has been asked to be. */
    private val shown = RectF()

    /** Everything a sweep has taken in so far, drawn flat: the reader is watching the run
     *  grow, and a run whose every word slides and fades is a run nobody can read. */
    private var gathered: List<Rect> = emptyList()
    private var marked: Rect? = null
    private var settled = false
    private var presence = 0f
    private var travel: ValueAnimator? = null
    private var aimX = 0f
    private var aimY = 0f
    private var aimRadius = 0f

    /**
     * The word under the circle, or none.
     *
     * The mark slides from the word it was on to the word it is on now, and fades rather
     * than blinking at either end. A mark that jumps between words is read as several marks
     * appearing, which is exactly what the eye should not be doing while it follows one.
     */
    fun mark(bounds: Rect?) {
        val was = RectF(shown)
        val hadOne = settled && presence > 0f
        travel?.cancel()
        travel = null
        marked = bounds
        if (bounds == null) {
            if (!hadOne) { presence = 0f; settled = false; invalidate(); return }
            travel = ValueAnimator.ofFloat(presence, 0f).apply {
                duration = FADE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { presence = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        settled = false
                    }
                })
                start()
            }
            return
        }
        val to = RectF(bounds)
        if (!hadOne) {
            shown.set(to)
            settled = true
            travel = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FADE_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { presence = it.animatedValue as Float; invalidate() }
                start()
            }
            return
        }
        travel = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                shown.set(
                    was.left + (to.left - was.left) * f,
                    was.top + (to.top - was.top) * f,
                    was.right + (to.right - was.right) * f,
                    was.bottom + (to.bottom - was.bottom) * f,
                )
                presence = 1f
                invalidate()
            }
            start()
        }
    }

    /** The words a sweep has taken in, or none: the phrase as it stands. */
    fun gather(bounds: List<Rect>) {
        if (gathered == bounds) return
        gathered = bounds
        invalidate()
    }

    /** Where the circle is, or nothing at all once the finger is gone. */
    fun aim(x: Float, y: Float, radius: Float) {
        aimX = x
        aimY = y
        aimRadius = radius
        invalidate()
    }

    fun stopAiming() {
        aimRadius = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Under the word being taken in now, so the one the circle is over still reads as the
        // one it is over.
        for (box in gathered) {
            canvas.drawRoundRect(RectF(box), 6f, 6f, glass)
        }
        if (settled && presence > 0f) {
            paint.alpha = (90 * presence).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(shown, 6f, 6f, paint)
        }
        if (aimRadius <= 0f) return
        canvas.drawCircle(aimX, aimY, aimRadius - rim.strokeWidth, glass)
        canvas.drawCircle(aimX, aimY, aimRadius - rim.strokeWidth, rim)
        canvas.drawCircle(aimX, aimY, 3 * density, pip)
    }
}

