package io.github.nanbiz.phonetix.service

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nanbiz.phonetix.core.Answer
import io.github.nanbiz.phonetix.core.Languages
import io.github.nanbiz.phonetix.core.Wording
import io.github.nanbiz.phonetix.ui.Tokens

/**
 * The word a reader is looking for, asked over whatever they are reading.
 *
 * Everything else here answers a word somebody else wrote; this one answers a word that
 * exists only in the reader's head. It is a panel over the screen rather than a screen of the
 * app's own: asking for a word in the middle of a conversation should not put the conversation
 * away, which is what opening an activity does. Taplex asked this way and this is that panel.
 *
 * What is typed is asked for when the keyboard's search key is pressed; the answer appears
 * under the field, in the card the rest of the product answers with.
 */
class AskPanel(context: Context, private val palette: Tokens.Palette) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float) = (value * density).toInt()

    /** What will be answered, which is the whole question. */
    private val prompt = TextView(context).apply {
        setTextColor(palette.inkMuted.toInt())
        textSize = 13f
    }

    /** The languages a word can be asked for in, where there is more than one to choose. */
    private val chipRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val chipStrip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chipRow)
        visibility = GONE
    }

    val field = EditText(context).apply {
        setTextColor(palette.ink.toInt())
        setHintTextColor(palette.inkFaint.toInt())
        hint = Wording.says["say-placeholder"].orEmpty()
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        isSingleLine = true
        // The line a bare field is ruled with is the platform's; the box around it is the
        // field here, so the field itself draws nothing and one edge runs down the panel.
        background = null
        setPadding(0, dp(10f), 0, dp(10f))
        textSize = 16f
    }

    /** Says the phrase instead of typing it, at the end of the field it fills. */
    private val mic = TextView(context).apply {
        text = "🎤"
        textSize = 18f
        gravity = Gravity.CENTER
    }

    private val answer = OverlayHost(context)

    /** What is being said while the phone is thinking, or when it has nothing. */
    private val note = TextView(context).apply {
        setTextColor(palette.inkMuted.toInt())
        textSize = 13f
        visibility = GONE
    }

    private val asking = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = Tokens.Scale.radiusButton * density
            setColor(palette.surfaceRaised.toInt())
            setStroke(dp(Tokens.Scale.borderWidth), palette.border.toInt())
        }
        setPadding(dp(14f), 0, dp(4f), 0)
        addView(field, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(mic, LayoutParams(dp(40f), dp(40f)))
    }

    /** Called with what was typed, when it is asked for. */
    var onSubmit: (String) -> Unit = {}

    /** Starts or stops listening, from the microphone on the panel. */
    var onDictate: (() -> Unit)? = null
        set(value) {
            field = value
            mic.visibility = if (value == null) GONE else VISIBLE
            mic.setOnClickListener { value?.invoke() }
        }

    /** The panel is being taken down, however that was asked for. */
    var onClose: () -> Unit = {}

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = Tokens.Scale.radiusCard * density
            setColor(palette.surface.toInt())
            setStroke(dp(Tokens.Scale.borderWidth), palette.border.toInt())
        }
        val pad = dp(14f)
        setPadding(pad, pad, pad, pad)
        addView(prompt)
        addView(chipStrip)
        addView(
            asking,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            },
        )
        addView(
            note,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            },
        )
        addView(
            answer.view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            },
        )
        answer.shown()
        field.setOnEditorActionListener { _, actionId, event ->
            // A key reports its press and its release, and asking twice cancels the first
            // answer on its way back, so only the press counts.
            val asked = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (asked) {
                field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onSubmit)
            }
            true
        }
    }

    /** Which language the answer will come back in, since that is the whole question. */
    fun askFor(language: String) {
        prompt.text = "${Wording.row("say").name} · ${Languages.english(language)}"
    }

    /** What has been heard so far, put in the field as if it had been typed. */
    fun heard(text: String) {
        field.setText(text)
        field.setSelection(text.length)
    }

    /** Whether the phone is listening right now, which the microphone shows. */
    fun listening(on: Boolean) {
        mic.alpha = if (on) 1f else 0.7f
        prompt.alpha = if (on) 0.6f else 1f
    }

    /**
     * The languages there is a dictionary to answer in. With one there is nothing to choose
     * and the row stays hidden; with more, each is a chip and the chosen one is lit.
     */
    fun setLanguages(languages: List<String>, chosen: String, onPick: (String) -> Unit) {
        chipRow.removeAllViews()
        if (languages.size < 2) {
            chipStrip.visibility = GONE
            return
        }
        chipStrip.visibility = VISIBLE
        for (code in languages) {
            val lit = code == chosen
            val chip = TextView(context).apply {
                text = Languages.english(code)
                textSize = 13f
                setTypeface(typeface, if (lit) Typeface.BOLD else Typeface.NORMAL)
                setTextColor((if (lit) palette.accentInk else palette.ink).toInt())
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                background = GradientDrawable().apply {
                    cornerRadius = Tokens.Scale.radiusChip * density
                    setColor((if (lit) palette.accent else palette.chipBg).toInt())
                }
                setOnClickListener { onPick(code) }
            }
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = dp(6f)
                    topMargin = dp(6f)
                    bottomMargin = dp(6f)
                },
            )
        }
    }

    /** Something to read while the engine opens a model for a direction nobody was reading. */
    fun saying(text: String) {
        note.text = text
        note.visibility = if (text.isEmpty()) GONE else VISIBLE
        if (text.isNotEmpty()) answer.view.setContent {}
    }

    /** The word that came back, in the card the rest of the product answers with. */
    fun show(said: Answer, onPlay: () -> Unit) {
        note.visibility = GONE
        answer.view.setContent {
            io.github.nanbiz.phonetix.ui.AnswerCard(
                answer = said,
                palette = palette,
                onPlay = onPlay,
            )
        }
    }

    /** Taken down by the way back, which arrives as a key on older phones. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onClose()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** A touch anywhere but the panel puts it away: the question was not asked. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            onClose()
            return true
        }
        return super.onTouchEvent(event)
    }

    /** Let go of the composition, which a view torn down after its lifecycle ends would leak. */
    fun done() {
        answer.hidden()
    }
}

private fun Long.toInt(): Int = this.toInt()

/** A view is what a window holds; this is what it is put in one as. */
fun askPanelView(panel: AskPanel): View = panel
