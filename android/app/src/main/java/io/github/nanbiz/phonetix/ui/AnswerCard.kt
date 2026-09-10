package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.nanbiz.phonetix.core.Answer
import io.github.nanbiz.phonetix.core.Languages

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
 * The order is what a reader wants, not what the data happens to hold: what it means, how it
 * is said, which word it is, then the senses that did not apply. The tapped spelling is
 * deliberately not the headline - the page is already showing it under the finger, and the
 * card repeating it pushed the answer down. The lemma still appears, small, in the grammar
 * line, so the word is never absent.
 *
 * Every colour and size comes from [Tokens], which is generated from the same page the
 * browser's card is styled by, so the two cannot drift apart.
 */
@Composable
fun AnswerCard(
    answer: Answer,
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    onSymbol: (String) -> Unit = {},
    /** Whether what the play button plays is a person rather than a machine. */
    recorded: Boolean = false,
    onPlay: () -> Unit = {},
    /** Somewhere to send a reader who wants the whole entry. */
    onOpen: (String) -> Unit = {},
    /** Where each piece of the card ended up, once it has been laid out.
     *
     *  An overlay window is invisible to a tool reading the screen, so without this a check can
     *  know the card was asked for and never that it drew anything or where its buttons are.
     *  The bounds come from the layout rather than from what was intended, which is the whole
     *  point of asking. */
    report: Reporter? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Scale.radiusCard.dp))
            .background(Color(palette.surface))
            .padding(Tokens.Scale.space4.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Scale.space3.dp),
    ) {
        if (!answer.found) {
            Nothing(answer, palette, report)
            return@Column
        }
        if (answer.readings.size < 2) {
            Headline(answer, palette, report)
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
            Pronunciation(answer, palette, onSymbol, recorded, onPlay, report)
        }
        // Every reading, where the join reached more than one. The reader chooses by meaning,
        // so each is its own row: showing the first and dropping the rest would be the card
        // making exactly the choice it is here to avoid.
        Readings(answer, palette, report)
        Grammar(answer, palette, report)
        Example(answer, palette, report)
        OtherSenses(answer, palette, report)
        Foot(answer, palette, report, onOpen)
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
        val guessed = answer.state == Answer.State.Guess
        val anchor = answer.says.isEmpty() && answer.glosses.isNotEmpty()
        if (guessed || anchor) {
            Box(Modifier.width(Tokens.Scale.space3.dp))
            Badge(
                text = if (guessed) "guess" else "in English",
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
    report: Reporter?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            text = "/",
            color = Color(palette.ipa),
            fontSize = Tokens.Scale.fontSizeIpa.sp,
        )
        // Symbol by symbol, because each one is a button: a reader who does not know a sound
        // is one tap from what it is, which is the whole of what the old tooltip was for.
        for (symbol in answer.symbols) {
            androidx.compose.material3.Text(
                text = symbol.token,
                color = Color(
                    when (symbol.kind) {
                        "vowel" -> palette.ipaVowel
                        "consonant" -> palette.ipaConsonant
                        else -> palette.ipaOther
                    },
                ),
                fontSize = Tokens.Scale.fontSizeIpa.sp,
                // No space between symbols: a transcription is one word and reads as one.
                // Each is still its own target, which is what a tap needs, and the gaps that
                // separated them made "/ˈpe.ro/" read as a row of letters.
                modifier = Modifier
                    .clickable { onSymbol(symbol.token) }
                    .reported(symbol.token, report),
            )
        }
        androidx.compose.material3.Text(
            text = "/",
            color = Color(palette.ipa),
            fontSize = Tokens.Scale.fontSizeIpa.sp,
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
    if (answer.readings.size >= 2) return
    val parts = buildList {
        answer.lemma?.let { add(it) }
        answer.pos?.let { add(it) }
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
        val marks = answer.marks.getOrNull(at + 1).orEmpty()
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
    if (answer.readings.size < 2) return
    for (reading in answer.readings) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Text(
                text = reading.headline.orEmpty(),
                color = Color(palette.ink),
                fontSize = Tokens.Scale.fontSizeLemma.sp,
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
 * Always the same place: which languages this is, and the way onward.
 *
 * The link is always here whether or not a dictionary answered, because a reader who got
 * nothing is the one most likely to want it.
 */
@Composable
private fun Foot(
    answer: Answer,
    palette: Tokens.Palette,
    report: Reporter?,
    onOpen: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            text = "Wiktionary",
            color = Color(palette.accent),
            fontSize = Tokens.Scale.fontSizeLabel.sp,
            modifier = Modifier
                .clickable { onOpen(wiktionary(answer)) }
                .reported("Wiktionary", report),
        )
        Box(Modifier.width(Tokens.Scale.space3.dp))
        Pair(answer, palette)
    }
}

/** Where a word's own page is, which is the same URL the extension builds. */
internal fun wiktionary(answer: Answer): String {
    val word = answer.lemma ?: answer.spelling
    return "https://en.wiktionary.org/wiki/" + java.net.URLEncoder.encode(word, "UTF-8")
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
        text = "${Languages.english(answer.source)} → ${Languages.english(answer.target)}",
        color = Color(palette.inkFaint),
        fontSize = Tokens.Scale.fontSizeLabel.sp,
    )
}

/** What the card says when the cascade found nothing, which is one sentence and no empty rows. */
@Composable
private fun Nothing(answer: Answer, palette: Tokens.Palette, report: Reporter?) {
    androidx.compose.material3.Text(
        text = when (answer.state) {
            Answer.State.NoPack -> "No dictionary for ${Languages.english(answer.source)} yet"
            Answer.State.UnknownLang -> "Not sure what language this is"
            Answer.State.Loading -> "Looking it up"
            else -> "Nothing found for ${answer.spelling}"
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

