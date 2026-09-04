package io.github.nanbiz.phonetix.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Display
import java.util.concurrent.Executor
import kotlin.math.abs

/** The two colours a word is actually drawn in: its background, and its ink. */
data class WordColors(val background: Int, val ink: Int)

/**
 * Reads the colours a word is really drawn in, by looking at the screen.
 *
 * Accessibility describes structure and text; it says nothing whatever about colour. So the
 * only way to make a replacement look like the thing it replaces is to capture the display
 * and sample the word's own pixels. The platform rate-limits that capture, and colours
 * change far more slowly than positions do, so one frame is kept and sampled for every word
 * until it goes stale.
 */
class ScreenSampler(private val service: AccessibilityService, private val executor: Executor) {

    @Volatile
    private var frame: Bitmap? = null
    @Volatile
    private var takenAt = 0L
    @Volatile
    private var pending = false
    @Volatile
    private var requestedAt = 0L

    val hasFrame: Boolean get() = frame != null

    /** Ask for a fresh frame if the last one is old enough. Returns immediately. */
    fun refreshIfStale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // A request that never comes back would otherwise wedge this shut for good, so a
        // stale one is abandoned rather than waited on.
        if (pending && SystemClock.uptimeMillis() - requestedAt < REQUEST_TIMEOUT_MS) return
        if (SystemClock.uptimeMillis() - takenAt < MIN_INTERVAL_MS) return
        pending = true
        requestedAt = SystemClock.uptimeMillis()
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bmp = runCatching {
                            val hw = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            // The wrapped bitmap is backed by the buffer, which must be
                            // released; take a software copy that outlives it.
                            val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            hw.close()
                            copy
                        }.getOrNull()
                        if (bmp != null) {
                            frame?.recycle()
                            frame = bmp
                            takenAt = SystemClock.uptimeMillis()
                        }
                        pending = false
                    }

                    override fun onFailure(errorCode: Int) {
                        // Worth naming: an app that sets FLAG_SECURE can never be sampled,
                        // and that is a permanent answer rather than a rate limit.
                        android.util.Log.w("Phonetix", "screenshot refused, code=$errorCode")
                        takenAt = SystemClock.uptimeMillis()
                        pending = false
                    }
                },
            )
        }.onFailure { android.util.Log.w("Phonetix", "screenshot: threw", it); pending = false }
    }

    /**
     * The colours of a whole screenful of words at once.
     *
     * Sampling each word separately sounds more faithful and is in fact less so: the frame
     * in hand may be a moment older than the positions, and a rectangle read against a
     * frame that has since scrolled samples whatever used to be there. Text within one app
     * is drawn in one or two colours anyway, so every chosen word is pooled into a single
     * histogram and the pair that comes out is used for all of them - steady, and immune to
     * being a frame behind.
     */
    fun sampleAll(rects: List<RectF>, exclude: List<RectF>): WordColors? {
        val bmp = frame ?: return null
        if (SystemClock.uptimeMillis() - takenAt > MAX_AGE_MS) return null
        val counts = HashMap<Int, Int>(256)
        var total = 0
        for (rect in rects) {
            val left = rect.left.toInt().coerceIn(0, bmp.width - 1)
            val top = rect.top.toInt().coerceIn(0, bmp.height - 1)
            val right = rect.right.toInt().coerceIn(left + 1, bmp.width)
            val bottom = rect.bottom.toInt().coerceIn(top + 1, bmp.height)
            val w = right - left
            val h = bottom - top
            if (w < 2 || h < 2) continue
            val pixels = IntArray(w * h)
            runCatching { bmp.getPixels(pixels, 0, w, left, top, w, h) }.getOrElse { continue }
            // Every third pixel in each direction. A line of text is thousands of pixels and
            // the two colours in it are not hiding: reading them all cost more than the rest
            // of a pass put together.
            for (row in 0 until h step STEP) {
                val y = top + row
                for (col in 0 until w step STEP) {
                    val x = left + col
                    // The capture contains our own transcriptions, drawn over these very
                    // words. Sampling them would measure our own colours and lock onto
                    // them, so the pixels we put there are skipped.
                    if (isCovered(x.toFloat(), y.toFloat(), exclude)) continue
                    val q = pixels[row * w + col] and 0x00F8F8F8
                    counts[q] = (counts[q] ?: 0) + 1
                    total++
                }
            }
        }
        if (total == 0 || counts.isEmpty()) return null
        val background = counts.maxByOrNull { it.value }?.key ?: return null

        // The ink is not one colour. Text is antialiased into dozens of shades, so no single
        // one holds enough of a text node's pixels to stand out - the first attempt at this
        // looked for the commonest distant colour and found nothing but background. Take the
        // average of everything far enough from the surface instead, which is the glyphs.
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for ((color, count) in counts) {
            if (distance(color, background) < INK_DISTANCE) continue
            r += Color.red(color).toLong() * count
            g += Color.green(color).toLong() * count
            b += Color.blue(color).toLong() * count
            n += count
        }
        if (n < MIN_INK_PIXELS) return null
        val ink = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        return WordColors(opaque(background), opaque(ink))
    }

    /**
     * The background and ink of the word in this rectangle.
     *
     * The most common colour under a word is the surface it sits on, and the colour that
     * lies furthest from it is the ink that spells it out; anything with too few pixels to
     * matter is noise from antialiasing and is ignored. Returns null when the frame is
     * missing or the rectangle holds nothing that reads as text, in which case the caller
     * keeps its own palette rather than inventing a colour.
     */
    fun sample(rect: RectF): WordColors? {
        val bmp = frame ?: return null
        if (SystemClock.uptimeMillis() - takenAt > MAX_AGE_MS) return null

        val left = rect.left.toInt().coerceIn(0, bmp.width - 1)
        val top = rect.top.toInt().coerceIn(0, bmp.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, bmp.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, bmp.height)
        val w = right - left
        val h = bottom - top
        if (w < 2 || h < 2) return null

        // Quantised histogram: exact colours are useless here because text is antialiased
        // into hundreds of shades, and the two that matter are the two big clusters.
        val counts = HashMap<Int, Int>(64)
        val pixels = IntArray(w * h)
        runCatching { bmp.getPixels(pixels, 0, w, left, top, w, h) }.getOrElse { return null }
        for (p in pixels) {
            val q = p and 0x00F8F8F8 // keep the top 5 bits of each channel
            counts[q] = (counts[q] ?: 0) + 1
        }
        if (counts.isEmpty()) return null

        val background = counts.maxByOrNull { it.value }?.key ?: return null
        val floor = (pixels.size * MIN_INK_SHARE).toInt().coerceAtLeast(2)
        var ink = 0
        var best = -1
        for ((color, n) in counts) {
            if (n < floor) continue
            val d = distance(color, background)
            if (d > best) { best = d; ink = color }
        }
        if (best < MIN_CONTRAST) return null
        return WordColors(opaque(background), opaque(ink))
    }

    private fun isCovered(x: Float, y: Float, exclude: List<RectF>): Boolean {
        for (r in exclude) if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) return true
        return false
    }

    private fun opaque(c: Int) = c or (0xFF shl 24)

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))

    fun destroy() {
        frame?.recycle()
        frame = null
    }

    private companion object {
        /** The platform limits how often a capture is allowed; stay well inside it. */
        const val MIN_INTERVAL_MS = 900L
        const val REQUEST_TIMEOUT_MS = 2500L
        const val STEP = 3
        /** Past this the colours may belong to a screen that is no longer there. */
        const val MAX_AGE_MS = 6000L
        /** How far from the surface a pixel has to be before it counts as a letter. */
        const val INK_DISTANCE = 90
        /** Below this there was no text in the sample, only surface. */
        const val MIN_INK_PIXELS = 12L
        const val MIN_INK_SHARE = 0.04
        const val MIN_CONTRAST = 60
    }
}
