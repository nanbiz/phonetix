//! What a page or a screen gets drawn on it, decided in one pass.
//!
//! A host finds runs of text and hands them over; what comes back is one token per word, each
//! carrying everything needed to draw it and nothing needing a second question. The host never
//! decides which words are annotated, what they mean, or how they are said: those three would
//! otherwise be answered differently by a browser and by a phone reading the same sentence.
//!
//! Words the packs could not answer come back as misses, with what would answer them. The host
//! runs its engines over those and hands the results back through [`complete`], so an engine's
//! answer joins the same tokens rather than being drawn beside them.

use std::collections::HashMap;

use crate::answer::{
    AnnotateOptions, AnswerState, EngineResult, InlineMode, Lang, Miss, Need, Provenance, TextRun,
    Token,
};
use crate::resolve::Open;
use crate::segment::words;
use crate::sprinkle::picks;

/// How long an inline gloss may be before it stops being an annotation and becomes a sentence
/// over the reader's page.
const GLOSS_LIMIT: usize = 18;

/// Annotate a batch of runs.
///
/// The occurrence count that the sprinkle keys on runs across the whole batch rather than per
/// run, because a reader sees a page and not a text node: a word appearing once in each of
/// twenty paragraphs is a word appearing twenty times.
pub fn annotate<D: AsRef<[u8]>>(
    runs: &[TextRun],
    source: &Lang,
    target: &Lang,
    open: &Open<D>,
    options: &AnnotateOptions,
) -> (Vec<Token>, Vec<Miss>) {
    let mut tokens = Vec::new();
    let mut misses = Vec::new();
    let mut seen_before: HashMap<String, u32> = HashMap::new();

    for run in runs {
        let lang = run.lang_hint.clone().unwrap_or_else(|| source.clone());
        // The word before this one, within the run. A run is a line the page drew, so the
        // first word of one has no neighbour rather than borrowing the last word of another.
        let mut before: Option<String> = None;
        for word in words(&run.text) {
            let spelling = word.text;
            let key = spelling.to_lowercase();
            let occurrence = {
                let count = seen_before.entry(key.clone()).or_insert(0);
                let now = *count;
                *count += 1;
                now
            };

            // A word the reader has already opened a card for is always annotated: they asked
            // about it once, so it is theirs to keep seeing.
            let asked_about = options.seen.iter().any(|word| word.to_lowercase() == key);
            let inline = match options.mode {
                InlineMode::Off => false,
                _ => asked_about || picks(&key, occurrence, options.density),
            };

            // With the word before it, which is what decides a spelling that is several
            // words. The run is the sentence as the page has it, so the neighbour is the one
            // the reader is actually looking at.
            //
            // At the start of a sentence a capital says nothing about the word, and the word
            // in small letters is read instead wherever the dictionary has one: German "Er"
            // opening a line is "er", "he", and not the noun "Er" the capital would otherwise
            // reach. A capitalised word in the middle of a sentence keeps its capital, which in
            // German is what makes it a noun.
            let reading = match open.source {
                Some(pack) if starts_sentence(&run.text, word.start) => {
                    let small = spelling.to_lowercase();
                    if small != spelling && !pack.lookup(&small).is_empty() {
                        small
                    } else {
                        spelling.clone()
                    }
                }
                _ => spelling.clone(),
            };
            let answer =
                crate::resolve::read_in_context(&reading, before.as_deref(), &lang, target, open);
            before = Some(spelling.clone());
            // Only what is drawn is looked up further: a word nothing will draw costs the
            // reader nothing to leave unanswered, and a page is thousands of words.
            let gloss = drawn_as(&answer, &lang, target, open).map(|text| cut(&text, GLOSS_LIMIT));
            // Carrying as much of the detail as the reader asked for; the card always has the
            // full form. The accent is already in what the cascade answered, and applying it
            // again here would shift a word its accent's own pack had already spelled out.
            let ipa = answer
                .ipa
                .first()
                .map(|ipa| crate::symbols::display(ipa, options.narrow, options.hide_stress));
            // And how the translation is said, where that is what the reader asked for: the
            // word they are being handed is the one they will try to say. Out of the pack for
            // the language they read into, which is open because the join between the two is
            // what produced the gloss in the first place.
            let gloss_ipa = if options.mode == InlineMode::Both {
                gloss
                    .as_deref()
                    .and_then(|said| said_in(said, target, open))
                    .map(|ipa| crate::symbols::display(&ipa, options.narrow, options.hide_stress))
            } else {
                None
            };

            let index = tokens.len() as u32;
            if inline {
                if let Some(need) = missing(options.mode, gloss.is_some(), ipa.is_some()) {
                    misses.push(Miss {
                        token_index: index,
                        need,
                    });
                }
            }
            // A spelling that is several words and nothing decided which: the sentence it is
            // in, translated, would. Only where there is a translation to be had - a language
            // read into itself has no engine running and nothing to read - and only for a word
            // the reader can see, since the cost is one translation of the line it is on.
            if inline && answer.state == AnswerState::Homograph && target != &lang {
                misses.push(Miss {
                    token_index: index,
                    need: Need::Sentence,
                });
            } else if inline && target != &lang && gloss.is_some() && several_meanings(&answer) {
                misses.push(Miss {
                    token_index: index,
                    need: Need::Sense,
                });
            }
            tokens.push(Token {
                run_id: run.id,
                start: word.start,
                end: word.end,
                spelling,
                lang: lang.clone(),
                state: answer.state,
                gloss,
                ipa,
                gloss_ipa,
                inline,
                provenance: answer.provenance,
            });
        }
    }
    (tokens, misses)
}

