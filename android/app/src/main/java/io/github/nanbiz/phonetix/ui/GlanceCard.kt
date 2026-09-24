package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nanbiz.phonetix.core.Answer

/**
 * What the side button shows about the word it is over, read at a glance while the finger
 * is still on the screen.
 *
 * Display only: it takes no touch and is gone when the button is let go, so nothing on it is
 * a control. What it says follows the reader's two switches:
 *
 * - translation: the word it means, the kind of word and what else it can mean, one example;
 * - pronunciation: how it is said;
 * - both: how it is said, then what it means;
 * - neither: what the dictionary says it is.
 *
 * The word itself heads it in small type, so a reader dragging over a line knows which word
 * the answer is for.
 */
@Composable
fun GlanceCard(
    answer: Answer,
    palette: Tokens.Palette,
    sound: Boolean,
    meaning: Boolean,
    modifier: Modifier = Modifier,
    report: Reporter? = null,
) {
    val ipa = answer.ipa.firstOrNull()?.takeIf { it.isNotBlank() }
    val says = answer.says.firstOrNull()?.let(::plainly)?.takeIf { it.isNotBlank() }
    // The other words the spelling can be, where the dictionary could not tell which one the
    // page means. Decided, the rest are other words entirely: "Reifen", tyres, is also the verb
    // "reifen", to ripen, and naming it beside the tyres answered a question nobody asked.
    val also = if (answer.state != Answer.State.Homograph) emptyList() else answer.readings
        .mapNotNull { it.says.firstOrNull()?.let(::plainly) }
        .filter { it != says }
        .distinct()
        .take(ALSO)
    val definition = answer.glosses.firstOrNull()?.takeIf { it.isNotBlank() }
    val guessed = answer.state == Answer.State.Guess

    Column(
        modifier = modifier
            .widthIn(min = 120.dp, max = Tokens.Scale.cardWidth.dp)
            .clip(RoundedCornerShape(Tokens.Scale.radiusCard.dp))
            .background(Color(palette.surface))
            .padding(horizontal = Tokens.Scale.space4.dp, vertical = Tokens.Scale.space3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The word, and how it is said where that is asked for.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = answer.spelling,
                color = Color(palette.inkMuted),
                fontSize = Tokens.Scale.fontSizeSmall.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.reported(answer.spelling, report),
            )
            if (sound && meaning && ipa != null && says != null) {
                Text(
                    text = "  /$ipa/",
                    color = Color(palette.ipa),
                    fontSize = Tokens.Scale.fontSizeSense.sp,
                    style = IpaStyle.copy(fontWeight = FontWeight.Normal),
                    maxLines = 1,
                    modifier = Modifier.reported("/$ipa/", report),
                )
            }
        }
        when {
            meaning && says != null -> {
                Text(
                    text = says,
                    color = Color(if (guessed) palette.inkMuted else palette.ink),
                    fontSize = Tokens.Scale.fontSizeHeadline.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = if (guessed) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.reported(says, report),
                )
                val kind = listOfNotNull(answer.pos) + also
                if (kind.isNotEmpty()) {
                    Text(
                        text = kind.joinToString(" · "),
                        color = Color(palette.inkMuted),
                        fontSize = Tokens.Scale.fontSizeSmall.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.reported(kind.joinToString(" · "), report),
                    )
                }
                if (!sound) {
                    answer.example?.takeIf { it.isNotBlank() }?.let { example ->
                        Text(
                            text = example,
                            color = Color(palette.inkMuted),
                            fontSize = Tokens.Scale.fontSizeSmall.sp,
                            fontStyle = FontStyle.Italic,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.reported(example, report),
                        )
                    }
                }
            }
            sound && ipa != null -> Text(
                text = "/$ipa/",
                color = Color(palette.ipa),
                fontSize = Tokens.Scale.fontSizeIpaLarge.sp,
                style = IpaStyle,
                maxLines = 2,
                modifier = Modifier.reported("/$ipa/", report),
            )
            definition != null -> Text(
                text = definition,
                color = Color(palette.ink),
                fontSize = Tokens.Scale.fontSizeBody.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.reported(definition, report),
            )
            ipa != null -> Text(
                text = "/$ipa/",
                color = Color(palette.ipa),
                fontSize = Tokens.Scale.fontSizeIpaLarge.sp,
                style = IpaStyle,
                maxLines = 2,
                modifier = Modifier.reported("/$ipa/", report),
            )
        }
    }
}

/** Whether the card has anything to say about [answer] under these switches: a card that
 *  would only repeat the word is not opened at all. */
fun glanceHasSomething(answer: Answer, sound: Boolean, meaning: Boolean): Boolean {
    val ipa = answer.ipa.any { it.isNotBlank() }
    val says = answer.says.any { it.isNotBlank() }
    val definition = answer.glosses.any { it.isNotBlank() }
    return (meaning && says) || ipa || definition
}

/**
 * A sense as the word it offers: an English dictionary writes "dog (the species Canis
 * familiaris); hound", and at a glance the answer is "dog".
 */
fun plainly(sense: String): String {
    var out = sense
    while (true) {
        val open = out.indexOf('(')
        val close = if (open >= 0) out.indexOf(')', open) else -1
        if (open < 0 || close < 0) break
        out = out.removeRange(open, close + 1)
    }
    return out.split(';').first().trim().trimEnd('.', ',', ':').trim()
}

/** How many other meanings the card names beside the kind of word. */
private const val ALSO = 2
