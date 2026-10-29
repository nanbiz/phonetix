package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nanbiz.phonetix.core.Accents
import io.github.nanbiz.phonetix.core.Answer
import io.github.nanbiz.phonetix.core.Interface
import io.github.nanbiz.phonetix.core.Languages
import io.github.nanbiz.phonetix.core.Wording
import io.github.nanbiz.phonetix.core.SymbolInfo

/** Told what a piece of a card says and where it landed. */
typealias Reporter = (String, androidx.compose.ui.geometry.Rect) -> Unit

/** Report this piece once it has been placed. */
internal fun Modifier.reported(text: String, report: Reporter?): Modifier =
    if (report == null) {
        this
    } else {
        onGloballyPositioned { report(text, it.boundsInWindow()) }
    }

/**
 * The answer surface: what a reader gets when they stop at a word.
 *
 * The top row names the word under the finger and says what it is being read as and where the
 * answer came from, because everything under it is only as good as that. Then what it means,
 * then how it is said - and the pronunciation is the interactive part: every symbol is a
 * button, and the sound a reader asks about is described on [SoundLine], which is always there
 * and always the same height, so exploring a transcription never resizes the card.
 *
 * Every colour and size comes from [Tokens], and the rows are the ones the extension draws in
 * the same order, both generated from the one surface page, so the two cannot drift apart.
 */
@Composable
fun AnswerCard(
    answer: Answer,
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    onSymbol: (String) -> Unit = {},
    /** Whether what the play button plays is a person rather than a machine. */
    recorded: Boolean = false,
    /** The accent this language is being read in, which the card names beside the word. */
    accent: String = "",
    /** The sound the detail line is describing, where the reader has tapped one. */
    opened: SymbolInfo? = null,
    /** A picture of the mouth making that sound, drawn by the caller that could fetch it. */
    diagram: (@Composable () -> Unit)? = null,
    onPlay: () -> Unit = {},
    /** Play a recording of one sound, which is a file rather than a synthesised voice. */
    onPlaySymbol: () -> Unit = {},
    /**
     * Where the word this card is about sits along the card's own width, and which side of the
     * card it is on. Nothing where the card is not anchored to a word, and then no arrow is
     * drawn: a card that opened between two words was a card about either of them.
     */
    pointsAt: Dp? = null,
    pointsDown: Boolean = false,
    /** Somewhere to send a reader who wants the whole entry. */
    onOpen: (String) -> Unit = {},
    /** Where each piece of the card ended up, once it has been laid out.
     *
     *  An overlay window is invisible to a tool reading the screen, so without this a check can
     *  know the card was asked for and never that it drew anything or where its buttons are.
     *  The bounds come from the layout rather than from what was intended, which is the whole
     *  point of asking. */
    report: Reporter? = null,
    /** Whether the card scrolls within whatever height it is given. */
    scrolls: Boolean = false,
) {
    Box(modifier.fillMaxWidth()) {
        // The arrow, outside the card's own rounded box so the card can keep clipping its
        // corners. It is drawn first and the card over it, so the seam where they meet is the
        // card's edge rather than a line across the arrow.
        if (pointsAt != null) {
            Arrow(
                at = pointsAt,
                down = pointsDown,
                palette = palette,
                modifier = Modifier.align(if (pointsDown) Alignment.BottomStart else Alignment.TopStart),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = if (pointsAt != null && !pointsDown) ARROW else 0.dp,
                    bottom = if (pointsAt != null && pointsDown) ARROW else 0.dp,
                )
                .clip(RoundedCornerShape(Tokens.Scale.radiusCard.dp))
                .background(Color(palette.surface))
                // Scrolls where the window holding it is shorter than it is: a card is cut
                // to the room beside its word rather than laid over the word. Only where it
                // is asked for - inside a page that scrolls itself, a second scroll is one
                // measured with no height at all.
                .then(
                    if (scrolls) {
                        Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
                    } else {
                        Modifier
                    },
                )
                .padding(Tokens.Scale.space4.dp),
            verticalArrangement = Arrangement.spacedBy(Tokens.Scale.space3.dp),
        ) {
            // The word the reader is on, whatever else the card could or could not find out.
            TopRow(answer, accent, palette, report, onOpen)
            if (!answer.found) {
                Nothing(answer, palette, report)
                return@Column
            }
            if (!chooses(answer)) {
                if (!answer.headline.isNullOrBlank()) Headline(answer, palette, report)
            } else {
                // Nothing leads: the reader is choosing between the readings below, and a headline
                // would be the card choosing for them.
                androidx.compose.material3.Text(
                    text = "${answer.spelling} is more than one word",
                    color = Color(palette.inkMuted),
                    fontSize = Tokens.Scale.fontSizeBody.sp,
                )
            }
            if (answer.ipa.isNotEmpty()) {
                Pronunciation(answer, palette, onSymbol, recorded, onPlay, opened, report)
            }
            // Only where there are sounds to ask about: a line inviting a tap on a transcription
            // whose symbols the table could not name is a line that answers nothing.
            if (answer.symbols.isNotEmpty()) {
                SoundLine(
                    about = opened,
                    palette = palette,
                    onPlay = onPlaySymbol,
                    onOpen = onOpen,
                    diagram = diagram,
                    report = report,
                )
            }
            // Every reading, where the join reached more than one. The reader chooses by meaning,
            // so each is its own row: showing the first and dropping the rest would be the card
            // making exactly the choice it is here to avoid.
            Readings(answer, palette, report)
            Grammar(answer, palette, report)
            // The other senses only where a language is read in itself. A word translated is
            // answered by its translation, and the card under a finger is not the place for
            // the dictionary's every sense of it: "and" read into Spanish is "y", and its ten
            // English definitions covered the page it was read on.
            // One line of the word in use is not another meaning, and is kept for a word that
            // is translated; a card still asking which word this is shows neither.
            if (!chooses(answer)) {
                Example(answer, palette, report)
                if (!translated(answer)) OtherSenses(answer, palette, report)
            }
            Foot(answer, palette, report, onOpen)
        }
    }
}

