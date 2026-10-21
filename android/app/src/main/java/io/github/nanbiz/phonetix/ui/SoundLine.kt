package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nanbiz.phonetix.core.Wording
import io.github.nanbiz.phonetix.core.SymbolInfo

/**
 * What one sound is, on the card's own line for it.
 *
 * The same line the extension draws, from the same tokens: always there, always the same
 * height, holding either a description or the invitation to ask for one. A panel that appeared
 * under the card and grew with the description moved the card while a reader was reading it.
 *
 * Only what the table actually has. A sound with no recording shows no play button, because a
 * control that leads nowhere is worse than no control.
 */
@Composable
fun SoundLine(
    /** The sound being shown, or nothing while none has been asked about. */
    about: SymbolInfo?,
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    /** The mouth that makes it, drawn by the caller: fetching a picture is the host's business. */
    diagram: (@Composable () -> Unit)? = null,
    report: Reporter? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Tokens.Scale.detailHeight.dp)
            .clip(RoundedCornerShape(Tokens.Scale.radiusButton.dp))
            .background(Color(palette.surfaceRaised))
            .padding(horizontal = Tokens.Scale.space2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Scale.space2.dp),
    ) {
        if (about == null) {
            Text(
                text = Wording.say("tap-sound"),
                color = Color(palette.inkFaint),
                fontSize = Tokens.Scale.fontSizeLabel.sp,
            )
            return@Row
        }

        Text(
            text = about.token,
            color = Color(palette.ipa),
            fontSize = Tokens.Scale.fontSizeIpa.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.reported(about.token, report),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = about.name,
                color = Color(palette.ink),
                fontSize = Tokens.Scale.fontSizeLabel.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.reported(about.name, report),
            )
            if (about.example.isNotBlank()) {
                Text(
                    text = about.example,
                    color = Color(palette.inkFaint),
                    fontSize = Tokens.Scale.fontSizeLabel.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.reported(about.example, report),
                )
            }
        }
        if (about.audio != null) {
            // A person saying this one, unlike the word on the card, which a machine spoke.
            Mark(
                icon = Icons.Filled.VolumeUp,
                description = "play a recording of ${about.name}",
                // Its own name, because the card behind it has a play button too and a check
                // tapping one has to know which it reached.
                key = "play-recording",
                palette = palette,
                report = report,
                onClick = onPlay,
            )
        }
        about.wiki?.let { url ->
            Mark(
                icon = Icons.Filled.Info,
                description = "read about ${about.token}",
                key = "Wikipedia",
                palette = palette,
                report = report,
                onClick = { onOpen(url) },
            )
        }
        about.seeing?.let { url ->
            // Films of a real mouth saying it, which is what a diagram cannot show.
            Mark(
                icon = Icons.Filled.Movie,
                description = "see a mouth saying ${about.token}",
                key = "Seeing Speech",
                palette = palette,
                report = report,
                onClick = { onOpen(url) },
            )
        }
        if (diagram != null) {
            Box(
                Modifier
                    .size(Tokens.Scale.thumbWidth.dp, Tokens.Scale.thumbHeight.dp)
                    .clip(RoundedCornerShape(Tokens.Scale.radiusBar.dp))
                    // A sagittal section is dark ink drawn to be read on white, so it keeps its
                    // own plate whatever the card's ground is.
                    .background(Color(Tokens.Fixed.markPlate)),
                contentAlignment = Alignment.Center,
            ) {
                diagram()
            }
        }
    }
}

/** One of the small square buttons on the line: a mark, and what it does. */
@Composable
private fun Mark(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    key: String,
    palette: Tokens.Palette,
    report: Reporter?,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(Tokens.Scale.iconButton.dp)
            .clip(RoundedCornerShape(Tokens.Scale.radiusSymbol.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .reported(key, report),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(palette.inkFaint),
            modifier = Modifier.size(Tokens.Scale.sourceMarkSize.dp),
        )
    }
}
