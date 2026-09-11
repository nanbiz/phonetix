package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The controls a settings screen is made of, drawn from the design page's own tokens.
 *
 * The same vocabulary the extension's settings view is built from - a row, a switch, a
 * segmented control - so a reader who has used one recognises the other. What each looks like
 * is decided once, on the surface page, and generated into [Tokens]; nothing here picks a
 * colour or a size of its own.
 */

/** One line of a settings screen: what it is, what it does, and the control that changes it. */
@Composable
fun SettingRow(
    name: String,
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    /** A sentence under the name, where the name alone does not say enough. */
    about: String = "",
    control: (@Composable () -> Unit)? = null,
    /** A control that needs the whole width, such as a bar. */
    wide: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = Tokens.Scale.space3.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Scale.space1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = Color(palette.ink),
                    fontSize = Tokens.Scale.fontSizeBody.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (about.isNotBlank()) {
                    Text(
                        text = about,
                        color = Color(palette.inkMuted),
                        fontSize = Tokens.Scale.fontSizeSmall.sp,
                    )
                }
            }
            if (control != null) {
                Box(Modifier.width(Tokens.Scale.space3.dp))
                control()
            }
        }
        if (wide != null) wide()
    }
}

/**
 * A switch: on or off, and nothing in between.
 *
 * The knob is drawn against its own track rather than against the screen, because painted in
 * the surface colour it disappeared into both palettes.
 */
@Composable
fun Switch(
    on: Boolean,
    label: String,
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    /** Larger, for the one switch a reader opens the app to find. */
    big: Boolean = false,
    /** Off, when the thing it switches is not ready to be switched on yet. */
    enabled: Boolean = true,
    change: (Boolean) -> Unit,
) {
    val height = if (big) Tokens.Scale.toggleHeightLg else Tokens.Scale.toggleHeight
    val width = if (big) Tokens.Scale.toggleWidthLg else Tokens.Scale.toggleWidth
    val knob = height - (if (big) 8f else 6f)
    val edge = if (big) 4f else 3f
    Box(
        modifier
            .width(width.dp)
            .height(height.dp)
            .clip(RoundedCornerShape(Tokens.Scale.radiusChip.dp))
            .background(Color(if (on) palette.accent else palette.chipBg))
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { change(!on) }
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .padding(
                    start = if (on) (width - height + edge).dp else edge.dp,
                    top = edge.dp,
                )
                .width(knob.dp)
                .height(knob.dp)
                .clip(RoundedCornerShape(knob.dp))
                .background(Color(if (on) palette.accentInk else palette.inkMuted)),
        )
    }
}

/**
 * One choice out of a few, all of them visible.
 *
 * With four or five short answers, showing them is faster to read than opening something to
 * find out what they are.
 */
@Composable
fun Segmented(
    choices: List<Pair<String, String>>,
    chosen: String,
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    change: (String) -> Unit,
) {
    Box(
        modifier
            .border(
                Tokens.Scale.borderWidth.dp,
                Color(palette.border),
                RoundedCornerShape(Tokens.Scale.radiusButton.dp),
            )
            .clip(RoundedCornerShape(Tokens.Scale.radiusButton.dp)),
    ) {
        // The choices scroll rather than clip: a language with six accents does not fit across
        // a phone, and a control whose last choice is off the edge is a choice nobody can make.
        // The frame stays put, because it is the shape of the control and not of its contents.
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for ((value, label) in choices) {
                val on = value == chosen
                Box(
                    Modifier
                        .height((Tokens.Scale.buttonHeight - 8f).dp)
                        .background(Color(if (on) palette.accent else palette.surface))
                        .clickable { change(value) }
                        .padding(horizontal = Tokens.Scale.space2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = Color(if (on) palette.accentInk else palette.inkMuted),
                        fontSize = Tokens.Scale.fontSizeLabel.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * One choice out of a handful, each saying what it does.
 *
 * A row of one-word buttons is only a choice for a reader who already knows what the words
 * mean: "both" says nothing to somebody who has not worked out what the other four are. So
 * each choice carries its own sentence, and the one in force is marked.
 */
@Composable
fun Choices(
    options: List<io.github.nanbiz.phonetix.core.Choice>,
    chosen: String,
    palette: Tokens.Palette,
    modifier: Modifier = Modifier,
    change: (String) -> Unit,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Tokens.Scale.space2.dp),
    ) {
        for (option in options) {
            val (value, label, about) = option
            val on = value == chosen
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Tokens.Scale.radiusButton.dp))
                    .background(if (on) Color(palette.accentBg) else Color.Transparent)
                    .border(
                        Tokens.Scale.borderWidth.dp,
                        Color(if (on) palette.accent else palette.border),
                        RoundedCornerShape(Tokens.Scale.radiusButton.dp),
                    )
                    .clickable { change(value) }
                    .padding(
                        horizontal = Tokens.Scale.space3.dp,
                        vertical = Tokens.Scale.space2.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = label,
                        color = Color(if (on) palette.accent else palette.ink),
                        fontSize = Tokens.Scale.fontSizeSmall.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = about,
                        color = Color(if (on) palette.accent else palette.inkMuted),
                        fontSize = Tokens.Scale.fontSizeLabel.sp,
                    )
                }
                if (on) {
                    Text(
                        text = "✓",
                        color = Color(palette.accent),
                        fontSize = Tokens.Scale.fontSizeSmall.sp,
                    )
                }
            }
        }
    }
}