/**
 * Whether the card is asking which word this is. Only where nothing decided it: a decided
 * answer keeps its other readings, and read off them alone, "because" - decided, a
 * conjunction - was a card saying it was more than one word.
 */
private fun chooses(answer: Answer): Boolean =
    answer.state == Answer.State.Homograph && answer.readings.size >= 2

/** Whether the card answers with a word in another language, which is what it leads with. */
private fun translated(answer: Answer): Boolean =
    answer.says.isNotEmpty() && answer.source.isNotEmpty() && answer.source != answer.target

/** How many readings a card lists for a reader to choose between. */
private const val READINGS_SHOWN = 3

/** How far the arrow reaches out of the card, which is also the room made for it. */
private val ARROW = 8.dp

/**
 * The arrow that says which word the card is about.
 *
 * A triangle in the card's own surface with the card's own border along its two outer edges,
 * so what a reader sees is the card's edge carrying a point rather than a shape stuck to it.
 */
@Composable
private fun Arrow(at: Dp, down: Boolean, palette: Tokens.Palette, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(ARROW)) {
        val middle = at.toPx().coerceIn(ARROW.toPx() * 2, size.width - ARROW.toPx() * 2)
        val half = ARROW.toPx()
        val tip = if (down) size.height else 0f
        val base = if (down) 0f else size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(middle - half, base)
            lineTo(middle, tip)
            lineTo(middle + half, base)
            close()
        }
        drawPath(path, Color(palette.surface))
        drawPath(
            path,
            Color(palette.border),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = Tokens.Scale.borderWidth.dp.toPx(),
            ),
        )
    }
}

/**
 * The word as the page spells it, what it is being read as, and where the answer came from.
 *
 * One line and it stays one line: what is under the finger is named before anything else is
 * said about it, and a reader decides how far to trust the card by where the answer came from.
 */
