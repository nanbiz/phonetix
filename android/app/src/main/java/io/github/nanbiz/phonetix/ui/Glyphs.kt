package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The small drawings the card uses in place of words.
 *
 * Each is a path in the same twenty-unit box the design page's icons are drawn in, scaled to
 * whatever size it is given, so a mark keeps its proportions at any density and needs no
 * bitmap. They are drawn here rather than pulled from an icon library because the two that
 * matter, a voice a machine made and a voice a person recorded, are not in one.
 */

/** The path of a play triangle, in the twenty-unit box the design page's own icon uses. */
private fun DrawScope.playTriangle(): Path {
    val unit = size.minDimension / 20f
    return Path().apply {
        moveTo(4f * unit, 2f * unit)
        lineTo(18f * unit, 10f * unit)
        lineTo(4f * unit, 18f * unit)
        close()
    }
}

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
            .clickable(onClickLabel = description) { onClick() }
            .semantics { contentDescription = description }
            .reported(key, report),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size((Tokens.Scale.iconSize * 0.6f).dp)) {
            // A triangle centred by its bounding box reads as sitting left of centre, which is
            // why the same glyph on the design page is nudged right inside its circle.
            translate(left = size.width * 0.08f) {
                drawPath(playTriangle(), color = Color(palette.accentInk))
            }
        }
    }
}

/**
 * What made the sound that button will play.
 *
 * Two different things arrive through the same button: a voice a machine generated from the
 * transcription, and a recording of a person saying the word. A reader deciding how much to
 * trust what they hear is owed the difference, and it is a property of the sound rather than a
 * sentence, so it is a mark beside the button rather than a word taking a line of the card.
 */
@Composable
fun SynthesisedMark(
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    report: Reporter? = null,
) {
    Canvas(
        modifier
            .size(Tokens.Scale.sourceMarkSize.dp)
            .semantics { contentDescription = "synthesised voice" }
            .reported("sound=synthesised", report),
    ) {
        val unit = size.minDimension / 20f
        // A four-pointed spark: the mark generated things carry across current interfaces.
        val spark = Path().apply {
            moveTo(10f * unit, 1f * unit)
            cubicTo(11f * unit, 5f * unit, 15f * unit, 9f * unit, 19f * unit, 10f * unit)
            cubicTo(15f * unit, 11f * unit, 11f * unit, 15f * unit, 10f * unit, 19f * unit)
            cubicTo(9f * unit, 15f * unit, 5f * unit, 11f * unit, 1f * unit, 10f * unit)
            cubicTo(5f * unit, 9f * unit, 9f * unit, 5f * unit, 10f * unit, 1f * unit)
            close()
        }
        drawPath(spark, color = Color(palette.inkFaint))
    }
}

/** The same, for a sound a person actually said into a microphone. */
@Composable
fun RecordingMark(
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    report: Reporter? = null,
) {
    Canvas(
        modifier
            .size(Tokens.Scale.sourceMarkSize.dp)
            .semantics { contentDescription = "recording of a speaker" }
            .reported("sound=recording", report),
    ) {
        val unit = size.minDimension / 20f
        val ink = Color(palette.inkFaint)
        val line = Stroke(width = 1.6f * unit)
        // Narrower and taller than a mic is usually drawn. The head is a capsule, and what
        // makes it read as one rather than as a ring is the straight part of its sides: at
        // the size this mark is drawn, a 6-by-10 head leaves about two pixels of straight
        // edge, and the whole thing came out looking like a lollipop.
        drawRoundRect(
            color = ink,
            topLeft = Offset(7.5f * unit, 1.5f * unit),
            size = Size(5f * unit, 11f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
            style = line,
        )
        drawArc(
            color = ink,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(4.5f * unit, 5f * unit),
            size = Size(11f * unit, 11f * unit),
            style = line,
        )
        drawLine(
            color = ink,
            start = Offset(10f * unit, 16f * unit),
            end = Offset(10f * unit, 19f * unit),
            strokeWidth = 1.8f * unit,
        )
    }
}