/// How one word is said in the language being read into.
///
/// The first word of the gloss, because a gloss is a headword and sometimes a phrase around
/// it - "way, route" is answered by looking up "way" - and nothing at all where the reader
/// has no dictionary for that language.
fn said_in<D: AsRef<[u8]>>(gloss: &str, target: &Lang, open: &Open<D>) -> Option<String> {
    let head = gloss.split([',', ';', '(']).next()?.trim();
    if head.is_empty() {
        return None;
    }
    let pack = open.target?;
    if pack.lang() != target.0 {
        return None;
    }
    if let Some(said) = pack.lookup(head).first().and_then(|e| e.ipa.first()) {
        return Some(said.clone());
    }
    // A gloss carries the article a dictionary writes with it - "to bank", "a road", "der
    // Weg" - and the entry is under the word itself, which is the last word of the head in
    // every language a gloss is written in here.
    let word = head.split_whitespace().last()?;
    if word == head {
        return None;
    }
    pack.lookup(word)
        .first()
        .and_then(|e| e.ipa.first())
        .cloned()
}

/// What is still missing for what the reader asked to see, or nothing when the tokens have it.
fn missing(mode: InlineMode, has_gloss: bool, has_ipa: bool) -> Option<Need> {
    let wants_gloss = matches!(mode, InlineMode::Meaning | InlineMode::Both);
    let wants_ipa = matches!(mode, InlineMode::Sound);
    match (wants_gloss && !has_gloss, wants_ipa && !has_ipa) {
        (true, true) => Some(Need::Both),
        (true, false) => Some(Need::Gloss),
        (false, true) => Some(Need::Ipa),
        (false, false) => None,
    }
}

