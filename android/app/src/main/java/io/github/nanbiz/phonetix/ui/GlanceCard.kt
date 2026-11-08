package io.github.nanbiz.phonetix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nanbiz.phonetix.core.Answer
import io.github.nanbiz.phonetix.core.Wording

/**
 * What the side button shows about the word it is over, read at a glance while the finger
 * is still on the screen.
 *
 * Display only: it takes no touch and is gone when the button is let go, so nothing on it is
 * a control. It says how the word is said and then what it means: the word it means, the
 * kind of word and what else it can mean. A word in a language the reader reads is not
 * translated, so its card says how it is said, and failing that what the dictionary says it is.
 *
 * The word itself heads it in small type, so a reader dragging over a line knows which word
 * the answer is for.
 */
@Composable
fun GlanceCard(
    answer: Answer,
    palette: Tokens.Palette,
    meaning: Boolean,
    modifier: Modifier = Modifier,
    report: Reporter? = null,
    narrow: Boolean = false,
    /** Whether the word is read in the language its dictionary defines it in, so that what the
     *  dictionary says is a definition rather than a translation, and goes under how it is said. */
    defining: Boolean = false,
    /** The parts the reader asked the card to say, under Look up: how the word is said, which
     *  kind and form of word it is, and the other words its spelling can be. */
    sound: Boolean = true,
    grammar: Boolean = true,
    others: Boolean = true,
) {
    // At the detail the reader asked for: the dictionary's narrow transcription, between
    // brackets, where they asked for narrow ones and it gives one; the broad one otherwise.
    val detailed = narrow && !answer.narrow.isNullOrBlank()
    val ipa = (if (detailed) answer.narrow else answer.ipa.firstOrNull())
        ?.takeIf { it.isNotBlank() && sound }
    val written = ipa?.let { if (detailed) "[$it]" else "/$it/" }
    // What the word is led by: the word the core picks, which is the reader's word for it or,
    // read into English, the first gloss that is a meaning rather than a note about grammar.
    // A form leads with its meaning said in that form: "anduvo" is "he walked", where the
    // dictionary's meaning is the infinitive's, "to walk".
    val paradigm = answer.paradigm
    // Where the reader's language has no word for it, what it is, said in theirs, in its place.
    val wordless = answer.says.isEmpty() && !answer.described.isNullOrBlank()
    val says = (if (wordless) answer.described else
        (paradigm?.said ?: answer.lead ?: answer.says.firstOrNull())?.let(::plainly))
        ?.takeIf { it.isNotBlank() }
    // And under a word their language says the same way, or under every word where they asked.
    val describedUnder = answer.described?.takeIf { !wordless && it.isNotBlank() }
    // The other words the spelling can be, where the dictionary could not tell which one the
    // page means. Decided, the rest are other words entirely: "Reifen", tyres, is also the verb
    // "reifen", to ripen, and naming it beside the tyres answered a question nobody asked.
    val also = if (answer.state != Answer.State.Homograph || !others) emptyList() else answer.readings
        .mapNotNull { reading -> reading.says.firstOrNull()?.let(::plainly) ?: reading.described }
        .filter { it != says }
        .distinct()
        .take(ALSO)
    val definition = answer.glosses.firstOrNull()?.takeIf { it.isNotBlank() }
    // Whole, since a definition is a sentence and cutting it at its first bracket or semicolon
    // leaves half of one. Read in English it is what the dictionary says; translated for
    // practice it is the English sense under the translation.
    val defined = when {
        !defining -> null
        meaning -> definition
        else -> answer.says.firstOrNull() ?: definition
    }?.takeIf { it.isNotBlank() }
    // The readings said differently from each other, where the page left it open which one.
    val spoken = if (answer.state != Answer.State.Homograph) emptyList() else answer.readings
        .filter { it.ipa.firstOrNull()?.isNotBlank() == true }
        .distinctBy { it.ipa.first() }
        .take(ALSO + 1)
    val guessed = answer.state == Answer.State.Guess

    Column(
        modifier = modifier
            .widthIn(min = 120.dp, max = Tokens.Scale.cardWidth.dp)
            .clip(RoundedCornerShape(Tokens.Scale.radiusCard.dp))
            .background(Color(palette.surface))
            .padding(horizontal = Tokens.Scale.space4.dp, vertical = Tokens.Scale.space3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The word, its own ending in the accent where it is a form of another word, and how it
        // is said on the same line.
        Row(verticalAlignment = Alignment.Bottom) {
            val ending = paradigm?.endingAt?.takeIf { it in 1 until answer.spelling.length }
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    if (ending == null) {
                        append(answer.spelling)
                    } else {
                        append(answer.spelling.substring(0, ending))
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(palette.accent)))
                        append(answer.spelling.substring(ending))
                        pop()
                    }
                },
                color = Color(palette.inkMuted),
                fontSize = Tokens.Scale.fontSizeSmall.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.reported(answer.spelling, report),
            )
            if (meaning && ipa != null && says != null) {
                Text(
                    text = "  $written",
                    color = Color(palette.ipa),
                    fontSize = Tokens.Scale.fontSizeSense.sp,
                    style = IpaStyle.copy(fontWeight = FontWeight.Normal),
                    maxLines = 1,
                    modifier = Modifier.reported(written.orEmpty(), report),
                )
            }
        }
        when {
            meaning && says != null -> {
                Text(
                    text = says,
                    color = Color(if (guessed) palette.inkMuted else palette.ink),
                    // A description is a sentence, set like the extension's: smaller than a word.
                    fontSize = (if (wordless) Tokens.Scale.fontSizeIpa else Tokens.Scale.fontSizeHeadline).sp,
                    fontWeight = if (wordless) FontWeight.Medium else FontWeight.SemiBold,
                    fontStyle = if (guessed) FontStyle.Italic else FontStyle.Normal,
                    maxLines = if (wordless) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.reported(says, report),
                )
                if (describedUnder != null) {
                    Text(
                        text = describedUnder,
                        color = Color(palette.ink),
                        fontSize = Tokens.Scale.fontSizeSense.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.reported(describedUnder, report),
                    )
                }
                // What the word is in its own language, under what it is in the one being
                // learnt: an English word read for practice.
                if (defined != null) {
                    Text(
                        text = defined,
                        color = Color(palette.inkMuted),
                        fontSize = Tokens.Scale.fontSizeSmall.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.reported(defined, report),
                    )
                }
                // An expression read as one: each of its words with what it is on its own,
                // which is how the pattern is learnt.
                if (answer.parts.size > 1) {
                    val line = answer.parts.joinToString(" · ") { (word, means) -> "$word $means" }
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            answer.parts.forEachIndexed { at, (word, means) ->
                                if (at > 0) append(" · ")
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(palette.ink), fontWeight = FontWeight.Medium))
                                append(word)
                                pop()
                                append(" $means")
                            }
                        },
                        color = Color(palette.inkMuted),
                        fontSize = Tokens.Scale.fontSizeSmall.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.reported(line, report),
                    )
                }
                // Which form of which word it is, where it is a form - "preterite · indicative ·
                // 3rd singular of andar" - and otherwise the kind of word and what else it can be.
                val form = paradigm?.named()?.takeIf { it.isNotEmpty() && grammar }
                val kind = if (form != null) {
                    listOf(form.joinToString(" · ") + (answer.lemma?.let { " of $it" } ?: ""))
                } else {
                    listOfNotNull(answer.pos?.takeIf { grammar }?.let(Wording::posName)) + also
                }
                if (kind.isNotEmpty()) {
                    // The word it is a form of set apart from the names of the form: it is the
                    // word to look up, the rest is what was done to it.
                    val lemma = answer.lemma?.takeIf { form != null }
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            val line = kind.joinToString(" · ")
                            if (lemma == null || !line.endsWith(" of $lemma")) {
                                append(line)
                            } else {
                                append(line.removeSuffix(lemma))
                                pushStyle(
                                    androidx.compose.ui.text.SpanStyle(
                                        color = Color(palette.accent),
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                )
                                append(lemma)
                                pop()
                            }
                            // And what is written onto its end: "infinitive of bañar + me".
                            paradigm?.joined?.takeIf { form != null }?.let { append(" + $it") }
                        },
                        color = Color(palette.inkMuted),
                        fontSize = Tokens.Scale.fontSizeSmall.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.reported(kind.joinToString(" · "), report),
                    )
                }
            }
            // A spelling that is said more than one way, and the page cannot tell which: each
            // way, with what tells it apart ("record" the noun and the verb).
            spoken.size > 1 -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (reading in spoken) {
                    val way = "/${reading.ipa.first()}/"
                    Text(
                        text = way,
                        color = Color(palette.ipa),
                        fontSize = Tokens.Scale.fontSizeIpa.sp,
                        style = IpaStyle,
                        maxLines = 1,
                        modifier = Modifier.reported(way, report),
                    )
                    val what = listOfNotNull(
                        reading.pos?.let(Wording::posName),
                        reading.glosses.firstOrNull()?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (what.isNotEmpty()) {
                        Text(
                            text = what,
                            color = Color(palette.inkMuted),
                            fontSize = Tokens.Scale.fontSizeSmall.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.reported(what, report),
                        )
                    }
                }
            }
            written != null -> {
                Text(
                    text = written,
                    color = Color(palette.ipa),
                    fontSize = Tokens.Scale.fontSizeIpaLarge.sp,
                    style = IpaStyle,
                    maxLines = 2,
                    modifier = Modifier.reported(written, report),
                )
                if (defined != null) {
                    Text(
                        text = defined,
                        color = Color(palette.ink),
                        fontSize = Tokens.Scale.fontSizeBody.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.reported(defined, report),
                    )
                }
            }
            definition != null -> Text(
                text = definition,
                color = Color(palette.ink),
                fontSize = Tokens.Scale.fontSizeBody.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.reported(definition, report),
            )
        }
    }
}

/** Whether the card has anything to say about [answer]: a card that would only repeat the
 *  word is not opened at all. */
fun glanceHasSomething(answer: Answer, meaning: Boolean, sound: Boolean = true): Boolean {
    val ipa = sound && answer.ipa.any { it.isNotBlank() }
    val says = !answer.lead.isNullOrBlank() || answer.says.any { it.isNotBlank() } ||
        !answer.described.isNullOrBlank()
    val definition = answer.glosses.any { it.isNotBlank() }
    return (meaning && says) || ipa || definition
}

/**
 * A sense as the word it offers: an English dictionary writes "dog (the species Canis
 * familiaris); hound", and at a glance the answer is "dog".
 */
fun plainly(sense: String): String {
    var out = sense
    while (true) {
        val open = out.indexOf('(')
        val close = if (open >= 0) out.indexOf(')', open) else -1
        if (open < 0 || close < 0) break
        out = out.removeRange(open, close + 1)
    }
    return out.split(';').first().trim().trimEnd('.', ',', ':').trim()
}

/** How many other meanings the card names beside the kind of word. */
private const val ALSO = 2

/** The primary and secondary stress marks. */
