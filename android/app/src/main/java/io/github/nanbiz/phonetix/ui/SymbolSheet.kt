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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nanbiz.phonetix.core.SymbolInfo

/**
 * What one sound is, a tap under the word.
 *
 * The card answers about a word; this answers about a sound, which is why it is one tap deeper
 * rather than on the card's face. Nothing the old tooltip offered about a symbol is dropped:
 * the symbol itself, its name, a word that has it, the mouth that makes it, a recording of it,
 * the article about it, and the films of a real mouth saying it.
 *
 * What is absent is absent. A symbol whose table row has no diagram shows no diagram slot,
 * rather than an empty frame that looks like something failing to load.
 */
@Composable
fun SymbolSheet(
    symbol: SymbolInfo,
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    diagram: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Scale.radiusCard.dp))
            .background(Color(palette.surface))
            .padding(Tokens.Scale.space4.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Scale.space3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The symbol at the size of a specimen, because it is the thing being asked about.
            Text(
                text = symbol.token,
                color = Color(palette.ipaConsonant),
                fontSize = Tokens.Scale.fontSizeSymbol.sp,
                fontWeight = FontWeight.Medium,
            )
            Box(Modifier.width(Tokens.Scale.space4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = symbol.name,
                    color = Color(palette.ink),
                    fontSize = Tokens.Scale.fontSizeBody.sp,
                )
                if (symbol.example.isNotBlank()) {
                    Text(
                        text = symbol.example,
                        color = Color(palette.inkMuted),
                        fontSize = Tokens.Scale.fontSizeSense.sp,
                    )
                }
            }
            if (symbol.audio != null) {
                Box(
                    Modifier
                        .size(Tokens.Scale.audioSize.dp)
                        .clip(RoundedCornerShape(Tokens.Scale.audioSize.dp))
                        .background(Color(palette.chipBg))
                        .clickable { onPlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "▸",
                        color = Color(palette.ink),
                        fontSize = Tokens.Scale.fontSizeBody.sp,
                    )
                }
            }
        }

        // The mouth that makes it, where the table has one. Drawn by the caller, because
        // fetching a picture is the host's business and not this one's.
        if (diagram != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(Tokens.Scale.radiusButton.dp))
                    .background(Color(palette.surfaceRaised)),
            ) {
                diagram()
            }
        }

        // Where to go deeper. Only the links the table actually has: a row that leads nowhere
        // is worse than no row.
        val links = buildList {
            symbol.wiki?.let { add("Wikipedia" to it) }
            symbol.seeing?.let { add("Seeing Speech" to it) }
        }
        if (links.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Scale.space3.dp)) {
                for ((label, url) in links) {
                    Text(
                        text = label,
                        color = Color(palette.accent),
                        fontSize = Tokens.Scale.fontSizeSmall.sp,
                        modifier = Modifier.clickable { onOpen(url) },
                    )
                }
            }
        }
    }
}