/// Fill in what the host's engines answered, shown the way everything else is.
///
/// An engine's answer is marked as one. A reader deciding whether to trust a word is owed the
/// difference between a dictionary and a machine, and the state and the provenance both carry
/// it so neither the inline layer nor the card can lose it.
pub fn complete<D: AsRef<[u8]>>(
    tokens: &mut [Token],
    results: &[EngineResult],
    options: &AnnotateOptions,
    target: &Lang,
    open: &Open<D>,
) {
    for result in results {
        let Some(token) = tokens.get_mut(result.token_index as usize) else {
            continue;
        };
        // The sentence, where the host had it translated for a spelling that is several
        // words. The word is read again with it in hand, because which word it is can change
        // what it means, how it is said and which entry the card is about - all of which the
        // cascade decides in one place rather than patching one of them here.
        if let Some(sentence) = &result.sentence {
            if token.state != AnswerState::Homograph {
                // Decided already, and asked about which of its senses the line means. Only the
                // drawn word changes: the reading, its state and where it came from stay, and a
                // line that says nothing about it leaves the first sense standing.
                let with_sentence = Open {
                    said: Some(sentence.as_str()),
                    ..*open
                };
                let spelling = token.spelling.clone();
                let lang = token.lang.clone();
                let answer =
                    crate::resolve::read_in_context(&spelling, None, &lang, target, &with_sentence);
                if answer.state == token.state {
                    if let Some(gloss) = drawn_as(&answer, &lang, target, &with_sentence)
                        .map(|text| cut(&text, GLOSS_LIMIT))
                    {
                        if token.gloss_ipa.is_some() && token.gloss.as_deref() != Some(&gloss) {
                            token.gloss_ipa = said_in(&gloss, target, &with_sentence).map(|ipa| {
                                crate::symbols::display(&ipa, options.narrow, options.hide_stress)
                            });
                        }
                        token.gloss = Some(gloss);
                    }
                }
            } else {
                let with_sentence = Open {
                    said: Some(sentence.as_str()),
                    ..*open
                };
                let spelling = token.spelling.clone();
                let lang = token.lang.clone();
                let answer =
                    crate::resolve::read_in_context(&spelling, None, &lang, target, &with_sentence);
                if answer.state != AnswerState::Homograph {
                    token.state = answer.state;
                    token.provenance = answer.provenance.clone();
                    // Drawn the way the first pass drew it: a dictionary's sense over the page
                    // is "the (definite article)" or "third-person singular ... of courir",
                    // and what the reader is owed is the word it means.
                    if let Some(gloss) = drawn_as(&answer, &lang, target, &with_sentence)
                        .map(|text| cut(&text, GLOSS_LIMIT))
                    {
                        if token.gloss_ipa.is_some() {
                            token.gloss_ipa = said_in(&gloss, target, &with_sentence).map(|ipa| {
                                crate::symbols::display(&ipa, options.narrow, options.hide_stress)
                            });
                        }
                        token.gloss = Some(gloss);
                    }
                    if let Some(ipa) = answer.ipa.first() {
                        token.ipa = Some(crate::symbols::display(
                            ipa,
                            options.narrow,
                            options.hide_stress,
                        ));
                    }
                }
            }
        }
        if let Some(gloss) = &result.gloss {
            token.gloss = Some(cut(gloss, GLOSS_LIMIT));
            token.state = AnswerState::Guess;
            token.provenance = Some(Provenance::Guess {
                engine: result.engine.clone(),
            });
        }
        if let Some(ipa) = &result.ipa {
            // Shown the way a pack's own transcription would be: in the reader's accent, at
            // the detail they asked for. An engine's answer that skipped this came out in a
            // different notation from the word beside it.
            let accent = options.accent.as_deref().unwrap_or("");
            let said = crate::accent::apply(ipa, accent, &token.spelling);
            token.ipa = Some(crate::symbols::display(
                &said,
                options.narrow,
                options.hide_stress,
            ));
            // A transcription a machine spoke is still a machine's, but it says nothing about
            // what the word means, so it does not turn a dictionary answer into a guess.
            if token.gloss.is_none() {
                token.provenance = Some(Provenance::Synthesised);
            }
        }
    }
}

/// A gloss cut to what an inline annotation can carry, on a word boundary where there is one.
///
/// Cut mid-word, an annotation reads as a different word; cut at a space, it reads as the
/// beginning of the right one. The ellipsis says that there is more, which the card has.
/// Whether the word at this offset starts a sentence: nothing but space before it in the run,
/// or the end of one - a full stop, a question or exclamation mark, a colon - with an opening
/// quotation mark or bracket allowed between.
fn starts_sentence(text: &str, start_utf16: u32) -> bool {
    let mut offset = 0u32;
    let mut before: Vec<char> = Vec::new();
    for c in text.chars() {
        if offset >= start_utf16 {
            break;
        }
        before.push(c);
        offset += c.len_utf16() as u32;
    }
    let previous = before.iter().rev().find(|c| {
        !c.is_whitespace() && !matches!(c, '"' | '“' | '„' | '«' | '»' | '(' | '[' | '¿' | '¡')
    });
    match previous {
        None => true,
        Some(c) => matches!(c, '.' | '!' | '?' | ':' | '…'),
    }
}

