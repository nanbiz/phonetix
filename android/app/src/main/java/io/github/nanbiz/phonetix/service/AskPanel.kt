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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.nanbiz.phonetix.core.Languages
import io.github.nanbiz.phonetix.core.Wording
import io.github.nanbiz.phonetix.ui.Tokens

/**
 * A word or a phrase translated between the reader's language and the one they are learning,
 * asked over whatever they are reading.
 *
 * A panel over the screen rather than a screen of the app's own: asking in the middle of a
 * conversation should not put the conversation away, which is what opening an activity does.
 *
 * The two languages head it, with an arrow between them for the way the question is being
 * answered: it is worked out from what is typed, so a word in either language comes back in
 * the other, and the arrow turns it round where it was worked out wrong. Each language opens
 * a list to choose another. A word is answered with everything it can mean, the commonest
 * first; a phrase with its translation.
 */
class AskPanel(context: Context, private val palette: Tokens.Palette) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float) = (value * density).toInt()

    private companion object {
        /** The square the microphone is given, and the drawing set inside it, in dp. */
        const val TOUCH_DP = 40f
        const val ICON_DP = 22f
    }

    /**
     * Which language the answer comes back in.
     *
     * A button that opens a list, rather than a strip of fifty chips in alphabetical order: a
     * reader asks in two or three languages and had to hunt past forty-seven others to reach
     * one of them. The list is searchable, and the ones they have asked in lately are at the
     * top of it.
     */
    private fun chip() = TextView(context).apply {
        textSize = 15f
        setTextColor(palette.ink.toInt())
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
        background = GradientDrawable().apply {
            cornerRadius = dp(9f).toFloat()
            setColor(palette.surface.toInt())
        }
    }

    /** The reader's own language, and the one they are learning. */
    private val mineChip = chip()
    private val learningChip = chip()

    /** Which way the question is answered, and the way to turn it round. */
    private val arrow = TextView(context).apply {
        textSize = 18f
        setTextColor(palette.accent.toInt())
        gravity = Gravity.CENTER
    }

    /** The languages as one control: the two of them and the arrow, or the one typed in. */
    private val pair = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(palette.chipBg.toInt())
        }
    }

    /** The list itself, over the panel, filtered by what is typed into it. */
    private val search = EditText(context).apply {
        setTextColor(palette.ink.toInt())
        setHintTextColor(palette.inkFaint.toInt())
        hint = Wording.says["search"].orEmpty()
        isSingleLine = true
        background = null
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        textSize = 15f
    }
    private val listed = LinearLayout(context).apply { orientation = VERTICAL }
    private val chooser = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            android.widget.ScrollView(context).apply { addView(listed) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(260f)),
        )
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

    /**
     * Says the phrase instead of typing it. It sits at the end of the field it fills, which
     * is where a microphone means "speak this" rather than anything about the panel.
     *
     * A drawn microphone rather than the emoji for one: an emoji is whatever face the
     * device's font gives it, at its own weight and in its own colours, beside controls that
     * are all drawn in the palette.
     */
    private val mic = android.widget.ImageView(context).apply {
        setImageResource(io.github.nanbiz.phonetix.R.drawable.ic_mic)
        imageTintList = android.content.res.ColorStateList.valueOf(palette.inkMuted.toInt())
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val inset = dp((TOUCH_DP - ICON_DP) / 2f)
        setPadding(inset, inset, inset, inset)
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
        minimumHeight = dp(48f)
        background = GradientDrawable().apply {
            cornerRadius = dp(24f).toFloat()
            setColor(palette.pageBg.toInt())
        }
        setPadding(dp(18f), 0, dp(4f), 0)
        addView(field, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(mic, LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
    }

    /** Called with what was typed, as it is typed. */
    var onSubmit: (String) -> Unit = {}

    /** The ask that has not happened yet, replaced by every keystroke. */
    private var pending: Runnable = Runnable {}

    /** Starts or stops listening, from the microphone on the panel. */
    var onDictate: (() -> Unit)? = null
        set(value) {
            field = value
            mic.visibility = if (value == null) GONE else VISIBLE
            mic.setOnClickListener { value?.invoke() }
        }

    /** The panel is being taken down, however that was asked for. */
    var onClose: () -> Unit = {}

    /** The mark, which opens the app: the panel stays about the word being asked for, and
     *  everything else is set where everything else is set. */
    private val mark = android.widget.ImageView(context).apply {
        setImageResource(io.github.nanbiz.phonetix.R.mipmap.ic_mark)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val inset = dp(8f)
        setPadding(inset, inset, inset, inset)
    }

    /** The top line: which language the answer comes back in, and the way through to the app.
     *  One row rather than two, because a panel of two rows has nothing to spare. */
    private val header = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        pair.addView(mineChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        pair.addView(arrow, LayoutParams(dp(40f), dp(38f)))
        pair.addView(learningChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(pair, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(android.view.View(context), LayoutParams(0, 0, 1f))
        addView(mark, LayoutParams(dp(44f), dp(44f)))
    }

    /** Opens the app, from the mark on the panel. */
    var onOpenApp: (() -> Unit)? = null
        set(value) {
            field = value
            mark.setOnClickListener { value?.invoke() }
        }

    init {
        // The owners a composition needs are looked for up the view tree from the window's
        // root, which is this panel rather than the view inside it: set only on that view, a
        // card drawn in here brought the service down with "ViewTreeLifecycleOwner not found".
        setViewTreeLifecycleOwner(answer)
        setViewTreeViewModelStoreOwner(answer)
        setViewTreeSavedStateRegistryOwner(answer)
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            setColor(palette.surface.toInt())
        }
        elevation = dp(8f).toFloat()
        val pad = dp(16f)
        setPadding(pad, pad, pad, pad)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(chooser, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(edited: android.text.Editable?) {
                searching(edited?.toString().orEmpty())
            }

            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        addView(
            asking,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12f)
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
                topMargin = dp(14f)
            },
        )
        answer.shown()
        // Answered as it is typed rather than when a key is pressed: the question is short,
        // the answer is wanted while it is being written, and pressing something to ask is a
        // step a reader should not have to find. The last keystroke wins - what is being
        // typed now is not a question yet.
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(edited: android.text.Editable?) {
                val asked = edited?.toString().orEmpty().trim()
                removeCallbacks(pending)
                pending = Runnable { if (asked.isNotEmpty()) onSubmit(asked) }
                postDelayed(pending, 450)
            }

            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
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

    /** What has been heard so far, put in the field as if it had been typed. */
    fun heard(text: String) {
        field.setText(text)
        field.setSelection(text.length)
    }

    /** Whether the phone is listening right now, which the microphone shows. */
    fun listening(on: Boolean) {
        mic.imageTintList = android.content.res.ColorStateList.valueOf(
            (if (on) palette.accent else palette.inkMuted).toInt()
        )
    }

    /**
     * The two languages, which way the question is being answered, and what choosing another
     * language or turning the arrow does. [languages] is every language, in the order worth
     * offering.
     */
    fun setPair(
        languages: List<String>,
        mine: String,
        learning: String,
        forward: Boolean,
        onPick: (mine: Boolean, code: String) -> Unit,
        onTurn: () -> Unit,
    ) {
        arrow.visibility = VISIBLE
        learningChip.visibility = VISIBLE
        mineChip.text = Languages.english(mine)
        learningChip.text = if (learning.isBlank()) {
            Wording.says["choose-language"].orEmpty()
        } else {
            Languages.english(learning)
        }
        arrow.text = if (forward) "→" else "←"
        arrow.setOnClickListener { onTurn() }
        fun opens(chip: TextView, isMine: Boolean, chosen: String) {
            chip.setOnClickListener {
                if (chooser.visibility == VISIBLE) {
                    closeChooser()
                } else {
                    chooser.visibility = VISIBLE
                    search.setText("")
                    val pick = { code: String -> onPick(isMine, code) }
                    searching = { typed -> fill(languages, chosen, pick, typed) }
                    fill(languages, chosen, pick, "")
                    search.requestFocus()
                }
            }
        }
        opens(mineChip, true, mine)
        opens(learningChip, false, learning)
    }

    /**
     * The one language a word is typed in, where nothing is translated: the panel then answers
     * with how the word is said and what it means, in the language it is in.
     */
    fun setSingle(languages: List<String>, typedIn: String, onPick: (String) -> Unit) {
        arrow.visibility = GONE
        learningChip.visibility = GONE
        mineChip.text = Languages.english(typedIn)
        mineChip.setOnClickListener {
            if (chooser.visibility == VISIBLE) {
                closeChooser()
            } else {
                chooser.visibility = VISIBLE
                search.setText("")
                searching = { typed -> fill(languages, typedIn, onPick, typed) }
                fill(languages, typedIn, onPick, "")
                search.requestFocus()
            }
        }
    }

    /** Which way the question is being answered, once it has been worked out. */
    fun direction(forward: Boolean) {
        arrow.text = if (forward) "→" else "←"
    }

    /** What the filter does, kept so a keystroke reaches the list that is open. */
    private var searching: (String) -> Unit = {}

    private fun fill(
        languages: List<String>,
        chosen: String,
        onPick: (String) -> Unit,
        typed: String,
    ) {
        listed.removeAllViews()
        val wanted = typed.trim().lowercase()
        for (code in languages) {
            val name = Languages.english(code)
            if (wanted.isNotEmpty() && !name.lowercase().contains(wanted)) continue
            val row = TextView(context).apply {
                text = name
                textSize = 15f
                val lit = code == chosen
                setTextColor((if (lit) palette.accent else palette.ink).toInt())
                setTypeface(typeface, if (lit) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                setOnClickListener {
                    closeChooser()
                    onPick(code)
                    // Back to the question, with the keyboard, since a language is chosen to
                    // type in it.
                    field.requestFocus()
                    context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.showSoftInput(field, 0)
                }
            }
            listed.addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
        if (listed.childCount == 0) {
            listed.addView(
                TextView(context).apply {
                    text = Wording.says["nothing-found"].orEmpty()
                    textSize = 14f
                    setTextColor(palette.inkMuted.toInt())
                    setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /** Puts the list away, and the keyboard it opened with it. */
    private fun closeChooser() {
        chooser.visibility = GONE
        search.setText("")
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    /** Something to read while the engine opens a model for a direction nobody was reading. */
    fun saying(text: String) {
        note.text = text
        note.visibility = if (text.isEmpty()) GONE else VISIBLE
        if (text.isNotEmpty()) answer.view.setContent {}
    }

    /** Everything a word can mean in the other language. */
    fun showMeanings(meanings: List<io.github.nanbiz.phonetix.ui.Meant>, sound: Boolean) {
        note.visibility = GONE
        answer.view.setContent {
            io.github.nanbiz.phonetix.ui.MeaningsList(meanings, palette, sound)
        }
    }

    /** A word in its own language: each way it is read, said and defined. */
    fun showDefined(word: String, readings: List<io.github.nanbiz.phonetix.ui.Defined>, sound: Boolean) {
        note.visibility = GONE
        answer.view.setContent {
            io.github.nanbiz.phonetix.ui.DefinedList(word, readings, palette, sound)
        }
    }

    /** A phrase translated, and how it is said where that is asked for. */
    fun showLine(text: String, ipa: String?) {
        note.visibility = GONE
        answer.view.setContent {
            io.github.nanbiz.phonetix.ui.TranslatedLine(text, ipa, palette)
        }
    }

    /** Taken down by the way back, which arrives as a key on older phones. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            // The way back takes away the one thing that is open, the list before the panel:
            // a reader who opened the list to look at it expects to get back to the question.
            if (chooser.visibility == VISIBLE) {
                closeChooser()
                return true
            }
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
