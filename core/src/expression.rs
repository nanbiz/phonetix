//! Expressions a reader has to read as one that the dictionary does not hold as one.
//!
//! "Me gusta" is "I like", literally "it pleases me": the English subject is the Spanish
//! object. The dictionary holds "gusta" as a form of "gustar", "to like", and read word by word
//! the card said "he likes" over "Me gusta", the wrong person, from the wrong word. The verbs
//! that work this way, and the pronouns that go before them, are listed in
//! data/expressions.tsv; a pronoun from that list before a form of a verb from it is read as
//! one expression, said in the pronoun's person, with each word's own meaning under it.

use crate::answer::Lang;
use crate::resolve::{Answer, Open};
use lexpack::Pack;

const TABLE: &str = include_str!("../../data/expressions.tsv");

/// One row of the table: a language, a kind, a word, and the two English columns.
fn rows() -> impl Iterator<Item = [&'static str; 5]> {
    TABLE
        .lines()
        .filter(|line| !line.starts_with('#') && !line.trim().is_empty())
        .filter_map(|line| {
            let cells: Vec<&str> = line.split('\t').collect();
            (cells.len() == 5).then(|| [cells[0], cells[1], cells[2], cells[3], cells[4]])
        })
}

/// The English a verb of this kind is said with, and what it literally does.
fn verb(lang: &str, lemma: &str) -> Option<(&'static str, &'static str)> {
    rows()
        .find(|row| row[0] == lang && row[1] == "verb" && row[2] == lemma)
        .map(|row| (row[3], row[4]))
}

/// A pronoun that goes before such a verb: its person and number as the dictionary tags them,
/// and what it literally is.
fn clitic(lang: &str, word: &str) -> Option<(&'static str, &'static str, &'static str)> {
    let word = word.to_lowercase();
    let row = rows().find(|row| row[0] == lang && row[1] == "clitic" && row[2] == word)?;
    let person = match &row[3][..1] {
        "1" => "first-person",
        "2" => "second-person",
        _ => "third-person",
    };
    let number = if row[3].ends_with("pl") {
        "plural"
    } else {
        "singular"
    };
    Some((person, number, row[4]))
}

/// The lemma a spelling is a form of, where the dictionary files it as one: "gusta" is
/// "third-person singular present indicative of gustar".
fn lemma_of<D: AsRef<[u8]>>(pack: &Pack<D>, spelling: &str) -> Option<String> {
    pack.lookup(&spelling.to_lowercase())
        .into_iter()
        .find_map(|entry| {
            entry
                .senses
                .iter()
                .find_map(|sense| crate::resolve::form_of_word(&sense.gloss))
        })
}

/// Where an expression of this kind is around the word at [at], as the start and end
/// (exclusive) of it in [words].
pub fn around<D: AsRef<[u8]>>(pack: &Pack<D>, words: &[&str], at: usize) -> Option<(usize, usize)> {
    let lang = pack.lang();
    for start in [at.checked_sub(1), Some(at)].into_iter().flatten() {
        let (Some(first), Some(second)) = (words.get(start), words.get(start + 1)) else {
            continue;
        };
        if clitic(lang, first).is_some()
            && lemma_of(pack, second).is_some_and(|lemma| verb(lang, &lemma).is_some())
        {
            return Some((start, start + 2));
        }
    }
    None
}

/// An expression of this kind read as one, where [spelling] is one and the card is read into
/// English, which is the language its meaning is said in: "me gusta" is "I like", in the
/// pronoun's person and the verb's tense, how it is said is the two words', and each word's own
/// meaning goes with it.
pub fn read<D: AsRef<[u8]>>(
    spelling: &str,
    source: &Lang,
    target: &Lang,
    open: &Open<D>,
) -> Option<Answer> {
    if target.0 != "en" {
        return None;
    }
    let pack = open.source?;
    let mut words = spelling.split_whitespace();
    let (first, second) = (words.next()?, words.next()?);
    if words.next().is_some() {
        return None;
    }
    let (person, number, to_whom) = clitic(&source.0, first)?;
    let lemma = lemma_of(pack, second)?;
    let (english, literal) = verb(&source.0, &lemma)?;
    // The verb on its own: asked with the pronoun before it, it would be this expression again.
    let verb_answer = crate::resolve::read_in_context(second, None, source, target, open);
    let form = verb_answer.paradigm.clone()?;
    // Who does it is the pronoun; when, and how, is the verb's.
    let mut place: crate::paradigm::Place = form
        .place
        .iter()
        .filter(|(_, category)| *category != "person" && *category != "number")
        .cloned()
        .collect();
    place.push((person.to_string(), "person"));
    place.push((number.to_string(), "number"));
    let said = crate::inflect::english(english, "verb", &place)?;
    let bare = crate::inflect::english_bare(english, "verb", &form.place);
    let pleases = crate::inflect::english_bare(literal, "verb", &form.place)
        .unwrap_or_else(|| literal.trim_start_matches("to ").to_string());
    let clitic_ipa = pack
        .lookup(&first.to_lowercase())
        .into_iter()
        .find_map(|entry| entry.ipa.first().cloned())
        .unwrap_or_else(|| first.to_lowercase());
    let mut answer = verb_answer;
    if let Some(verb_ipa) = answer.ipa.first().cloned() {
        let whole = format!("{clitic_ipa} {verb_ipa}");
        answer.symbols = crate::symbols::explain(&whole);
        answer.ipa = vec![whole];
    }
    answer.narrow = None;
    answer.spelling = spelling.to_string();
    answer.pos = Some("expression".to_string());
    answer.says = vec![said.clone()];
    answer.readings = Vec::new();
    answer.parts = vec![
        (first.to_string(), to_whom.to_string()),
        (second.to_string(), format!("{pleases} ({lemma})")),
    ];
    let mut form = form;
    form.said = Some(said);
    form.bare = bare;
    // The other tenses and moods said the same way, in the pronoun's person; the verb's own
    // persons go, since "gusto" is not "I like" but "I please".
    let with_clitic = |place: &crate::paradigm::Place| {
        let mut out: crate::paradigm::Place = place
            .iter()
            .filter(|(_, category)| *category != "person" && *category != "number")
            .cloned()
            .collect();
        out.push((person.to_string(), "person"));
        out.push((number.to_string(), "number"));
        out
    };
    form.along.retain(|along| along.category != "person");
    for along in &mut form.along {
        for other in &mut along.forms {
            other.said = crate::inflect::english(english, "verb", &with_clitic(&other.place));
        }
    }
    answer.paradigm = Some(form);
    Some(answer)
}
