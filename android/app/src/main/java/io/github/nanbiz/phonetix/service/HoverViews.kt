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
        // Only when the size changed: a layout pass is every frame the mark moves, and a new
        // list handed over every one of those is work for the same answer.
        if (changed && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            claimed.set(0, 0, width, height)
            systemGestureExclusionRects = listOf(claimed)
        }
    }

    private val claimed = Rect()

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

    override fun onDraw(canvas: Canvas) {
        if (masked) return
        val icon = mark ?: return
        icon.setBounds(0, 0, width, height)
        // The ink of whatever it is sitting on, solid enough to find and faint enough to read
        // through; a touch stronger under the finger so it answers the press. Dark ink on a
        // bright page needs less of it to be seen than pale ink on a dark one.
        icon.setTint(if (onLight) DARK else LIGHT)
        icon.alpha = when {
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


/**
 * Where the mark is put away: a round target at the foot of the screen, in the middle, that
 * rises while the mark is dragged near the bottom and takes it when it is let go there.
 *
 * Drawn on a window of its own that takes no touch, the full width of the screen and as tall
 * as the shade behind the target: the shade is what makes a pale target readable over a pale
 * page and a dark one over a dark page alike.
 */
class DropTargetView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val palette = Tokens.palette(themeNamed(SettingsStore.current.theme), dark)

    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2.5f)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 0, 0, 0)
        maskFilter = android.graphics.BlurMaskFilter(dp(10f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    init {
        // The shadow is blurred, which only a software layer draws.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** How far up it has come, from nothing to all the way. */
    private var presence = 0f

    /** How far it has taken the mark in, from nothing to holding it. */
    private var pull = 0f

    /** What it is doing once the mark has been let go on it: shrinking away with it. */
    private var swallowed = 0f

    private var rising: ValueAnimator? = null
    private var pulling: ValueAnimator? = null

    /** Whether it is up, or on its way up. */
    var present = false
        private set

    /** Whether the mark is over it, so that letting go puts the mark away. */
    var holding = false
        private set

    /** How far up the system's own bar at the foot of the screen reaches, in pixels. */
    var lifted = 0f

    /** Where its middle is, in this view's own coordinates, when it is all the way up. */
    fun centreY(): Float = height - lifted - dp(BOTTOM_DP) - dp(RADIUS_DP)

    /** Come up, or go back down. */
    fun present(up: Boolean) {
        if (present == up) return
        present = up
        if (!up) take(false)
        rising?.cancel()
        rising = ValueAnimator.ofFloat(presence, if (up) 1f else 0f).apply {
            duration = if (up) RISE_MS else FALL_MS
            // Up with a little overshoot, so it arrives rather than stops; down quickening,
            // the way a thing falls away.
            interpolator = if (up) android.view.animation.OvershootInterpolator(1.6f)
            else android.view.animation.AccelerateInterpolator(1.4f)
            addUpdateListener {
                presence = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Take the mark, or let it go again. */
    fun take(over: Boolean) {
        if (holding == over) return
        holding = over
        pulling?.cancel()
        pulling = ValueAnimator.ofFloat(pull, if (over) 1f else 0f).apply {
            duration = PULL_MS
            interpolator = if (over) android.view.animation.OvershootInterpolator(2.2f)
            else DecelerateInterpolator()
            addUpdateListener {
                pull = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** The mark has been let go on it: the two go together, and then [done]. */
    fun swallow(done: () -> Unit) {
        rising?.cancel()
        pulling?.cancel()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWALLOW_MS
            interpolator = android.view.animation.AnticipateInterpolator(1.2f)
            addUpdateListener {
                swallowed = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = done()
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val shown = presence.coerceIn(0f, 1f)
        if (shown <= 0f && swallowed <= 0f) return
        val fade = (1f - swallowed).coerceIn(0f, 1f)

        // The shade: darkening towards the foot, stronger while the target holds the mark.
        val depth = ((0.28f + 0.14f * pull) * shown * fade * 255).toInt()
        shade.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            Color.argb(0, 0, 0, 0), Color.argb(depth, 0, 0, 0),
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)

        val radius = dp(RADIUS_DP) * (1f + GROWS * pull) * (1f - swallowed * 0.9f)
        if (radius <= 0f) return
        val cx = width / 2f
        // Risen from below the screen's edge, so it comes up into view rather than appearing.
        val below = (lifted + dp(BOTTOM_DP) + dp(RADIUS_DP) * 2f) * (1f - presence)
        val cy = centreY() + below
        val alpha = (shown * fade * 255).toInt().coerceIn(0, 255)

        shadow.alpha = (alpha * 0.4f).toInt()
        canvas.drawCircle(cx, cy + dp(3f), radius, shadow)

        val quiet = (palette.surfaceRaised and 0xFFFFFF).toInt()
        val loud = (palette.danger and 0xFFFFFF).toInt()
        disc.color = blend(quiet, loud, pull) or (alpha shl 24)
        canvas.drawCircle(cx, cy, radius, disc)
        rim.color = ((palette.border and 0xFFFFFF).toInt()) or (((1f - pull) * alpha).toInt() shl 24)
        canvas.drawCircle(cx, cy, radius, rim)

        val inkQuiet = (palette.ink and 0xFFFFFF).toInt()
        cross.color = blend(inkQuiet, 0xFFFFFF, pull) or (alpha shl 24)
        val arm = radius * 0.34f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, cross)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, cross)
    }

    /** Two colours mixed, [by] of the way from the first to the second. */
    private fun blend(from: Int, to: Int, by: Float): Int {
        val t = by.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return ((a + (b - a) * t).toInt() and 0xFF) shl shift
        }
        return channel(16) or channel(8) or channel(0)
    }

    companion object {
        /** The target's radius, in dp. */
        const val RADIUS_DP = 28f

        /** How far above the foot of the screen it sits, clear of the gesture strip. */
        const val BOTTOM_DP = 36f

        /** How tall the shade behind it is, which is the height of its window. */
        const val SHADE_DP = 200f

        /** How much larger it grows while it holds the mark. */
        const val GROWS = 0.22f

        const val RISE_MS = 280L
        const val FALL_MS = 180L
        const val PULL_MS = 200L
        const val SWALLOW_MS = 260L
    }
}

/**
 * What the card would do with the word the circle has stayed on, offered round the finger: say
 * it, and open its page. The finger slides onto one and lifts to do it; lifted anywhere else,
 * nothing is done.
 *
 * Drawn in the layer that covers the screen and takes no touch: the finger is still on the
 * mark, which follows it, and which one it is over is worked out from where it is.
 */
class ActionsView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val palette = Tokens.palette(themeNamed(SettingsStore.current.theme), dark)

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 0, 0, 0)
        maskFilter = android.graphics.BlurMaskFilter(dp(8f), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    init {
        // The shadow is blurred, which only a software layer draws.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val icons = listOf(
        ContextCompat.getDrawable(context, io.github.nanbiz.phonetix.R.drawable.ic_play),
        ContextCompat.getDrawable(context, io.github.nanbiz.phonetix.R.drawable.ic_wiktionary),
        ContextCompat.getDrawable(context, io.github.nanbiz.phonetix.R.drawable.ic_star),
    )

    /** Whether the word they are for is already kept, which the star shows. */
    private var kept = false

    /** Where each action is, in the order of [ACTIONS], or nothing while none is offered. */
    private var spots: List<android.graphics.PointF> = emptyList()
    private var chosen = -1
    private var presence = 0f
    private var rising: ValueAnimator? = null

    /** Whether the actions are up. */
    val open: Boolean get() = spots.isNotEmpty()

    /** Put the actions up at these places, one for each of [ACTIONS]. */
    fun offer(at: List<android.graphics.PointF>, alreadyKept: Boolean) {
        spots = at
        kept = alreadyKept
        chosen = -1
        rising?.cancel()
        rising = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RISE_MS
            interpolator = android.view.animation.OvershootInterpolator(1.4f)
            addUpdateListener {
                presence = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Take them down. */
    fun close() {
        rising?.cancel()
        spots = emptyList()
        chosen = -1
        presence = 0f
        invalidate()
    }

    /** The action the finger at ([x], [y]) is on, or -1; told so it can be drawn chosen. */
    fun choose(x: Float, y: Float): Int {
        var best = -1
        var nearest = dp(TAKES_DP)
        for ((i, spot) in spots.withIndex()) {
            val off = kotlin.math.hypot(spot.x - x, spot.y - y)
            if (off <= nearest) {
                nearest = off
                best = i
            }
        }
        if (best != chosen) {
            chosen = best
            invalidate()
        }
        return best
    }

    override fun onDraw(canvas: Canvas) {
        if (spots.isEmpty() || presence <= 0f) return
        val radius = dp(SIZE_DP) / 2f
        for ((i, spot) in spots.withIndex()) {
            val on = i == chosen
            val r = radius * presence.coerceAtMost(1.2f) * (if (on) 1.18f else 1f)
            canvas.drawCircle(spot.x, spot.y + dp(2f), r, shadow)
            disc.color = (if (on) palette.accent else palette.surface).toInt()
            canvas.drawCircle(spot.x, spot.y, r, disc)
            val lit = i == KEEP && kept
            rim.color = (if (on || lit) palette.accent else palette.border).toInt()
            canvas.drawCircle(spot.x, spot.y, r, rim)
            val icon = icons.getOrNull(i) ?: continue
            val half = (dp(ICON_DP) / 2f * presence.coerceAtMost(1f)).toInt()
            icon.setBounds(
                (spot.x - half).toInt(), (spot.y - half).toInt(),
                (spot.x + half).toInt(), (spot.y + half).toInt(),
            )
            icon.setTint((if (on) palette.accentInk else if (lit) palette.accent else palette.ink).toInt())
            icon.draw(canvas)
        }
    }

    companion object {
        /** What is offered, in the order it is drawn. */
        val ACTIONS = listOf("play", "article", "keep")
        private const val KEEP = 2

        /** How far from the finger the actions open, how big each is and its icon, and how
         *  near the finger has to come to one to have chosen it, in dp. */
        const val REACH_DP = 76f
        const val SIZE_DP = 52f

        /** How far the finger goes from where the actions opened before it has left them. */
        const val LEFT_BEHIND_DP = REACH_DP + SIZE_DP * 1.5f
        const val ICON_DP = 24f
        const val TAKES_DP = 40f

        const val RISE_MS = 160L
    }
}
