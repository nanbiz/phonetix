package io.github.nanbiz.phonetix.debug

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.widget.ScrollView
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Scrolls the test page the way hands and platforms actually scroll it.
 *
 * A test that scrolls one way proves the overlay follows that one way. Real movement is
 * never a straight line: a finger accelerates and decelerates, a fling decays, a reader
 * moves the page in short strokes with pauses between them, and every one of those has a
 * different relationship between the moment the overlay reads a position and the moment the
 * page is somewhere else. Each profile below is a shape of movement to hold the overlay
 * against, driven frame by frame so the page reports where it is throughout.
 *
 * The shapes are the ones movement science uses for this. Minimum jerk is the classical
 * model of an unimpeded human reach (Flash and Hogan, 1985): position follows
 * 10t^3 - 15t^4 + 6t^5, smooth at both ends and fastest in the middle. The lognormal is
 * Plamondon's kinematic theory, where the velocity of a rapid stroke is a lognormal in time
 * and position is its integral, which is asymmetric - quick to speed up, long to settle -
 * in the way a real flick is. The rest are the degenerate cases worth keeping honest
 * against: a constant speed nothing biological produces, and pure acceleration.
 */
object ScrollMotion {

    /** How far along the travel is, for a movement that is `t` of the way through its time. */
    fun progress(profile: String, t: Float, random: Random): Float = when (profile) {
        "linear" -> t
        "accelerate" -> t * t
        "decelerate" -> 1f - (1f - t) * (1f - t)
        "minjerk" -> t * t * t * (10f - 15f * t + 6f * t * t)
        "lognormal" -> lognormal(t)
        // A hand does not hold a speed steady; it wavers around the movement it intends.
        "tremor" -> {
            val base = t * t * t * (10f - 15f * t + 6f * t * t)
            (base + (random.nextFloat() - 0.5f) * 0.02f).coerceIn(0f, 1f)
        }
        else -> t
    }

    /**
     * The integral of a lognormal velocity, which is the normal distribution's own integral
     * in log time. Approximated with a logistic, whose error against the true curve is under
     * a percent of the travel and far below anything the eye or this test measures.
     */
    private fun lognormal(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val mu = -1.0f
        val sigma = 0.6f
        val z = (ln(t) - mu) / sigma
        return (1.0 / (1.0 + Math.exp(-1.702 * z))).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Run one movement on the page, reporting what it is doing so a test can pair its own
     * readings with the page's.
     *
     * `strokes` is what makes a scroll a reader's rather than a machine's: the page is moved
     * in that many separate movements with a pause between each, which is how anyone reads
     * down a page, and each pause is a chance for the overlay to settle in the wrong place.
     */
    fun run(
        scroller: ScrollView,
        profile: String,
        distance: Int,
        durationMs: Int,
        strokes: Int,
        seed: Int,
        onDone: () -> Unit = {},
    ) {
        val random = Random(seed)
        val from = scroller.scrollY
        val count = strokes.coerceAtLeast(1)
        // Strokes of uneven length, because a reader's are: a run of identical ones is one
        // movement in disguise.
        val weights = FloatArray(count) { 0.6f + random.nextFloat() }
        val total = weights.sum()
        val legs = ArrayList<Int>(count)
        var used = 0
        for (i in 0 until count) {
            val leg = if (i == count - 1) distance - used
            else (distance * weights[i] / total).roundToInt()
            legs.add(leg)
            used += leg
        }
        val perLeg = (durationMs / count).coerceAtLeast(80)

        Log.d(
            TAG,
            "MOTION start at=${SystemClock.uptimeMillis()} profile=$profile from=$from " +
                "distance=$distance duration=$durationMs strokes=$count seed=$seed",
        )

        fun leg(index: Int, startY: Int) {
            if (index >= legs.size) {
                Log.d(TAG, "MOTION done at=${SystemClock.uptimeMillis()} y=${scroller.scrollY}")
                onDone()
                return
            }
            val travel = legs[index]
            val began = SystemClock.uptimeMillis()
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val elapsed = (SystemClock.uptimeMillis() - began).toFloat()
                    val t = (elapsed / perLeg).coerceIn(0f, 1f)
                    val y = startY + (travel * progress(profile, t, random)).roundToInt()
                    scroller.scrollTo(0, y.coerceAtLeast(0))
                    if (t < 1f) {
                        choreographer.postFrameCallback(this)
                    } else {
                        // The pause between strokes, which is where a reader actually reads.
                        val rest = 90L + (random.nextFloat() * 220f).toLong()
                        scroller.postDelayed({ leg(index + 1, scroller.scrollY) }, rest)
                    }
                }
            })
        }
        leg(0, from)
    }

    /** The profiles a test may ask for, so a suite can enumerate them rather than list them. */
    val PROFILES = listOf("linear", "accelerate", "decelerate", "minjerk", "lognormal", "tremor")

    private const val TAG = "PhonetixTest"
}
