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

    /** When the held frame was taken, so a caller can wait for one newer than an event. */
    val frameAt: Long get() = takenAt

    /** Throw the held frame away and take a new one at the next opportunity. */
    fun invalidateFrame() {
        takenAt = 0L
        frame?.recycle()
        frame = null
    }

    /**
     * Ask for a fresh frame if the last one is old enough. Returns immediately.
     *
     * A forced capture is one taken with the overlay deliberately out of the way, and the
     * rate limit must not turn it into a reuse of the frame that still has the overlay in
     * it - which is how the colours came back as our own gold.
     */
    fun refreshIfStale(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // A request that never comes back would otherwise wedge this shut for good, so a
        // stale one is abandoned rather than waited on.
        if (pending && SystemClock.uptimeMillis() - requestedAt < REQUEST_TIMEOUT_MS) return
        if (!force && SystemClock.uptimeMillis() - takenAt < MIN_INTERVAL_MS) return
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
     * The colours of one line of text: the surface it sits on, and the ink it is drawn in.
     *
     * A single word is the wrong thing to measure. Short words carry a few dozen glyph
     * pixels against a surface that fills the rest of the rectangle, which is not enough to
     * separate the two, and a word that fails to read once was then left with no colour at
     * all. A whole line carries thousands, and every word on it is drawn in the same colour
     * as its neighbours, so the line is measured and its words all take the answer.
     *
     * The commonest colour is the surface. The ink is not a colour but a cloud: text is
     * antialiased into dozens of shades, none of which holds enough pixels to stand out on
     * its own, so everything far enough from the surface is averaged together instead.
     */
    fun sampleRegion(rect: RectF): WordColors? {
        val bmp = frame ?: return null
        if (SystemClock.uptimeMillis() - takenAt > MAX_AGE_MS) return null
        val counts = histogram(bmp, rect) ?: return null
        return separate(counts)
    }

    /**
     * The colours of the screen as a whole, for a line that could not be read on its own.
     *
     * Text the accessibility tree describes and the capture cannot resolve still has to be
     * covered by something, and the app's own page colour is a far better guess than a
     * palette of ours: it is at worst the wrong shade of the right thing, where a fixed
     * colour is a patch that announces itself.
     */
    fun screenColors(): WordColors? {
        val bmp = frame ?: return null
        if (SystemClock.uptimeMillis() - takenAt > MAX_AGE_MS) return null
        val whole = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        val counts = histogramInFrame(bmp, whole, WIDE_STEP) ?: return null
        val background = counts.maxByOrNull { it.value }?.key ?: return null
        // Ink against the page as a whole is a judgement, not a measurement: whatever reads
        // on it. The average of the distant colours here would be the colour of icons and
        // images as much as of text.
        val light = Color.red(background) * 299 + Color.green(background) * 587 +
            Color.blue(background) * 114 > 140_000
        val ink = if (light) Color.rgb(0x1A, 0x1A, 0x1A) else Color.rgb(0xF2, 0xF2, 0xF2)
        return WordColors(opaque(background), ink)
    }

    private fun separate(counts: Map<Int, Int>): WordColors? {
        val background = counts.maxByOrNull { it.value }?.key ?: return null
        // The ink is the far end of the cloud, not its middle. Averaging everything that is
        // merely distant from the surface mixes the letters with the halo of half-lit pixels
        // around them, which came back as grey for white text and as a muddied version of
        // any colour: the reported ink for white was 0xCCCCCC. Only the colours near the
        // furthest one from the surface are the glyphs themselves.
        val furthest = counts.keys.maxOf { distance(it, background) }
        if (furthest < MIN_CONTRAST) return null
        val floor = maxOf(INK_DISTANCE, (furthest * INK_SHARE).toInt())
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for ((color, count) in counts) {
            if (distance(color, background) < floor) continue
            r += Color.red(color).toLong() * count
            g += Color.green(color).toLong() * count
            b += Color.blue(color).toLong() * count
            n += count
        }
        if (n < MIN_INK_PIXELS) return null
        val ink = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        if (distance(ink, background) < MIN_CONTRAST) return null
        return WordColors(opaque(background), opaque(ink))
    }

    private fun histogram(bmp: Bitmap, rect: RectF): Map<Int, Int>? {
        val where = toFrame(bmp, rect)
        // A paragraph is a large rectangle and the two colours in it are not hiding, so the
        // sample is thinned out as the area grows rather than reading a megapixel per line.
        val area = (where.width() * where.height()).coerceAtLeast(1f)
        val step = kotlin.math.sqrt(area / TARGET_SAMPLES).toInt().coerceIn(STEP, WIDE_STEP)
        return histogramInFrame(bmp, where, step)
    }

    /**
     * Where a rectangle given in the coordinates the accessibility tree reports lands in the
     * captured frame.
     *
     * The two are not always the same space. A display running below its native resolution
     * reports node bounds in the resolution the apps are laid out at while the capture comes
     * back at the size of the panel, and sampling one against the other reads the pixels of
     * whatever happens to sit at the scaled-up position instead of the word.
     */
    private fun toFrame(bmp: Bitmap, rect: RectF): RectF {
        val metrics = service.resources.displayMetrics
        val logical = metrics.widthPixels.toFloat()
        if (logical <= 0f) return rect
        val scale = bmp.width / logical
        if (abs(scale - 1f) < 0.02f) return rect
        return RectF(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)
    }

    private fun histogramInFrame(bmp: Bitmap, rect: RectF, step: Int): Map<Int, Int>? {
        val left = rect.left.toInt().coerceIn(0, bmp.width - 1)
        val top = rect.top.toInt().coerceIn(0, bmp.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, bmp.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, bmp.height)
        val w = right - left
        val h = bottom - top
        if (w < 2 || h < 2) return null
        val pixels = IntArray(w * h)
        runCatching { bmp.getPixels(pixels, 0, w, left, top, w, h) }.getOrElse { return null }
        // Every few pixels in each direction. A line of text is thousands of pixels and the
        // two colours in it are not hiding: reading them all cost more than the rest of a
        // pass put together.
        val counts = HashMap<Int, Int>(256)
        for (row in 0 until h step step) {
            val base = row * w
            for (col in 0 until w step step) {
                val q = pixels[base + col] and 0x00F8F8F8 // the top 5 bits of each channel
                counts[q] = (counts[q] ?: 0) + 1
            }
        }
        return if (counts.isEmpty()) null else counts
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
        const val STEP = 2
        /** For the screen as a whole, where only the commonest colour is wanted. */
        const val WIDE_STEP = 9
        /** About how many pixels are worth reading out of one rectangle. */
        const val TARGET_SAMPLES = 20_000f
        /** Past this the colours may belong to a screen that is no longer there. */
        const val MAX_AGE_MS = 6000L
        /** How far from the surface a pixel has to be before it counts as a letter. */
        const val INK_DISTANCE = 90
        /** And how near the furthest such pixel, so the halo around a letter is left out. */
        const val INK_SHARE = 0.75f
        /** Below this there was no text in the sample, only surface. */
        const val MIN_INK_PIXELS = 12L
        const val MIN_CONTRAST = 60
    }
}