@Composable
private fun TopRow(
    answer: Answer,
    accent: String,
    palette: Tokens.Palette,
    report: Reporter?,
    onOpen: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The word and what it is marked with, together, taking whatever room is left over.
        //
        // The word and the spacer beside it were each given a share of the free space, so the
        // spacer got half of it and the link at the end of the row stopped in the middle of
        // the card: what shares the row is one thing, and the space after it is the rest.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            androidx.compose.material3.Text(
                text = answer.spelling,
                color = Color(palette.ink),
                fontSize = Tokens.Scale.fontSizeLemma.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .reported(answer.spelling, report),
            )
            Box(Modifier.width(Tokens.Scale.space2.dp))
            // What it is being read as, and in which accent where the reader chose one: the two
            // are one fact, so they share one neutral pill.
            // Only where there is a language to name: an empty pill beside the word is a pill
            // saying only that a pill was drawn.
            if (answer.source.isNotBlank()) {
                val named = Accents.of(answer.source).firstOrNull { it.id == accent }?.name
                Badge(
                    text = if (named != null) "${answer.source.uppercase()} · $named"
                    else answer.source.uppercase(),
                    ink = Color(palette.chipInk),
                    background = Color(palette.chipBg),
                )
            }
            val from = answer.provenance
            if (from != null) {
                Box(Modifier.width(Tokens.Scale.space2.dp))
                val machine = from !is Answer.Provenance.Dictionary
                Badge(
                    text = when (from) {
                        is Answer.Provenance.Dictionary -> "dictionary"
                        is Answer.Provenance.Guess -> from.engine.ifEmpty { "machine" }
                        Answer.Provenance.Synthesised -> "espeak"
                    },
                    ink = Color(if (machine) palette.guess else palette.accent),
                    background = Color(if (machine) palette.guessBg else palette.accentBg),
                )
            }
        }
        // The word's own entry, and it is here whether or not a dictionary answered: the
        // reader who got nothing is the one most likely to want it.
        Box(
            Modifier
                .size(Tokens.Scale.iconButton.dp)
                .clip(RoundedCornerShape(Tokens.Scale.radiusSymbol.dp))
                .clickable { onOpen(wiktionary(answer)) }
                .semantics { contentDescription = "open this word on Wiktionary" }
                .reported("Wiktionary", report),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                text = "W",
                color = Color(palette.inkFaint),
                fontSize = Tokens.Scale.markSize.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            )
        }
    }
}

/**
 * The answer, and where it came from.
 *
 * The badge is beside the answer rather than under it, because a reader deciding whether to
 * trust a word is deciding it at the moment they read the word.
 */
@Composable
private fun Headline(answer: Answer, palette: Tokens.Palette, report: Reporter?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            text = answer.headline.orEmpty(),
            color = Color(palette.ink),
            fontSize = Tokens.Scale.fontSizeHeadline.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f, fill = false)
                .reported(answer.headline.orEmpty(), report),
        )
        // A machine's answer is labelled one. A wrong word wearing a dictionary's authority is
        // worse than an obvious guess.
        //
        // And so is an English gloss sitting where the answer goes. Where the dictionary
        // reached nothing in the reader's language, what is left is the word's English
        // meaning, and unmarked it reads as the translation rather than as the anchor it is.
        // Which kind of word it is, beside what it means rather than on a row of its own:
        // alone on a line a single word reads as a leftover.
        answer.pos?.let {
            Box(Modifier.width(Tokens.Scale.space2.dp))
            Badge(text = it, ink = Color(palette.chipInk), background = Color(palette.chipBg))
        }
        val guessed = answer.state == Answer.State.Guess
        val anchor = answer.says.isEmpty() && answer.glosses.isNotEmpty()
        if (guessed || anchor) {
            Box(Modifier.width(Tokens.Scale.space3.dp))
            Badge(
                text = Wording.say(if (guessed) "guess" else "in-english"),
                ink = Color(if (guessed) palette.guess else palette.inkMuted),
                background = Color(if (guessed) palette.guessBg else palette.chipBg),
            )
        }
    }
}