/// Whether the senses a word is drawn from would draw different words.
///
/// "banco" is "bank" and "bench", "parque" is "park" and "parking lot": drawn from the first
/// sense alone, a line about a bench says "bank". Only the first few senses count, since they
/// are the ones a dictionary puts the common meanings in, and senses that are notes about
/// grammar are not meanings to choose between.
fn several_meanings(answer: &crate::resolve::Answer) -> bool {
    let mut first: Option<String> = None;
    for sense in answer.glosses.iter().take(SENSES_CONSIDERED) {
        let Some(drawn) = plain(sense) else { continue };
        let Some(term) = crate::gloss::terms(&drawn).into_iter().next() else {
            continue;
        };
        match &first {
            None => first = Some(term),
            Some(had) if *had != term => return true,
            Some(_) => {}
        }
    }
    false
}

/// How many of a word's senses are weighed against its translated line.
pub(crate) const SENSES_CONSIDERED: usize = 6;

/// What a word is drawn as over the page, out of what a dictionary says it means.
///
/// A dictionary writes for a card: "dog (the species Canis familiaris, ...)", "masculine
/// singular definite article; the", "first-person singular present indicative of caminar".
/// Over the page there is room for the word and nothing else, so what is drawn is the word:
/// the first sense that is a meaning rather than a note about grammar, without what its
/// parentheses explain, and of its alternatives the first one that is not itself such a note.
/// The card still shows every sense as the dictionary wrote it.
fn inline_of(senses: &[String]) -> Option<String> {
    senses.iter().find_map(|sense| plain(sense))
}

/// What a word is drawn as, looking as far as it takes to find a meaning.
///
/// Its own senses first; then the other words the same spelling is, which the card offers
/// anyway; then, where all it says is that it is a form of another word - "third-person
/// singular present indicative of correre" - that word's meaning. Only when none of that
/// finds a meaning is the first sense drawn as the dictionary wrote it.
fn drawn_as<D: AsRef<[u8]>>(
    answer: &crate::resolve::Answer,
    lang: &Lang,
    target: &Lang,
    open: &Open<D>,
) -> Option<String> {
    if let Some(found) = inline_of(&answer.says).or_else(|| inline_of(&answer.glosses)) {
        return Some(found);
    }
    // The word this one is a form of, before any other reading: that is still this reading,
    // said through the entry it points at - Portuguese "as" is the plural of "o", "the", and
    // the pronoun "as" beside it is a different word.
    let first = answer.glosses.first().or_else(|| answer.says.first());
    if let Some(pointed) = first.and_then(|gloss| points_at(gloss.as_str())) {
        let there = crate::resolve::read_in_context(&pointed, None, lang, target, open);
        if let Some(found) = inline_of(&there.says).or_else(|| inline_of(&there.glosses)) {
            return Some(found);
        }
    }
    for reading in answer.readings.iter().skip(1) {
        if let Some(found) = inline_of(&reading.says).or_else(|| inline_of(&reading.glosses)) {
            return Some(found);
        }
    }
    first
        .map(|sense| sense.trim().to_string())
        .filter(|text| !text.is_empty())
}

/// The word a note points at: what follows its last "of".
pub(crate) fn points_at(gloss: &str) -> Option<String> {
    let lowered = gloss.to_lowercase();
    let at = lowered.rfind(" of ")? + " of ".len();
    let word = gloss[at..]
        .split(|c: char| c.is_whitespace() || matches!(c, ',' | ';' | ':' | '(' | '.'))
        .next()?
        .trim();
    (!word.is_empty()).then(|| word.to_string())
}

/// A sense as a word, or nothing when all it says is grammar.
fn plain(sense: &str) -> Option<String> {
    // A note pointing at another word often carries that word's meaning in quotation marks -
    // "masculine plural of el (“the”)" - and that is the meaning.
    if about_grammar(sense) {
        if let Some(quoted) = quoted(sense) {
            return Some(quoted);
        }
    }
    let without = drop_parentheses(sense);
    let parts: Vec<&str> = without.split(';').map(str::trim).collect();
    if let Some(part) = parts
        .iter()
        .find(|part| !part.is_empty() && !about_grammar(part))
    {
        return Some(tidy(part));
    }
    // Every part a note about grammar. Such a note usually ends with the meaning, after a
    // colon - "dative singular of der: the" - or a comma - "definite article, the".
    let note = parts.first()?;
    if let Some((_, after)) = note.split_once(':') {
        let after = after.trim();
        if !after.is_empty() && !about_grammar(after) {
            return Some(tidy(after));
        }
    }
    note.split(',')
        .map(str::trim)
        .skip(1)
        .find(|piece| !piece.is_empty() && !about_grammar(piece))
        .map(tidy)
}

