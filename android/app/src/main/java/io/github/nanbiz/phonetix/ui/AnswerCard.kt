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
import io.github.nanbiz.phonetix.core.Answer
import io.github.nanbiz.phonetix.core.Languages

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
    onPlay: () -> Unit = {},
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
            Nothing(answer, palette)
            return@Column
        }
        Headline(answer, palette)
        if (answer.ipa.isNotEmpty()) {
            Pronunciation(answer, palette, onSymbol, onPlay)
        }
        // Every reading, where the join reached more than one. The reader chooses by meaning,
        // so each is its own row: showing the first and dropping the rest would be the card
        // making exactly the choice it is here to avoid.
        Readings(answer, palette)
        Grammar(answer, palette)
        Example(answer, palette)
        OtherSenses(answer, palette)
        Foot(answer, palette)
    }
}

/**
 * The answer, and where it came from.
 *
 * The badge is beside the answer rather than under it, because a reader deciding whether to
 * trust a word is deciding it at the moment they read the word.
 */
@Composable
private fun Headline(answer: Answer, palette: Tokens.Palette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            text = answer.headline.orEmpty(),
            color = Color(palette.ink),
            fontSize = Tokens.Scale.fontSizeHeadline.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f, fill = false),
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
    onPlay: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            text = "/",
            color = Color(palette.ipa),
            fontSize = Tokens.Scale.fontSizeIpa.sp,
        )
        // Symbol by symbol, because each one is a button: a reader who does not know a sound
        // is one tap from what it is, which is the whole of what the old tooltip was for.
        for (symbol in symbolsOf(answer.ipa.first())) {
            androidx.compose.material3.Text(
                text = symbol,
                color = Color(palette.ipaConsonant),
                fontSize = Tokens.Scale.fontSizeIpa.sp,
                // No space between symbols: a transcription is one word and reads as one.
                // Each is still its own target, which is what a tap needs, and the gaps that
                // separated them made "/ˈpe.ro/" read as a row of letters.
                modifier = Modifier.clickable { onSymbol(symbol) },
            )
        }
        androidx.compose.material3.Text(
            text = "/",
            color = Color(palette.ipa),
            fontSize = Tokens.Scale.fontSizeIpa.sp,
        )
        Box(Modifier.width(Tokens.Scale.space3.dp))
        Box(
            Modifier
                .size(Tokens.Scale.audioSize.dp)
                .clip(RoundedCornerShape(Tokens.Scale.audioSize.dp))
                .background(Color(palette.chipBg))
                .clickable { onPlay() },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                text = "▸",
                color = Color(palette.ink),
                fontSize = Tokens.Scale.fontSizeBody.sp,
            )
        }
    }
}

/**
 * Which word this is: the one to memorise, and the form that was tapped.
 *
 * For a form the lemma gets the prominence, because "gehen" is what a learner commits to
 * memory and "ging" is what they happened to meet.
 */
@Composable
private fun Grammar(answer: Answer, palette: Tokens.Palette) {
    val parts = buildList {
        answer.lemma?.let { add(it) }
        answer.pos?.let { add(it) }
        if (answer.lemma != null) add("form: ${answer.spelling}")
    }
    if (parts.isEmpty()) return
    androidx.compose.material3.Text(
        text = parts.joinToString("  ·  "),
        color = Color(palette.inkMuted),
        fontSize = Tokens.Scale.fontSizeSmall.sp,
    )
}

/**
 * The word in use, where the dump had a line of it.
 *
 * One line and only when there is one: a sense in context settles which sense applies faster
 * than another gloss does, and an invented sentence would settle it wrongly.
 */
@Composable
private fun Example(answer: Answer, palette: Tokens.Palette) {
    val example = answer.example ?: return
    androidx.compose.material3.Text(
        text = example,
        color = Color(palette.inkMuted),
        fontSize = Tokens.Scale.fontSizeSense.sp,
        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
    )
}

/**
 * The senses that did not apply, up to two, and how many are left.
 *
 * A word with one sense shows none of this. A word with twelve shows two and the count, because
 * the full list made a card into a scroll.
 */
@Composable
private fun OtherSenses(answer: Answer, palette: Tokens.Palette) {
    val rest = answer.glosses.drop(1)
    if (rest.isEmpty()) return
    for (sense in rest.take(2)) {
        androidx.compose.material3.Text(
            text = sense,
            color = Color(palette.inkMuted),
            fontSize = Tokens.Scale.fontSizeSense.sp,
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

/** The other words this reached, where it reached more than one. */
@Composable
private fun Readings(answer: Answer, palette: Tokens.Palette) {
    val rest = answer.says.drop(1)
    if (rest.isEmpty()) return
    for (reading in rest) {
        androidx.compose.material3.Text(
            text = reading,
            color = Color(palette.ink),
            fontSize = Tokens.Scale.fontSizeLemma.sp,
        )
    }
}

/** Always the same place: which languages this is, and out of which pack. */
@Composable
private fun Foot(answer: Answer, palette: Tokens.Palette) {
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
private fun Nothing(answer: Answer, palette: Tokens.Palette) {
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

/**
 * A transcription split into the symbols a reader can ask about.
 *
 * A symbol is a base letter with whatever diacritics hang off it: those are not separate
 * sounds and splitting them apart would offer a reader a tap on a mark rather than on a sound.
 */
internal fun symbolsOf(ipa: String): List<String> {
    val out = mutableListOf<String>()
    for (c in ipa) {
        val combining = c.isWhitespace() ||
            Character.getType(c).let {
                it == Character.NON_SPACING_MARK.toInt() ||
                    it == Character.COMBINING_SPACING_MARK.toInt() ||
                    it == Character.MODIFIER_LETTER.toInt() ||
                    it == Character.MODIFIER_SYMBOL.toInt()
            }
        if (combining && out.isNotEmpty()) {
            out[out.size - 1] = out.last() + c
        } else {
            out.add(c.toString())
        }
    }
    return out.filter { it.isNotBlank() }
}