/** How it is said: every symbol reachable on its own, then the audio and what made it. */
@Composable
private fun Pronunciation(
    answer: Answer,
    palette: Tokens.Palette,
    onSymbol: (String) -> Unit,
    recorded: Boolean,
    onPlay: () -> Unit,
    opened: SymbolInfo?,
    report: Reporter?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            text = "/",
            color = Color(palette.inkFaint),
            fontSize = Tokens.Scale.fontSizeIpaLarge.sp,
        )
        // Whole, where the table could not say what its sounds are: a transcription nobody
        // can tap is still the transcription, and empty delimiters are a card saying it knows
        // how a word sounds and then showing nothing.
        if (answer.symbols.isEmpty()) {
            androidx.compose.material3.Text(
                text = answer.ipa.first(),
                color = Color(palette.ink),
                fontSize = Tokens.Scale.fontSizeIpaLarge.sp,
                modifier = Modifier.reported(answer.ipa.first(), report),
            )
        }
        // Symbol by symbol, because each one is a button: a reader who does not know a sound
        // is one tap from what it is, which is the whole of what the old tooltip was for.
        for (symbol in answer.symbols) {
            // The rule under a symbol says what kind of sound it is and follows the letterform
            // rather than boxing it, so the transcription still reads as one word. Stress and
            // syllable marks carry none: they are not sounds, and underlining them broke the
            // line into dashes.
            val rule = when (symbol.kind) {
                "vowel" -> palette.ipaVowel
                "consonant" -> palette.ipaConsonant
                // A diacritic is not a sound of its own - it changes the one before it - so it
                // is ruled in the quiet colour rather than a sound's own.
                "diacritic" -> palette.ipaOther
                else -> null
            }
            androidx.compose.material3.Text(
                text = symbol.token,
                color = Color(if (rule == null) palette.ipaOther else palette.ink),
                fontSize = Tokens.Scale.fontSizeIpaLarge.sp,
                // No space between symbols: a transcription is one word and reads as one.
                // Each is still its own target, which is what a tap needs, and the gaps that
                // separated them made "/ˈpe.ro/" read as a row of letters.
                modifier = Modifier
                    .clip(RoundedCornerShape(Tokens.Scale.radiusSymbol.dp))
                    .background(
                        if (opened?.token == symbol.token) Color(palette.accentBg)
                        else Color.Transparent,
                    )
                    .clickable { onSymbol(symbol.token) }
                    .underlined(rule?.let { Color(it) })
                    .reported(symbol.token, report),
            )
        }
        androidx.compose.material3.Text(
            text = "/",
            color = Color(palette.inkFaint),
            fontSize = Tokens.Scale.fontSizeIpaLarge.sp,
        )
        Box(Modifier.width(Tokens.Scale.space3.dp))
        PlayButton(
            palette = palette,
            description = if (recorded) "hear ${answer.spelling}" else "say ${answer.spelling}",
            onClick = onPlay,
            report = report,
        )
        Box(Modifier.width(Tokens.Scale.space2.dp))
        // What the audio will be, shown rather than spelled out: a synthesised voice and a
        // person saying a word are different things and a reader is owed which one they are
        // getting, but that is a property of the button beside it and not a line of the card.
        if (recorded) RecordingMark(palette, report = report)
        else SynthesisedMark(palette, report = report)
    }
}

/**
 * Which word this is: the one to memorise, and the form that was tapped.
 *
 * For a form the lemma gets the prominence, because "gehen" is what a learner commits to
 * memory and "ging" is what they happened to meet.
 */
