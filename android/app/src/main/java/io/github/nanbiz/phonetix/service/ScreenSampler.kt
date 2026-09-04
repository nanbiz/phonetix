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

    val hasFrame: Boolean get() = frame != null

    /** Ask for a fresh frame if the last one is old enough. Returns immediately. */
    fun refreshIfStale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (pending) return
        if (SystemClock.uptimeMillis() - takenAt < MIN_INTERVAL_MS) return
        pending = true
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
                        // Usually just the platform's own rate limit; try again later.
                        takenAt = SystemClock.uptimeMillis()
                        pending = false
                    }
                },
            )
        }.onFailure { pending = false }
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
    fun sampleAll(rects: List<RectF>): WordColors? {
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
            for (p in pixels) {
                val q = p and 0x00F8F8F8
                counts[q] = (counts[q] ?: 0) + 1
            }
            total += pixels.size
        }
        if (total == 0 || counts.isEmpty()) return null
        val background = counts.maxByOrNull { it.value }?.key ?: return null
        val floor = (total * MIN_INK_SHARE).toInt().coerceAtLeast(2)
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
        /** Past this the colours may belong to a screen that is no longer there. */
        const val MAX_AGE_MS = 6000L
        const val MIN_INK_SHARE = 0.04
        const val MIN_CONTRAST = 60
    }
}
