package io.github.nanbiz.phonetix.service

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * What a word the reader is looking for is answered by, before they have configured anything.
 *
 * The dictionaries and the reading engine are the reader's own: they choose a host, they choose
 * what to keep, and nothing is fetched behind their back. This question comes before all of
 * that - it is asked by somebody who has just installed the app and wants the word for
 * something - so it is answered by the machine on the phone, which fetches what it needs for a
 * pair itself. It is what Taplex answered with, and the reason its panel worked out of the box.
 *
 * It also says which language something was typed in, so a reader can ask in any language they
 * please rather than in the one this app assumed.
 */
object Machine {

    /** The default threshold answers "und" for anything short; a word is short. */
    private val names = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.1f).build(),
    )

    private val engines = HashMap<String, Translator>()

    /** What has already been answered, so the same phrase is never asked twice. */
    private val known = HashMap<String, String>()

    /** Which language this was written in, or nothing when it is too short to tell. */
    suspend fun language(text: String, answerable: Set<String> = emptySet()): String? {
        if (text.isBlank()) return null
        val listed = runCatching { names.identifyPossibleLanguages(text).await() }.getOrNull()
            ?: return null
        val guesses = listed.mapNotNull { guess ->
            guess.languageTag.takeIf { it != "und" }?.substringBefore('-')
        }
        // The best guess that can actually be answered, and the outright best only when none
        // of them can: a word typed in a language nobody here holds a dictionary for is still
        // worth translating, but a language that is held wins the tie.
        return guesses.firstOrNull { it in answerable } ?: guesses.firstOrNull()
    }

    /**
     * What this says in another language, fetching what that pair needs the first time.
     *
     * Nothing where the pair is not one this machine knows, and nothing where the answer is
     * the phrase itself: the engine hands a word it does not know straight back, and that is
     * not a translation.
     */
    suspend fun said(text: String, from: String, to: String): String? {
        val asked = text.trim()
        if (asked.isEmpty() || from.isEmpty() || to.isEmpty() || from == to) return null
        known["$from>$to>$asked"]?.let { return it }
        val engine = engineFor(from, to) ?: return null
        return runCatching {
            engine.downloadModelIfNeeded().await()
            engine.translate(asked).await()
        }.onFailure {
            android.util.Log.w("Phonetix", "the machine could not answer $from>$to", it)
        }.getOrNull()
            ?.takeIf { !it.equals(asked, ignoreCase = true) }
            ?.also { known["$from>$to>$asked"] = it }
    }

    private fun engineFor(from: String, to: String): Translator? {
        val source = TranslateLanguage.fromLanguageTag(from) ?: return null
        val target = TranslateLanguage.fromLanguageTag(to) ?: return null
        return engines.getOrPut("$source>$target") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build(),
            )
        }
    }
}