@Composable
private fun Grammar(answer: Answer, palette: Tokens.Palette, report: Reporter?) {
    // Each reading carries its own part of speech at the end of its row, so repeating the
    // first one under them says nothing and reads as if it belonged to the last.
    if (chooses(answer)) return
    // The part of speech is beside the answer, not here: this line is about which word the
    // spelling belongs to.
    val parts = buildList {
        answer.lemma?.let { add(it) }
        // Which form, where the dump named it: "plural of perro" says the relation, and the
        // spelling on its own leaves a reader to work out what they are looking at.
        if (answer.lemma != null) {
            add(answer.form?.let { "$it of ${answer.lemma}" } ?: "form: ${answer.spelling}")
        }
    }
    if (parts.isEmpty()) return
    val line = parts.joinToString("  ·  ")
    androidx.compose.material3.Text(
        text = line,
        color = Color(palette.inkMuted),
        fontSize = Tokens.Scale.fontSizeSmall.sp,
        modifier = Modifier.reported(line, report),
    )
}

/**
 * The word in use, where the dump had a line of it.
 *
 * One line and only when there is one: a sense in context settles which sense applies faster
 * than another gloss does, and an invented sentence would settle it wrongly.
 */
@Composable
private fun Example(answer: Answer, palette: Tokens.Palette, report: Reporter?) {
    val example = answer.example ?: return
    androidx.compose.material3.Text(
        text = example,
        color = Color(palette.inkMuted),
        fontSize = Tokens.Scale.fontSizeSense.sp,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
        modifier = Modifier.reported(example, report),
    )
}

/**
 * The senses that did not apply, up to two, and how many are left.
 *
 * A word with one sense shows none of this. A word with twelve shows two and the count, because
 * the full list made a card into a scroll.
 */
@Composable
private fun OtherSenses(answer: Answer, palette: Tokens.Palette, report: Reporter?) {
    // Each sense with what it is marked as, so a reader is told a sense is archaic or
    // regional rather than meeting it as though it were the ordinary one.
    val rest = answer.glosses.drop(1).mapIndexed { at, gloss ->
        val marks = answer.marks.getOrNull(at + 1).orEmpty().mapNotNull { Wording.markName(it, answer.pos) }.distinct()
        if (marks.isEmpty()) gloss else marks.joinToString(" ") + "  ·  " + gloss
    }
    if (rest.isEmpty()) return
    for (sense in rest.take(2)) {
        androidx.compose.material3.Text(
            text = sense,
            color = Color(palette.inkMuted),
            fontSize = Tokens.Scale.fontSizeSense.sp,
            modifier = Modifier.reported(sense, report),
        )
    }
    if (rest.size > 2) {
        androidx.compose.material3.Text(
            text = if (rest.size == 3) "1 more sense" else "${rest.size - 2} more senses",
            color = Color(palette.accent),
            fontSize = Tokens.Scale.fontSizeSmall.sp,
        )
    }
}

/**
 * The words this spelling is, where it is more than one.
 *
 * A reader chooses by meaning, so each reading leads with what it means and carries its part of
 * speech at the end of its own row. Nothing here decides which they met.
 */
@Composable
private fun Readings(answer: Answer, palette: Tokens.Palette, report: Reporter?) {
    if (!chooses(answer)) return
    // Three at most, and one row per meaning: two readings that say the same word are one
    // row to a reader choosing between them.
    val shown = answer.readings.distinctBy { it.headline to it.pos }.take(READINGS_SHOWN)
    for (reading in shown) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // One line each: a reading the reader's language has no word for leads with its
            // English definition, and one of those ran to four lines.
            androidx.compose.material3.Text(
                text = reading.headline.orEmpty(),
                color = Color(palette.ink),
                fontSize = Tokens.Scale.fontSizeLemma.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            reading.pos?.let {
                Box(Modifier.width(Tokens.Scale.space2.dp))
                androidx.compose.material3.Text(
                    text = it,
                    color = Color(palette.inkFaint),
                    fontSize = Tokens.Scale.fontSizeLabel.sp,
                )
            }
        }
    }
}

/**
 * Always the same place: which languages this is, and what answered.
 *
 * The way onward is not here: it is the mark on the top row, which is where a reader who wants
 * the whole entry looks and is there whether or not a dictionary answered.
 */
