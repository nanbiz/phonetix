package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.github.nanbiz.phonetix.core.Wording
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One thing a typed word can mean, as the panel lists it. */
data class Meant(val word: String, val pos: String, val hint: String, val ipa: String?)

/**
 * What a typed word means in the other language: one line per meaning, the commonest first.
 *
 * The word leads each line; which meaning it is follows in small type, and how it is said only
 * where the voice could say it. Nothing else: the list is read at a glance.
 */
@Composable
fun MeaningsList(meanings: List<Meant>, palette: Tokens.Palette) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Tokens.Scale.space2.dp),
    ) {
        for ((at, meant) in meanings.withIndex()) {
            // The commonest meaning is the answer; the rest are there to be glanced at.
            val first = at == 0
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = meant.word,
                        color = Color(palette.ink),
                        fontSize = (if (first) Tokens.Scale.fontSizeHeadline else Tokens.Scale.fontSizeLemma).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!meant.ipa.isNullOrBlank()) {
                        Text(
                            text = "  /${meant.ipa}/",
                            color = Color(palette.ipa),
                            fontSize = Tokens.Scale.fontSizeSense.sp,
                            style = IpaStyle.copy(fontWeight = FontWeight.Normal),
                            maxLines = 1,
                        )
                    }
                }
                // Its kind in front, by its whole name, as the card's other readings have theirs,
                // and which meaning it is after it, whole: one cut at the edge said nothing.
                if (meant.pos.isNotBlank() || meant.hint.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        if (meant.pos.isNotBlank()) {
                            Chip(Wording.posName(meant.pos), palette)
                        }
                        if (meant.hint.isNotBlank()) {
                            Text(
                                text = meant.hint,
                                color = Color(palette.inkMuted),
                                fontSize = Tokens.Scale.fontSizeSmall.sp,
                                modifier = Modifier.padding(
                                    start = if (meant.pos.isNotBlank()) Tokens.Scale.space2.dp else 0.dp,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A kind of word, as a chip: the card's own. */
@Composable
fun Chip(text: String, palette: Tokens.Palette) {
    Text(
        text = text,
        color = Color(palette.chipInk),
        fontSize = Tokens.Scale.fontSizeLabel.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Scale.radiusChip.dp))
            .background(Color(palette.chipBg))
            .padding(horizontal = Tokens.Scale.space2.dp, vertical = 1.dp),
    )
}

/** A phrase or a sentence, translated, and how it is said where that is asked for. */
@Composable
fun TranslatedLine(text: String, ipa: String?, palette: Tokens.Palette) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = text,
            color = Color(palette.ink),
            fontSize = Tokens.Scale.fontSizeHeadline.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!ipa.isNullOrBlank()) {
            Text(
                text = "/$ipa/",
                color = Color(palette.ipa),
                fontSize = Tokens.Scale.fontSizeSense.sp,
                style = IpaStyle.copy(fontWeight = FontWeight.Normal),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
