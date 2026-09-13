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
import androidx.compose.foundation.shape.CircleShape
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
import io.github.nanbiz.phonetix.core.Placement
import io.github.nanbiz.phonetix.core.Reading
import io.github.nanbiz.phonetix.core.Wording
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
    onLens: (Boolean) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenApps: () -> Unit,
) {
    val ready = accessibilityOn && overlayOn
    val context = androidx.compose.ui.platform.LocalContext.current
    // Read once per composition of the screen rather than held: a dictionary arrives while
    // this screen is open, and the card that fetched it says so itself.
    val held = remember(dictionaryReady, settings.packHost) {
        io.github.nanbiz.phonetix.core.Packs.held(context)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Scale.space4.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Scale.space3.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Header()

        // Whether a word can be answered at all, which on the phone is the two permissions:
        // it ships a dictionary, so pronunciation works from the first screen. Meanings need
        // more, and say so where they are chosen rather than across the whole screen.
        val started = ready
        val meaningsReady = settings.target.isNotBlank() && held.isNotEmpty()

        MasterCard(
            enabled = settings.enabled && started,
            ready = started,
            onEnabled = onEnabled,
        )

        // What is still to be done, and nothing below it until it is done: every card under
        // here is a choice about answers that cannot be given yet, and a bar that changes
        // nothing is worse than no bar.
        AnimatedVisibility(visible = !started) {
            SetupCard(
                accessibilityOn = accessibilityOn,
                overlayOn = overlayOn,
                onOpenAccessibility = onOpenAccessibility,
                onOpenOverlay = onOpenOverlay,
            )
        }

        if (started) {
            // In the order a reader decides: what they read into, where the dictionaries come
            // from, what appears over a word, and only then how much of the page.
            ReadingCard(settings = settings, onTarget = onTarget, onLayer = onLayer)
            // One of the two, never both: the card that says what is missing carries the very
            // field the dictionaries card carries, and a reader met the same box twice under
            // two headings.
            if (meaningsReady) {
                DictionariesCard(settings = settings)
            } else {
                MeaningsCard(settings = settings, onTarget = onTarget)
            }
            FrequencyCard(density = settings.density, onDensity = onDensity)
            TranscriptionsCard(settings = settings)
            AccentsCard(settings = settings, held = held)
            SayCard(settings = settings)
            LensCard(on = settings.lens, onLens = onLens)
            TouchCard(on = settings.touchWords, onTouchWords = onTouchWords)
            AppsCard(settings = settings, onOpenApps = onOpenApps)
        }

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
                Wording.says["tagline"].orEmpty(),
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
    // Off, it is a card like the others: painted in the raised surface it was a beige slab on
    // a beige page with nothing to say where it ended.
    val bg by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        label = "master",
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(Tokens.Scale.radiusCard.dp),
        border = androidx.compose.foundation.BorderStroke(
            Tokens.Scale.borderWidth.dp,
            if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(Tokens.Scale.space4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    Wording.says[if (enabled) "master-on" else "master-off"].orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    when {
                        !ready -> Wording.says["master-unready"].orEmpty()
                        enabled -> Wording.says["master-working"].orEmpty()
                        else -> Wording.says["master-idle"].orEmpty()
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The one switch a reader opens the app to find, drawn larger than the rest and
            // the same switch the extension's popup puts at the top of its own view.
            Switch(
                on = enabled,
                label = Wording.row("on").name,
                palette = palette(),
                big = true,
                enabled = ready,
                change = onEnabled,
            )
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
    SectionCard(title = Wording.row("start").name) {
        Text(
            Wording.row("start").about,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PermissionRow(
            title = Wording.row("setup-reading").name,
            body = Wording.row("setup-reading").about,
            granted = accessibilityOn,
            action = Wording.says["open-accessibility"].orEmpty(),
            onAction = onOpenAccessibility,
        )
        Spacer(Modifier.height(12.dp))
        PermissionRow(
            title = Wording.row("setup-overlay").name,
            body = Wording.row("setup-overlay").about,
            granted = overlayOn,
            action = Wording.says["allow-overlay"].orEmpty(),
            onAction = onOpenOverlay,
        )
    }
}

/**
 * What is still missing before a word's meaning can be shown, where the reader chooses to
 * show it.
 *
 * Not a gate across the screen: this phone ships a dictionary and answers how a word is said
 * out of it from the first screen. Meanings are the part that needs a language to read into
 * and a dictionary for the language being read, and a reader who wants only pronunciation
 * never has to do either.
 */
@Composable
private fun MeaningsCard(settings: Settings, onTarget: (String) -> Unit) {
    val colours = palette()
    SectionCard(title = Wording.row("meanings").name) {
        Text(
            Wording.row("meanings").about,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.target.isBlank()) {
            Spacer(Modifier.height(12.dp))
            SettingRow(
                name = Wording.says["start-target"].orEmpty(),
                palette = colours,
                about = Wording.row("target").about,
                wide = { LanguagePicker(chosen = settings.target, change = onTarget) },
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(Wording.says["start-pack"].orEmpty(), style = MaterialTheme.typography.titleMedium)
        DictionariesBody(settings)
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
/** The language a reader reads into, offered the same way wherever it is asked for. */
@Composable
private fun LanguagePicker(chosen: String, change: (String) -> Unit) {
    val named = remember {
        io.github.nanbiz.phonetix.core.Languages.all()
            .map { it to io.github.nanbiz.phonetix.core.Languages.english(it) }
            .sortedBy { it.second }
    }
    var open by remember { mutableStateOf(false) }
    val set = chosen.ifEmpty { null }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (set == null) {
                Wording.says["nothing-yet"].orEmpty()
            } else {
                io.github.nanbiz.phonetix.core.Languages.english(set)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { open = true }) { Text(if (set == null) "Choose" else "Change") }
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
                TextButton(onClick = { change(code); open = false }) {
                    Text(english, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ReadingCard(
    settings: Settings,
    onTarget: (String) -> Unit,
    onLayer: (String) -> Unit,
) {
    SectionCard(title = Wording.row("target").name) {
        LanguagePicker(chosen = settings.target, change = onTarget)
        Spacer(Modifier.height(8.dp))
        Text(Wording.row("layer").name, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        // What each choice does to the screen, in its own words, from data/choices.json: the
        // name of one is not a choice a reader can make, and the extension says the same
        // words because both are written out of the same table.
        Choices(
            options = Wording.layer,
            chosen = settings.layer,
            palette = palette(),
            change = onLayer,
        )
    }
}

/**
 * How much of how a word is said is drawn over it.
 *
 * Both are the extension's own settings and were never on the phone, which meant the two
 * products showed the same reader different transcriptions of the same word.
 */
@Composable
private fun TranscriptionsCard(settings: Settings) {
    SectionCard(title = Wording.row("more").name) {
        val colours = palette()
        val detail = Wording.detail
        val chosen = if (settings.narrow) "narrow" else "broad"
        SettingRow(
            name = Wording.row("narrow").name,
            palette = colours,
            about = Wording.aboutOf(detail, chosen),
            control = {
                Segmented(
                    choices = detail.map { it.value to it.label },
                    chosen = chosen,
                    palette = colours,
                    change = { SettingsStore.setNarrow(it == "narrow") },
                )
            },
        )
        SettingRow(
            name = Wording.row("stress").name,
            palette = colours,
            about = Wording.row("stress").about,
            control = {
                Switch(
                    on = !settings.hideStress,
                    label = "stress marks",
                    palette = colours,
                    change = { SettingsStore.setHideStress(!it) },
                )
            },
        )
    }
}

/**
 * Which accent each language is read in.
 *
 * Only the languages that really offer a choice - a voice that exists, or a rule that holds
 * for a whole vocabulary - because a list of accents that all sound the same is a list of
 * promises. Per language, since an accent means nothing except relative to one, and until now
 * the phone had no way to choose one at all while the extension did.
 */
@Composable
private fun AccentsCard(settings: Settings, held: List<String>) {
    // Only for languages this phone holds a dictionary for. The table knows an accent list for
    // a dozen languages; offering all of them to a reader who holds one dictionary is a screen
    // of choices about words the phone cannot answer.
    val offered = remember(held) {
        io.github.nanbiz.phonetix.core.Accents.all
            .filter { (lang, list) -> list.size > 1 && lang in held }
            .toList()
            .sortedBy { io.github.nanbiz.phonetix.core.Languages.english(it.first) }
    }
    if (offered.isEmpty()) return
    SectionCard(title = Wording.row("accent").name + "s") {
        val colours = palette()
        for ((lang, accents) in offered) {
            val chosen = settings.accentFor(lang)
            SettingRow(
                name = io.github.nanbiz.phonetix.core.Languages.english(lang),
                palette = colours,
                about = accents.firstOrNull { it.id == chosen }?.let {
                    if (it.rule) {
                        "${it.name} - derived from its pronunciation rules, applied to every word"
                    } else {
                        it.name
                    }
                } ?: "as the dictionary gives it",
                wide = {
                    Segmented(
                        choices = listOf("" to "dictionary") +
                            accents.map { it.id to it.name },
                        chosen = chosen,
                        palette = colours,
                        change = { SettingsStore.setAccent(lang, it) },
                    )
                },
            )
        }
    }
}

/** The colours this screen is drawn in, which are the ones Material got as well. */
@Composable
private fun palette(): Tokens.Palette = appPalette()

/**
 * The dictionaries this phone has, and the ones it could have.
 *
 * Nothing is fetched because a screen happened to be in a language: a dictionary is tens of
 * megabytes, and which ones are worth that is the reader's decision.
 */
@Composable
private fun DictionariesCard(settings: Settings) {
    SectionCard(title = Wording.row("dictionaries").name) { DictionariesBody(settings) }
}

@Composable
private fun DictionariesBody(settings: Settings) {
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

    run {
        val colours = palette()
        SettingRow(
            name = Wording.row("host").name,
            palette = colours,
            about = if (settings.packHost.isBlank()) {
                Wording.says["no-host"].orEmpty()
            } else {
                Wording.row("host").about
            },
            wide = {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    singleLine = true,
                    shape = RoundedCornerShape(Tokens.Scale.radiusButton.dp),
                    placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        if (host != settings.packHost) {
            TextButton(onClick = { SettingsStore.setPackHost(host) }) {
                Text(Wording.says["use-this"].orEmpty())
            }
        }
        if (offered.isEmpty() && settings.packHost.isNotBlank()) {
            Text(
                "nothing on offer at ${settings.packHost}",
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
                    }) { Text(Wording.says["remove"].orEmpty()) }
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
                    }) { Text(Wording.says["get"].orEmpty()) }
                }
            }
        }
    }
}

/**
 * The lens, and why it is the way in.
 *
 * The transcriptions lie over the words. Making them touchable is one way to ask about a
 * word and costs every swipe that starts on one; the lens costs nothing and is dragged to
 * whatever the reader wants to know about.
 */
/**
 * The other direction: the word for something the reader wants to say.
 *
 * Everything else in this app answers a word somebody else wrote. This one answers a word the
 * reader is looking for, and answers it with the same card, so what a machine handed over is
 * judged the way every other answer is: how it is said, what it means back, which sounds are
 * in it.
 */
@Composable
private fun SayCard(settings: Settings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colours = palette()
    var wanted by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<io.github.nanbiz.phonetix.core.Answer?>(null) }
    var nothing by remember { mutableStateOf(false) }
    /** Whether there is no model for the direction at all, which is a different thing to be
     *  told than that no word came back. */
    var unmodelled by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // What the reader already has is what they read into. What they are learning is whatever
    // they keep a dictionary for: the phone has no page in front of it to read a language off
    // while this screen is open, and a reader with one pack is learning that language.
    val have = settings.target
    val held = remember { io.github.nanbiz.phonetix.core.Packs.held(context).filter { it != have } }
    var learning by remember(held) { mutableStateOf(held.firstOrNull().orEmpty()) }

    SectionCard(title = Wording.row("say").name) {
        if (held.size > 1) {
            SettingRow(
                name = Wording.row("source").name,
                palette = colours,
                about = Wording.row("source").about,
                wide = {
                    Segmented(
                        choices = held.map {
                            it to io.github.nanbiz.phonetix.core.Languages.english(it)
                        },
                        chosen = learning,
                        palette = colours,
                        change = { learning = it },
                    )
                },
            )
        }
        SettingRow(
            name = "",
            palette = colours,
            about = if (learning.isBlank() || have.isBlank() || learning == have) {
                Wording.says["say-no-language"].orEmpty()
            } else {
                Wording.row("say").about
            },
            wide = {
                OutlinedTextField(
                    value = wanted,
                    onValueChange = { wanted = it },
                    singleLine = true,
                    enabled = learning.isNotBlank() && have.isNotBlank() && learning != have,
                    shape = RoundedCornerShape(Tokens.Scale.radiusButton.dp),
                    placeholder = { Text(Wording.says["say-placeholder"].orEmpty()) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = {
                            val asked = wanted.trim()
                            if (asked.isEmpty()) return@KeyboardActions
                            // The keyboard has done its work, and the answer appears where it
                            // was standing: asked with it up, the card came back underneath it
                            // and the reader saw an empty field and nothing else.
                            keyboard?.hide()
                            asking = true
                            said = null
                            nothing = false
                            unmodelled = false
                            scope.launch {
                                // Off the main thread: the engine opens a model of seventeen
                                // megabytes for the direction nobody has been reading in.
                                val answer = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) {
                                    Reading.say(context, asked, learning, have)
                                }
                                said = answer
                                unmodelled = answer == null &&
                                    !io.github.nanbiz.phonetix.core.Translator.ready(
                                        io.github.nanbiz.phonetix.core.Packs.models(context),
                                        have, learning,
                                    )
                                nothing = answer == null && !unmodelled
                                asking = false
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        if (asking) {
            Text(
                "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        said?.let { answer ->
            AnswerCard(answer = answer, palette = colours)
        }
        if (unmodelled) {
            Text(
                Wording.says["say-no-model"].orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (nothing) {
            Text(
                Wording.says["say-nothing"].orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LensCard(on: Boolean, onLens: (Boolean) -> Unit) {
    val colours = palette()
    SectionCard(title = Wording.row("lens").name) {
        SettingRow(
            name = Wording.row("lens-drag").name,
            palette = colours,
            about = Wording.row("lens-drag").about,
            control = {
                Switch(
                    on = on,
                    label = "the lens",
                    palette = colours,
                    change = onLens,
                )
            },
        )
        // What else the one mark answers. A gesture nothing on screen describes is a gesture
        // nobody finds: the drag was the only one a reader could discover by trying.
        if (on) {
            for (row in listOf("lens-hold", "lens-tap")) {
                Spacer(Modifier.height(Tokens.Scale.space2.dp))
                Text(
                    "${Wording.row(row).name}. ${Wording.row(row).about}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TouchCard(on: Boolean, onTouchWords: (Boolean) -> Unit) {
    val colours = palette()
    SectionCard(title = Wording.row("touch").name) {
        SettingRow(
            name = Wording.row("touch-words").name,
            palette = colours,
            about = Wording.says[if (on) "touch-on" else "touch-off"].orEmpty(),
            control = {
                Switch(
                    on = on,
                    label = "touching a word",
                    palette = colours,
                    change = onTouchWords,
                )
            },
        )
    }
}

@Composable
private fun FrequencyCard(density: Int, onDensity: (Int) -> Unit) {
    SectionCard(title = Wording.row("density").name) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // What the bar is for, in the words the extension uses for it: the card's own
            // title said the same thing twice before, once as a heading and once as a name.
            Text(
                Wording.row("density").about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Pill(Frequency.label(density))
        }
        // The bar the extension draws: a filled track and a round thumb. Material's own thumb
        // is a tall bar with a gap either side of it, which beside everything else here read
        // as a control that had come apart.
        Slider(
            value = Frequency.posForDensity(density),
            onValueChange = { onDensity(Frequency.densityForPos(it)) },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
            thumb = {
                Box(
                    Modifier
                        .size(Tokens.Scale.audioSize.dp * 0.72f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline,
                    ),
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                    drawStopIndicator = null,
                    modifier = Modifier.height(Tokens.Scale.progressHeight.dp),
                )
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(Wording.ends["density"]!!.first)
            Label(Wording.ends["density"]!!.second)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "${Wording.says["preview"]}: ${Wording.says["preview-about"]}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Preview(density = density)
    }
}

/**
 * Which words the bar would answer, marked in a sentence.
 *
 * The same choice the overlay makes - the core's own, word by word - and nothing more than
 * that. It used to draw the sentence with every chosen word replaced by its transcription,
 * which was wrong twice over: the sentence is in the reader's own language, so a real page
 * never looks like that, and it showed transcriptions to a reader whose setting says to show
 * meanings. What the bar decides is how many words are answered, so that is what it shows.
 */
@Composable
private fun Preview(density: Int) {
    val gold = Brand.gold
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(density) {
        buildAnnotatedString {
            val seen = HashMap<String, Int>()
            var i = 0
            Placement.scanWords(PREVIEW_TEXT) { start, end ->
                if (start > i) append(PREVIEW_TEXT.substring(i, start))
                val word = PREVIEW_TEXT.substring(start, end)
                val nth = seen.getOrDefault(word.lowercase(), 0)
                seen[word.lowercase()] = nth + 1
                val picked = runCatching {
                    io.github.nanbiz.phonetix.core.Lex.picks(word, nth, density)
                }.getOrDefault(false)
                if (picked) {
                    withStyle(SpanStyle(color = gold, fontWeight = FontWeight.Bold)) {
                        append(word)
                    }
                } else {
                    append(word)
                }
                i = end
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
        Text(text, style = MaterialTheme.typography.bodyMedium, lineHeight = 24.sp, color = muted)
    }
}

@Composable
private fun AppsCard(settings: Settings, onOpenApps: () -> Unit) {
    SectionCard(title = Wording.row("where").name) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenApps)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(Wording.row("apps").name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (settings.allApps) {
                        Wording.says["every-app"].orEmpty()
                    } else {
                        "${settings.apps.size} app${if (settings.apps.size == 1) "" else "s"} chosen"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Wording.says["change"].orEmpty(), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
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
