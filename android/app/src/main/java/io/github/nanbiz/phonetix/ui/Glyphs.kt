package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The small pictures the card uses in place of words.
 *
 * From the icon set rather than drawn here. Cut by hand in a canvas, each of these was a shape
 * this repository had to keep legible at every size and did not: the microphone's head is a
 * capsule, and at the size the mark is drawn its straight sides came to about two pixels, so
 * it read as a lollipop. A set that does nothing else gets that right and keeps getting it
 * right.
 *
 * What is ours is which picture means what, and that a reader is always shown it: a voice a
 * machine made and a voice a person recorded are different things, and the difference is drawn
 * rather than spelled out.
 */

/**
 * The round button that plays a word or a sound.
 *
 * Filled with the accent rather than outlined, because it is the one thing on the card that
 * does something to the world, and a reader looking for it should not have to find it.
 */
@Composable
fun PlayButton(
    palette: Tokens.Palette,
    /** What pressing it will play, for a reader who cannot see the card. */
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    report: Reporter? = null,
    /** The name this reports itself under, so a check can tell two buttons apart. */
    key: String = "play",
) {
    Box(
        modifier
            .size(Tokens.Scale.audioSize.dp)
            .clip(CircleShape)
            .background(Color(palette.accent))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .reported(key, report),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color(palette.accentInk),
            modifier = Modifier.size((Tokens.Scale.audioSize * 0.7f).dp),
        )
    }
}

/** What a machine said: the mark generated things carry across current interfaces. */
@Composable
fun SynthesisedMark(
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    report: Reporter? = null,
) {
    Icon(
        imageVector = Icons.Filled.AutoAwesome,
        contentDescription = "synthesised voice",
        tint = Color(palette.inkFaint),
        modifier = modifier
            .size(Tokens.Scale.sourceMarkSize.dp)
            .reported("sound=synthesised", report),
    )
}

/** What a person said, which is what a reader trusts most. */
@Composable
fun RecordingMark(
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    report: Reporter? = null,
) {
    Icon(
        imageVector = Icons.Filled.Mic,
        contentDescription = "recording of a speaker",
        tint = Color(palette.inkFaint),
        modifier = modifier
            .size(Tokens.Scale.sourceMarkSize.dp)
            .reported("sound=recording", report),
    )
}