@Composable
private fun Foot(
    answer: Answer,
    palette: Tokens.Palette,
    report: Reporter?,
    onOpen: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // The way onward is the mark on the top row, where a reader who wants the whole entry
        // looks; a second Wiktionary link down here was the same link twice.
        Pair(answer, palette)
        val pack = (answer.provenance as? Answer.Provenance.Dictionary)?.pack
        if (pack != null) {
            // At the far end, as the browser's card has it: after a fixed gap it was set in from
            // the edge by that gap wherever there were no languages to name before it.
            Box(Modifier.padding(start = Tokens.Scale.space3.dp).weight(1f))
            androidx.compose.material3.Text(
                text = pack,
                color = Color(palette.inkFaint),
                fontSize = Tokens.Scale.fontSizeLabel.sp,
            )
        }
    }
}

/**
 * A rule under a symbol, in the colour of what kind of sound it is.
 *
 * Drawn rather than asked for as a text decoration, because a decoration takes the colour of
 * the text it underlines and the whole point here is that the two differ.
 */
private fun Modifier.underlined(colour: Color?): Modifier =
    if (colour == null) {
        this
    } else {
        drawBehind {
            val thickness = Tokens.Scale.underlineThickness.dp.toPx()
            val below = size.height - Tokens.Scale.underlineOffset.dp.toPx() / 2
            drawRect(
                color = colour,
                topLeft = androidx.compose.ui.geometry.Offset(0f, below - thickness),
                size = androidx.compose.ui.geometry.Size(size.width, thickness),
            )
        }
    }

/** Where a word's own page is, which is the same URL the extension builds. */
internal fun wiktionary(answer: Answer): String {
    val word = answer.lemma ?: answer.spelling
    val page = "https://en.wiktionary.org/wiki/" + java.net.URLEncoder.encode(word, "UTF-8")
    // At the section for the language it is being read as: a spelling like "stops" is an entry
    // in four languages, every section arriving collapsed, and the page alone leaves the reader
    // to work out which of them they were reading. Wiktionary names its sections in English.
    val language = Interface.named(answer.source)
    return if (answer.source.isBlank()) page else "$page#" + java.net.URLEncoder.encode(language, "UTF-8")
}

@Composable
private fun Pair(answer: Answer, palette: Tokens.Palette) {
    // Nothing where there is nothing to say. A build with no dictionary knows neither language,
    // and an arrow between two blanks is a row that says only that a row was drawn. Nor is one
    // language read into itself a pair: "English → English" is the same word said twice, which
    // is what a reader sees whenever they have chosen no language to read into.
    if (answer.source.isBlank() || answer.target.isBlank()) return
    if (answer.source == answer.target) return
    androidx.compose.material3.Text(
        // Named rather than tagged: a reader knows what Spanish is and does not have to know
        // what "es" is.
        text = "${Interface.named(answer.source)} → ${Interface.named(answer.target)}",
        color = Color(palette.inkFaint),
        fontSize = Tokens.Scale.fontSizeLabel.sp,
    )
}

/** What the card says when the cascade found nothing, which is one sentence and no empty rows. */
@Composable
private fun Nothing(answer: Answer, palette: Tokens.Palette, report: Reporter?) {
    androidx.compose.material3.Text(
        text = when (answer.state) {
            Answer.State.NoPack -> Wording.say("no-dictionary-for", Interface.named(answer.source))
            Answer.State.UnknownLang -> Wording.say("unknown-language")
            Answer.State.Loading -> Wording.say("looking-up")
            else -> Wording.say("nothing-for", answer.spelling)
        },
        color = Color(palette.inkMuted),
        fontSize = Tokens.Scale.fontSizeBody.sp,
    )
}

@Composable
private fun Badge(text: String, ink: Color, background: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Tokens.Scale.radiusChip.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = ink,
            fontSize = Tokens.Scale.fontSizeLabel.sp,
        )
    }
}