/// What a sense quotes as the meaning, between curly quotation marks.
pub(crate) fn quoted(sense: &str) -> Option<String> {
    let start = sense.find('“')? + '“'.len_utf8();
    let end = start + sense[start..].find('”')?;
    let inside = drop_parentheses(sense[start..end].trim());
    (!inside.is_empty()).then(|| tidy(&inside))
}

/// One space between words, and no space or stray punctuation left where a parenthesis was.
fn tidy(text: &str) -> String {
    let joined = text.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut out = joined
        .replace(" ,", ",")
        .replace(" .", ".")
        .replace(" ;", ";")
        .replace(" :", ":");
    while out.ends_with(['.', ',', ';', ':']) {
        out.pop();
    }
    out.trim().to_string()
}

/// Whether this part of a sense describes the word's grammar rather than what it means.
///
/// Two shapes: a note pointing at another word - "plural of perro", "inflection of correr" -
/// and a description of what kind of word it is - "masculine singular definite article". A
/// short meaning that merely contains one of these words, "person", is neither.
pub(crate) fn about_grammar(part: &str) -> bool {
    const GRAMMAR: &[&str] = &[
        "article",
        "inflection",
        "nominative",
        "accusative",
        "dative",
        "genitive",
        "ablative",
        "vocative",
        "locative",
        "instrumental",
        "contraction",
        "abbreviation",
        "alternative form",
        "spelling",
        "singular",
        "plural",
        "first-person",
        "second-person",
        "third-person",
        "participle",
        "indicative",
        "subjunctive",
        "imperative",
        "infinitive",
        "gerund",
        "used before",
        "used after",
        "tense",
        "forms the",
        "masculine",
        "feminine",
        "neuter",
        // A note that only says which word this is a shape of: "apocopic form of mío, my",
        // "clipping of bicicleta", "short for Señor".
        "form of",
        "clipping of",
        "short for",
        "apocop",
    ];
    // What a parenthesis says qualifies the meaning and is not a note about grammar: "kid;
    // child (young person)" is a meaning.
    let lowered = drop_parentheses(part).to_lowercase();
    if lowered.contains("letter") && lowered.contains("name of the") {
        return true;
    }
    let grammatical = GRAMMAR.iter().any(|word| lowered.contains(word));
    let points_elsewhere = lowered.contains(" of ");
    let words = lowered.split_whitespace().count();
    grammatical && (points_elsewhere || words > 2)
}

