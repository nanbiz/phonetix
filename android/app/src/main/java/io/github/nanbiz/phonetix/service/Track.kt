package io.github.nanbiz.phonetix.service

/**
 * Where a page has been over the last second, and where that says it is now.
 *
 * A scroll is not a constant speed. A finger accelerates, lifts, and the fling that follows
 * decelerates the whole way down to nothing, and every one of those is happening while the
 * layer is being told about the page only every hundred milliseconds. Carrying the words at
 * the speed of the last interval assumes the page is doing now what it was doing then, which
 * through a fling is never true: it is always faster than the news at the start of one and
 * slower at the end.
 *
 * So the news is kept rather than consumed. Each report of where the page has got to is a
 * sample, the samples of the last second are fitted, and what the fit says about this instant
 * is where the words go. Deceleration comes out of the fit rather than being assumed, which is
 * what lets the words stay with the text through the part of a fling where the speed is
 * halving every few frames.
 */
class Track {

    private companion object {
        /** How many samples are kept. A second of a page reporting every hundred milliseconds
         *  is ten, and a page reporting every frame is sixty. */
        const val ROOM = 64

        /** How far back the fit looks.
         *
         *  Long enough to see a fling bend, short enough that the bend it sees is the one
         *  happening now. A fling decelerates hardest at its start, so a parabola fitted across
         *  seven hundred milliseconds of one carries that early curvature into a prediction
         *  about its tail and undershoots: measured against a real decay curve, the fit was
         *  fifty pixels short where carrying the last speed flat was thirty-six. */
        const val WINDOW_MS = 320f

        /** How far past its newest sample the fit may be believed. A fit is a description of
         *  what has happened, and a page that has been silent for longer than this has done
         *  something the samples cannot describe. */
        const val REACH_MS = 250f

        /** Faster than any page travels, which bounds what a fit may claim when its samples
         *  are noise. */
        const val SANE_PX_PER_MS = 12f

        /** The fastest a speed may be taken to be decaying: an eighth of it every
         *  millisecond, which is a fling over in a few frames. */
        const val MOST_DECAY = 0.125f
    }

    private val at = LongArray(ROOM)
    private val where = FloatArray(ROOM)
    private var count = 0
    private var next = 0

    /** The page's position at the newest sample, which is the last thing actually known. */
    var latest = 0f
        private set

    var latestAt = 0L
        private set

    fun clear() {
        count = 0
        next = 0
        latest = 0f
        latestAt = 0L
    }

    /** The page says it is here, now. */
    fun add(when_: Long, position: Float) {
        // Out of order, which a report arriving late can be: it says nothing new about now.
        if (count > 0 && when_ < latestAt) return
        at[next] = when_
        where[next] = position
        next = (next + 1) % ROOM
        if (count < ROOM) count++
        latest = position
        latestAt = when_
    }

    /** How many samples the last window holds, which is what says whether a fit means anything. */
    fun samples(window: Long = WINDOW_MS.toLong()): Int {
        var n = 0
        for (i in 0 until count) {
            if (latestAt - at[slot(i)] <= window) n++
        }
        return n
    }

    /**
     * Where the page is at this moment.
     *
     * A fling is not a parabola. Android runs one as a speed decaying towards nothing, so the
     * curve bends hardest at the start and flattens out, and a parabola fitted across a window
     * of it takes the average bend rather than the bend happening now: measured against a real
     * decay, the parabola came out forty-seven pixels short where simply carrying the last
     * speed on was thirteen. What is fitted here is the shape the page is actually making - a
     * speed and how fast that speed is decaying - which is two numbers from the same samples
     * and describes both a fling and a finger still moving.
     */
    fun at(when_: Long): Float {
        if (count == 0) return 0f
        val ahead = (when_ - latestAt).toFloat()
        // Past what the samples can speak to, the page is left where it was last known to be
        // rather than run on into a guess.
        if (ahead > REACH_MS) return latest
        val (speed, decay) = fit() ?: return latest
        val moved = if (decay <= 0f) {
            speed * ahead
        } else {
            // The distance left in a speed that is decaying at this rate, and how much of it
            // has been covered by now.
            speed / decay * (1f - kotlin.math.exp(-decay * ahead))
        }
        // Backwards is not something a page does at the end of a fling, whatever a fit says.
        return latest + when {
            speed > 0f -> moved.coerceAtLeast(0f)
            speed < 0f -> moved.coerceAtMost(0f)
            else -> 0f
        }
    }

    /** How fast the page is going at this moment, in pixels a millisecond. */
    fun speed(when_: Long): Float {
        val (speed, decay) = fit() ?: return 0f
        val ahead = (when_ - latestAt).toFloat().coerceIn(-WINDOW_MS, REACH_MS)
        return if (decay <= 0f) speed else speed * kotlin.math.exp(-decay * ahead)
    }

