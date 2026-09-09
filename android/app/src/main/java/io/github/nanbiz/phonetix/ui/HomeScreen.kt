@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.nanbiz.phonetix.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nanbiz.phonetix.core.Dictionary
import io.github.nanbiz.phonetix.core.Frequency
import io.github.nanbiz.phonetix.core.Settings
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import io.github.nanbiz.phonetix.core.SettingsStore
import androidx.compose.material3.OutlinedTextField

private const val PREVIEW_TEXT =
    "Reading a paragraph teaches pronunciation quietly, because every unfamiliar word " +
        "arrives already spoken."

@Composable
fun HomeScreen(
    settings: Settings,
    accessibilityOn: Boolean,
    overlayOn: Boolean,
    dictionaryReady: Boolean,
    onEnabled: (Boolean) -> Unit,
    onDensity: (Int) -> Unit,
    onTarget: (String) -> Unit,
    onLayer: (String) -> Unit,
    onTouchWords: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenApps: () -> Unit,
) {
    val ready = accessibilityOn && overlayOn
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Header()

        MasterCard(
            enabled = settings.enabled && ready,
            ready = ready,
            onEnabled = onEnabled,
        )

        AnimatedVisibility(visible = !ready) {
            SetupCard(
                accessibilityOn = accessibilityOn,
                overlayOn = overlayOn,
                onOpenAccessibility = onOpenAccessibility,
                onOpenOverlay = onOpenOverlay,
            )
        }

        FrequencyCard(
            density = settings.density,
            dictionaryReady = dictionaryReady,
            onDensity = onDensity,
        )

        ReadingCard(settings = settings, onTarget = onTarget, onLayer = onLayer)
        DictionariesCard(settings = settings)
        TouchCard(on = settings.touchWords, onTouchWords = onTouchWords)
        AppsCard(settings = settings, onOpenApps = onOpenApps)

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Wordmark(size = 34)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Phonetix", style = MaterialTheme.typography.displaySmall)
            Text(
                "Pronunciation, over the words you read",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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

@Composable
private fun MasterCard(enabled: Boolean, ready: Boolean, onEnabled: (Boolean) -> Unit) {
    val bg by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "master",
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (enabled) "Transcribing" else "Paused",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    when {
                        !ready -> "Finish setup below to switch on"
                        enabled -> "Words are being transcribed in your apps"
                        else -> "Nothing is drawn over your apps"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, enabled = ready, onCheckedChange = onEnabled)
        }
    }
}

