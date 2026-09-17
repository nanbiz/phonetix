package io.github.nanbiz.phonetix.service

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the service believes, written out where it can be read from outside the app.
 *
 * A bug in what the overlay draws is a bug in what it believes: which words it thinks are on
 * the screen, where it thinks they are, which one it thinks the mark is over, and what the
 * card that is up is actually about. None of that is visible from a screenshot - an overlay
 * window is invisible to a tool reading the screen, and a log line says only what one pass
 * decided. So the whole of it goes into one file, on demand, and is pulled off the device.
 *
 * Asked for with a broadcast, so it works on a phone in a reader's hand at the moment the
 * thing is going wrong:
 *
 *     adb shell am broadcast -a io.github.nanbiz.phonetix.DUMP -p io.github.nanbiz.phonetix
 *     scripts/proofread/state.py            (sends it, pulls the file, prints it)
 *
 * It grows: when a dump turns out to be missing what an investigation needed, the field goes
 * in here rather than into a one-off log line.
 */
object StateDump {

    const val ACTION = "io.github.nanbiz.phonetix.DUMP"

    /** Turn the tree probe on or off from outside, for one investigation on a real phone:
     *  `am broadcast -a io.github.nanbiz.phonetix.PROBE --ez on true`. */
    const val PROBE = "io.github.nanbiz.phonetix.PROBE"

    /** How many dumps are kept, so a phone left with this on does not fill up. */
    private const val KEEP = 20

    fun write(context: Context, state: JSONObject): File {
        val dir = File(context.filesDir, "state").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "state-$stamp.json")
        file.writeText(state.toString(2))
        val kept = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        for (old in kept.dropLast(KEEP)) old.delete()
        android.util.Log.d("Phonetix", "STATEDUMP ${file.absolutePath}")
        return file
    }

    fun rect(r: RectF?): JSONObject? = r?.let {
        JSONObject()
            .put("left", it.left.toInt())
            .put("top", it.top.toInt())
            .put("right", it.right.toInt())
            .put("bottom", it.bottom.toInt())
    }

    fun rect(r: android.graphics.Rect?): JSONObject? = r?.let {
        JSONObject()
            .put("left", it.left)
            .put("top", it.top)
            .put("right", it.right)
            .put("bottom", it.bottom)
    }

    fun box(b: io.github.nanbiz.phonetix.core.WordBox?): JSONObject? = b?.let {
        JSONObject()
            .put("word", it.word)
            .put("drawn", it.ipa)
            .put("full", it.full)
            .put("language", it.language)
            .put("before", it.before)
            .put("rect", rect(it.rect))
    }

    fun boxes(list: List<io.github.nanbiz.phonetix.core.WordBox>, limit: Int = 200): JSONArray {
        val out = JSONArray()
        for (b in list.take(limit)) out.put(box(b))
        return out
    }
}
