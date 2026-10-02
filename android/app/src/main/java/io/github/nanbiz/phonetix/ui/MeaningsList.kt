package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.layout.Arrangement
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
 * where the reader has pronunciation switched on. Nothing else: the list is read at a glance.
 */
@Composable
fun MeaningsList(meanings: List<Meant>, palette: Tokens.Palette, sound: Boolean) {
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
                    if (sound && !meant.ipa.isNullOrBlank()) {
                        Text(
                            text = "  /${meant.ipa}/",
                            color = Color(palette.ipa),
                            fontSize = Tokens.Scale.fontSizeSense.sp,
                            style = IpaStyle.copy(fontWeight = FontWeight.Normal),
                            maxLines = 1,
                        )
                    }
                }
                val about = listOf(meant.pos, meant.hint).filter { it.isNotBlank() }
                if (about.isNotEmpty()) {
                    Text(
                        text = about.joinToString(" · "),
                        color = Color(palette.inkMuted),
                        fontSize = Tokens.Scale.fontSizeSmall.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
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

/** One way a word is read in its own language: how it is said, and what it is. */
data class Defined(val ipa: String?, val pos: String, val gloss: String)

/**
 * A typed word in the language it is in, where nothing is translated: the word, and under it
 * each way it is read. With pronunciation on, every reading leads with how it is said and the
 * definition tells the readings apart ("record" the noun and the verb are said differently);
 * with it off the definitions are the answer.
 */
@Composable
fun DefinedList(word: String, readings: List<Defined>, palette: Tokens.Palette, sound: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Tokens.Scale.space3.dp),
    ) {
        Text(
            text = word,
            color = Color(palette.inkMuted),
            fontSize = Tokens.Scale.fontSizeSense.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        for (reading in readings) {
            Column(Modifier.fillMaxWidth()) {
                if (sound && !reading.ipa.isNullOrBlank()) {
                    Text(
                        text = "/${reading.ipa}/",
                        color = Color(palette.ipa),
                        fontSize = Tokens.Scale.fontSizeIpaLarge.sp,
                        style = IpaStyle,
                        maxLines = 2,
                    )
                }
                val about = listOf(reading.pos, reading.gloss).filter { it.isNotBlank() }
                if (about.isNotEmpty()) {
                    Text(
                        text = about.joinToString(" · "),
                        color = Color(if (sound) palette.inkMuted else palette.ink),
                        fontSize = (if (sound) Tokens.Scale.fontSizeSmall else Tokens.Scale.fontSizeBody).sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