/// The sense without what its parentheses and quotation marks add.
fn drop_parentheses(sense: &str) -> String {
    let mut out = String::with_capacity(sense.len());
    let mut depth = 0usize;
    for c in sense.chars() {
        match c {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = depth.saturating_sub(1),
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    out.trim().trim_end_matches([',', ':']).trim().to_string()
}

fn cut(text: &str, limit: usize) -> String {
    let units: Vec<char> = text.chars().collect();
    if units.len() <= limit {
        return text.to_string();
    }
    let head: String = units[..limit].iter().collect();
    match head.rfind(' ') {
        Some(space) if space >= limit / 2 => format!("{}…", &head[..space]),
        _ => format!("{head}…"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::answer::InlineMode;

    fn options(mode: InlineMode, density: u32) -> AnnotateOptions {
        AnnotateOptions {
            mode,
            density,
            narrow: false,
            hide_stress: false,
            accent: None,
            seen: Vec::new(),
        }
    }

    fn runs(text: &str) -> Vec<TextRun> {
        vec![TextRun {
            id: 7,
            text: text.to_string(),
            lang_hint: None,
        }]
    }

    fn nothing_open<'a>() -> Open<'a, Vec<u8>> {
        Open::default()
    }

    #[test]
    fn every_word_becomes_a_token_where_the_host_will_find_it() {
        let (tokens, _) = annotate(
            &runs("El perro corre"),
            &Lang("es".into()),
            &Lang("de".into()),
            &nothing_open(),
            &options(InlineMode::Meaning, 1),
        );
        assert_eq!(tokens.len(), 3);
        assert_eq!(tokens[1].spelling, "perro");
        assert_eq!((tokens[1].start, tokens[1].end), (3, 8));
        assert_eq!(tokens[1].run_id, 7);
    }

    #[test]
    fn with_no_pack_every_drawn_word_is_a_miss() {
        let (tokens, misses) = annotate(
            &runs("El perro corre"),
            &Lang("es".into()),
            &Lang("de".into()),
            &nothing_open(),
            &options(InlineMode::Meaning, 1),
        );
        assert_eq!(misses.len(), tokens.len());
        assert!(misses.iter().all(|m| m.need == Need::Gloss));
        assert!(tokens.iter().all(|t| t.state == AnswerState::NoPack));
    }

    #[test]
    fn nothing_is_drawn_and_nothing_is_asked_when_the_layer_is_off() {
        let (tokens, misses) = annotate(
            &runs("El perro corre"),
            &Lang("es".into()),
            &Lang("de".into()),
            &nothing_open(),
            &options(InlineMode::Off, 1),
        );
        assert!(tokens.iter().all(|t| !t.inline));
        assert!(misses.is_empty());
    }

    #[test]
    fn a_word_the_reader_asked_about_is_always_drawn() {
        let mut chosen = options(InlineMode::Meaning, 50);
        chosen.seen = vec!["Perro".into()];
        let (tokens, _) = annotate(
            &runs("El perro corre por el camino"),
            &Lang("es".into()),
            &Lang("de".into()),
            &nothing_open(),
            &chosen,
        );
        let perro = tokens.iter().find(|t| t.spelling == "perro").unwrap();
        assert!(perro.inline);
    }

    #[test]
    fn a_sparse_page_draws_less_than_a_dense_one() {
        let text = "El perro corre por el camino y descansa en el banco del parque";
        let dense = annotate(
            &runs(text),
            &Lang("es".into()),
            &Lang("de".into()),
            &nothing_open(),
            &options(InlineMode::Meaning, 1),
        )
        .0;
        let sparse = annotate(
            &runs(text),
            &Lang("es".into()),
            &Lang("de".into()),
            &nothing_open(),
            &options(InlineMode::Meaning, 50),
        )
        .0;
        let drawn = |tokens: &[Token]| tokens.iter().filter(|t| t.inline).count();
        assert!(
            drawn(&sparse) < drawn(&dense),
            "sparse drew as much as dense"
        );
    }

    #[test]
    fn an_engines_answer_is_marked_as_one() {
        let (mut tokens, _) = annotate(
            &runs("perro"),
            &Lang("es".into()),
            &Lang("de".into()),
            &nothing_open(),
            &options(InlineMode::Meaning, 1),
        );
        complete(
            &mut tokens,
            &[EngineResult {
                token_index: 0,
                gloss: Some("Hund".into()),
                ipa: None,
                sentence: None,
                engine: "bergamot".into(),
            }],
            &options(InlineMode::Meaning, 1),
            &Lang("de".into()),
            &nothing_open(),
        );
        assert_eq!(tokens[0].gloss.as_deref(), Some("Hund"));
        assert_eq!(tokens[0].state, AnswerState::Guess);
        assert!(matches!(
            tokens[0].provenance,
            Some(Provenance::Guess { .. })
        ));
    }

    #[test]
    fn a_long_gloss_is_cut_where_a_word_ends() {
        assert_eq!(cut("reading room in a library", 18), "reading room in a…");
        assert_eq!(cut("Hund", 18), "Hund");
    }

    #[test]
    fn a_run_that_needs_a_lexicon_is_left_whole() {
        let (tokens, misses) = annotate(
            &runs("日本語のテキスト"),
            &Lang("ja".into()),
            &Lang("de".into()),
            &nothing_open(),
            &options(InlineMode::Meaning, 1),
        );
        assert!(tokens.is_empty());
        assert!(misses.is_empty());
    }
}