@Composable
private fun SetupCard(
    accessibilityOn: Boolean,
    overlayOn: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
) {
    SectionCard(title = "Setup") {
        PermissionRow(
            title = "Reading the screen",
            body = "Lets Phonetix see the words in other apps. Nothing leaves your device.",
            granted = accessibilityOn,
            action = "Open accessibility",
            onAction = onOpenAccessibility,
        )
        Spacer(Modifier.height(12.dp))
        PermissionRow(
            title = "Drawing over apps",
            body = "Lets it paint the transcription on top of the word.",
            granted = overlayOn,
            action = "Allow overlay",
            onAction = onOpenOverlay,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        StatusDot(granted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onAction, shape = RoundedCornerShape(12.dp)) { Text(action) }
            }
        }
        if (granted) {
            Text(
                "On",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatusDot(on: Boolean) {
    val c by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primary else Brand.crimson, label = "dot",
    )
    Box(
        Modifier
            .padding(top = 6.dp)
            .size(10.dp)
            .clip(RoundedCornerShape(50))
            .background(c),
    )
}

/**
 * Whether the transcriptions themselves can be touched.
 *
 * They are windows lying over the words. A finger that comes down on one comes down on it and
 * not on the app underneath, and a window that has taken a gesture keeps it - so with this on,
 * a swipe that begins on a transcription does not scroll the page. Most of a page of text is
 * transcriptions. That is the whole of the trade, and it is put plainly here because a reader
 * who turns it on and then cannot scroll would have no way of guessing why.
 */
/**
 * What the reader is reading, and what they want over a word.
 *
 * Without a language to read into, a word can only answer with how it is said: the dictionary
 * needs to know which language the answer should come back in. That is the whole product, so
 * it is asked for here rather than buried.
 */
@Composable
private fun ReadingCard(
    settings: Settings,
    onTarget: (String) -> Unit,
    onLayer: (String) -> Unit,
) {
    SectionCard(title = "Reading into") {
        val named = remember {
            io.github.nanbiz.phonetix.core.Languages.all()
                .map { it to io.github.nanbiz.phonetix.core.Languages.english(it) }
                .sortedBy { it.second }
        }
        var open by remember { mutableStateOf(false) }
        val chosen = settings.target.ifEmpty { null }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (chosen == null) {
                    "Nothing yet - words answer with how they are said"
                } else {
                    io.github.nanbiz.phonetix.core.Languages.english(chosen)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { open = true }) { Text(if (chosen == null) "Choose" else "Change") }
        }
        if (open) {
            // A plain list rather than a menu: sixty languages in a dropdown is a list that
            // scrolls off the screen either way, and this one can be read.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for ((code, english) in named) {
                    TextButton(onClick = { onTarget(code); open = false }) {
                        Text(english, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Over a word", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            for ((value, label) in listOf(
                "gloss" to "meaning",
                "gloss+ipa" to "both",
                "ipa" to "sound",
                "replace" to "in place",
            )) {
                TextButton(onClick = { onLayer(value) }) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (settings.layer == value) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * The dictionaries this phone has, and the ones it could have.
 *
 * Nothing is fetched because a screen happened to be in a language: a dictionary is tens of
 * megabytes, and which ones are worth that is the reader's decision.
 */
@Composable
private fun DictionariesCard(settings: Settings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var held by remember { mutableStateOf(io.github.nanbiz.phonetix.core.Packs.held(context)) }
    var offered by remember {
        mutableStateOf(emptyList<io.github.nanbiz.phonetix.core.Offered>())
    }
    var busy by remember { mutableStateOf("") }
    var host by remember { mutableStateOf(settings.packHost) }

    androidx.compose.runtime.LaunchedEffect(settings.packHost) {
        offered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.nanbiz.phonetix.core.Packs.offered(settings.packHost)
        }
    }

    SectionCard(title = "Dictionaries") {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Where they come from") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (host != settings.packHost) {
            TextButton(onClick = { SettingsStore.setPackHost(host) }) { Text("Use this") }
        }
        if (offered.isEmpty()) {
            Text(
                if (settings.packHost.isBlank()) {
                    "No source for them yet"
                } else {
                    "Nothing offered there"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (pack in offered) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        io.github.nanbiz.phonetix.core.Languages.english(pack.lang),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${pack.entries} words · ${pack.bytes / 1024 / 1024} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    busy == pack.lang -> Text(
                        "fetching",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pack.lang in held -> TextButton(onClick = {
                        io.github.nanbiz.phonetix.core.Packs.forget(context, pack.lang)
                        held = io.github.nanbiz.phonetix.core.Packs.held(context)
                    }) { Text("Remove") }
                    else -> TextButton(onClick = {
                        busy = pack.lang
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                io.github.nanbiz.phonetix.core.Packs.get(
                                    context, settings.packHost, pack.lang,
                                )
                            }
                            held = io.github.nanbiz.phonetix.core.Packs.held(context)
                            busy = ""
                        }
                    }) { Text("Get") }
                }
            }
        }
    }
}

@Composable
private fun TouchCard(on: Boolean, onTouchWords: (Boolean) -> Unit) {
    SectionCard(title = "Touching a word") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (on) "Press a word for its sounds" else "Words are not touchable",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (on) {
                        "Hold a transcription to open its card. While this is on, a swipe " +
                            "that starts on a transcription will not scroll the page."
                    } else {
                        "Every touch goes to the app underneath, so scrolling is untouched. " +
                            "Turn this on to open a word's card by holding it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = onTouchWords)
        }
    }
}

@Composable
private fun FrequencyCard(density: Int, dictionaryReady: Boolean, onDensity: (Int) -> Unit) {
    SectionCard(title = "How often") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Frequency",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Pill(Frequency.label(density))
        }
        Slider(
            value = Frequency.posForDensity(density),
            onValueChange = { onDensity(Frequency.densityForPos(it)) },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("A few words")
            Label("Every word")
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Preview",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Preview(density = density, ready = dictionaryReady)
    }
}

/**
 * The setting applied to a real sentence, using the same dictionary and the same word
 * choice the overlay uses — so the slider shows what it will actually do rather than
 * asking the reader to imagine it.
 */
@Composable
private fun Preview(density: Int, ready: Boolean) {
    val gold = Brand.gold
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(density, ready) {
        if (!ready) null else buildAnnotatedString {
            val tokens = io.github.nanbiz.phonetix.core.Reading.annotate(
                listOf(PREVIEW_TEXT),
                source = "en",
                target = "en",
                mode = "ipa",
                density = density,
            ).filter { it.inline && it.ipa.isNotEmpty() }
            var i = 0
            for (t in tokens) {
                val at = t.start
                if (at > i) append(PREVIEW_TEXT.substring(i, at))
                if (t.ipa.isNotEmpty()) {
                    withStyle(SpanStyle(color = gold, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)) {
                        append(t.ipa)
                    }
                }
                i = t.end
            }
            if (i < PREVIEW_TEXT.length) append(PREVIEW_TEXT.substring(i))
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        if (text == null) {
            Text("Loading the dictionary…", style = MaterialTheme.typography.bodyMedium, color = muted)
        } else {
            Text(text, style = MaterialTheme.typography.bodyMedium, lineHeight = 24.sp)
        }
    }
}

@Composable
private fun AppsCard(settings: Settings, onOpenApps: () -> Unit) {
    SectionCard(title = "Where") {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenApps)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Apps", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (settings.allApps) "Every app"
                    else "${settings.apps.size} app${if (settings.apps.size == 1) "" else "s"} chosen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Change", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.height(12.dp))
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
