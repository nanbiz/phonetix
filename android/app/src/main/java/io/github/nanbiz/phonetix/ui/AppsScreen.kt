@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nanbiz.phonetix.core.Settings

data class AppEntry(val pkg: String, val label: String)

@Composable
fun AppsScreen(
    settings: Settings,
    apps: List<AppEntry>,
    onBack: () -> Unit,
    onAllApps: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                "Apps",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            SectionCard(title = "Scope") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Every app", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Off means only the apps you tick below",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.allApps, onCheckedChange = onAllApps)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(shown, key = { it.pkg }) { app ->
                AppRow(
                    app = app,
                    checked = settings.allApps || app.pkg in settings.apps,
                    enabled = !settings.allApps,
                    onToggle = { onToggle(app.pkg) },
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(app.label)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = { onToggle() })
    }
}

/**
 * A letter tile rather than the real launcher icon: pulling and decoding every app's
 * drawable to paint a list costs far more than the list is worth, and the tint keyed to
 * the name still makes rows easy to pick out while scrolling.
 */
@Composable
private fun Avatar(label: String) {
    val hue = (label.hashCode().toUInt() % 360u).toFloat()
    val tint = Color.hsv(hue, 0.35f, if (MaterialTheme.colorScheme.background.luminanceIsDark()) 0.45f else 0.80f)
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.firstOrNull()?.uppercase() ?: "?",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
