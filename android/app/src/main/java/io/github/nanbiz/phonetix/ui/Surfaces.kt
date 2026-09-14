package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What is left of a settings screen written in Compose.
 *
 * The screen itself is the product's own, shared with the extension and drawn in a web view
 * (see [SettingsWeb]). These are what the app still draws natively: the mark, and the card the
 * system's app list is laid out on.
 */

/** The icon's mark, set in type rather than shipped as another bitmap. */
@Composable
fun Wordmark(size: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("[", style = IpaStyle.copy(fontSize = size.sp, color = Brand.crimson))
        Text("ɤ", style = IpaStyle.copy(fontSize = (size * 0.9f).sp, color = Brand.gold),
            modifier = Modifier.padding(horizontal = 1.dp))
        Text("]", style = IpaStyle.copy(fontSize = size.sp, color = Brand.crimson))
    }
}

/**
 * One section of the screen: a surface with a border, on the page's own ground.
 *
 * The card used to be drawn in the raised surface colour on the page colour, and in this
 * palette those are within a few percent of each other: eight cards of beige on beige, with
 * nothing to say where one ended and the next began. A surface and a line is what the
 * extension's panel is, and it is what separates a card from the page here too.
 */
@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(Tokens.Scale.radiusCard.dp),
        border = androidx.compose.foundation.BorderStroke(
            Tokens.Scale.borderWidth.dp,
            MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Scale.space4.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(Tokens.Scale.space2.dp))
            content()
        }
    }
}

@Composable
fun Pill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