    /**
     * The speed at the newest sample and how fast it is decaying, from the samples in the
     * window.
     *
     * The speed comes from a weighted straight-line fit, which is what the samples support
     * directly. The decay comes from how the speed over each interval has been falling: a
     * fling's is constant, so the logarithm of those speeds falls in a straight line and its
     * slope is the rate. A page whose intervals are not falling has no decay to fit and is
     * carried at the speed it has.
     */
    private fun fit(): Pair<Float, Float>? {
        if (count < 2) return null
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var p0 = 0.0
        var p1 = 0.0
        var used = 0
        for (i in 0 until count) {
            val k = slot(i)
            val t = (at[k] - latestAt).toDouble()
            if (-t > WINDOW_MS) continue
            val w = 1.0 + t / WINDOW_MS
            val weight = if (w < 0.3) 0.3 else w
            val y = (where[k] - latest).toDouble()
            s0 += weight
            s1 += weight * t
            s2 += weight * t * t
            p0 += weight * y
            p1 += weight * t * y
            used++
        }
        if (used < 2) return null
        val det = s0 * s2 - s1 * s1
        if (kotlin.math.abs(det) < 1e-9) return null
        val average = ((s0 * p1 - s1 * p0) / det).toFloat()
        val decay = decay(used)
        // With a decay in hand the window's average speed can be corrected to the speed at its
        // newest end. Without one there is nothing to correct it by, and an average over three
        // hundred milliseconds is a description of a moment that is over: a page reports itself
        // about ten times a second, so a window holds three or four samples and the average of
        // them lags a fling badly. The newest interval is then the better answer, being the
        // only part of the window that is about now.
        val here = if (decay > 0f) average * correction(decay) else recent() ?: average
        return sane(here) to decay
    }

    /**
     * How much faster the average speed over the window is than the speed at its newest end,
     * when the speed is decaying at this rate.
     *
     * The average of an exponential over the window against its value at the end of it.
     */
    private fun correction(decay: Float): Float {
        val span = WINDOW_MS
        val average = (1f - kotlin.math.exp(-decay * span)) / (decay * span)
        val ending = kotlin.math.exp(-decay * span)
        // Both are relative to the speed at the start of the window, so their ratio is what
        // the newest end is worth in terms of the average.
        return if (average <= 1e-6f) 1f else (ending / average).coerceIn(0.2f, 1f)
    }

    /** The speed over the newest interval, which is the least stale thing the samples hold. */
    private fun recent(): Float? {
        if (count < 2) return null
        val newer = slot(0)
        val older = slot(1)
        val span = (at[newer] - at[older]).toFloat()
        if (span <= 0f) return null
        return (where[newer] - where[older]) / span
    }

    /** The rate the interval speeds are falling at, or nothing when they are not. */
    private fun decay(used: Int): Float {
        if (used < 4) return 0f
        // The speed over each interval, dated at the middle of it.
        var n = 0
        val mid = DoubleArray(ROOM)
        val fast = DoubleArray(ROOM)
        for (i in 0 until count - 1) {
            val newer = slot(i)
            val older = slot(i + 1)
            val span = (at[newer] - at[older]).toDouble()
            if (span <= 0.0) continue
            val t = (at[older] - latestAt).toDouble() + span / 2.0
            if (-t > WINDOW_MS) continue
            val v = (where[newer] - where[older]).toDouble() / span
            if (v == 0.0) continue
            mid[n] = t
            fast[n] = v
            n++
        }
        if (n < 3) return 0f
        // All of one direction, or this is not one movement.
        val forward = fast[0] > 0.0
        for (i in 0 until n) if ((fast[i] > 0.0) != forward) return 0f
        // A straight line through the logarithm of the speeds: a constant decay makes one.
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var p0 = 0.0
        var p1 = 0.0
        for (i in 0 until n) {
            val t = mid[i]
            val y = kotlin.math.ln(kotlin.math.abs(fast[i]))
            s0 += 1.0
            s1 += t
            s2 += t * t
            p0 += y
            p1 += t * y
        }
        val det = s0 * s2 - s1 * s1
        if (kotlin.math.abs(det) < 1e-9) return 0f
        val slope = (s0 * p1 - s1 * p0) / det
        // A speed rising with time is a movement speeding up, which has no decay to carry
        // forward; the straight line already describes it.
        val rate = -slope
        return if (rate.isFinite() && rate > 0.0) rate.toFloat().coerceAtMost(MOST_DECAY) else 0f
    }

    private fun sane(speed: Float): Float =
        speed.coerceIn(-SANE_PX_PER_MS, SANE_PX_PER_MS)

    private fun slot(i: Int): Int = ((next - 1 - i) % ROOM + ROOM) % ROOM
}
