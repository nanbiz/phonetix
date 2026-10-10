//! A form said in English, the way the word on the page is used.
//!
//! The dictionary gives a verb's meaning in the infinitive - "anduvo" is listed under "andar",
//! "to walk, to go" - and a card that led with "to walk" over a word that means "he walked"
//! said the right word in the wrong form. Given the place a form fills in its table (see
//! [crate::paradigm]), this says the meaning in that place: "he walked", "they will walk",
//! "(if) he walked", "dogs".
//!
//! Regular forms are made by rule; the verbs and nouns no rule makes are read from the English
//! Wiktionary through data/english-verbs.tsv and data/english-nouns.tsv, which
//! tools/english_verbs.py writes with the same rule, leaving out every word the rule gets right.

use crate::paradigm::Place;

const VERBS: &str = include_str!("../../data/english-verbs.tsv");
const NOUNS: &str = include_str!("../../data/english-nouns.tsv");
const ADJECTIVES: &str = include_str!("../../data/english-adjectives.tsv");

const VOWELS: [char; 5] = ['a', 'e', 'i', 'o', 'u'];

/// One short syllable ending consonant-vowel-consonant doubles its last letter: "stop",
/// "stopped".
fn doubles(verb: &str) -> bool {
    let chars: Vec<char> = verb.chars().collect();
    let n = chars.len();
    (3..=4).contains(&n)
        && !VOWELS.contains(&chars[n - 1])
        && !matches!(chars[n - 1], 'w' | 'x' | 'y')
        && VOWELS.contains(&chars[n - 2])
        && !VOWELS.contains(&chars[n - 3])
}

fn third_by_rule(word: &str) -> String {
    if word.ends_with(['s', 'x', 'z']) || word.ends_with("ch") || word.ends_with("sh") {
        format!("{word}es")
    } else if word.ends_with('y')
        && word
            .chars()
            .rev()
            .nth(1)
            .is_some_and(|c| !VOWELS.contains(&c))
    {
        format!("{}ies", &word[..word.len() - 1])
    } else {
        format!("{word}s")
    }
}

fn ing_by_rule(verb: &str) -> String {
    if let Some(stem) = verb.strip_suffix("ie") {
        format!("{stem}ying")
    } else if verb.ends_with('e')
        && !verb.ends_with("ee")
        && !verb.ends_with("ye")
        && !verb.ends_with("oe")
    {
        format!("{}ing", &verb[..verb.len() - 1])
    } else if doubles(verb) {
        format!("{verb}{}ing", verb.chars().last().unwrap_or_default())
    } else {
        format!("{verb}ing")
    }
}

fn past_by_rule(verb: &str) -> String {
    if verb.ends_with('e') {
        format!("{verb}d")
    } else if verb.ends_with('y')
        && verb
            .chars()
            .rev()
            .nth(1)
            .is_some_and(|c| !VOWELS.contains(&c))
    {
        format!("{}ied", &verb[..verb.len() - 1])
    } else if doubles(verb) {
        format!("{verb}{}ed", verb.chars().last().unwrap_or_default())
    } else {
        format!("{verb}ed")
    }
}

/// A verb's four forms: third person, present participle, past, past participle.
fn verb_forms(verb: &str) -> [String; 4] {
    for line in VERBS.lines() {
        let mut parts = line.split('\t');
        if parts.next() == Some(verb) {
            let rest: Vec<&str> = parts.collect();
            if rest.len() == 4 {
                return [
                    rest[0].to_string(),
                    rest[1].to_string(),
                    rest[2].to_string(),
                    rest[3].to_string(),
                ];
            }
        }
    }
    [
        third_by_rule(verb),
        ing_by_rule(verb),
        past_by_rule(verb),
        past_by_rule(verb),
    ]
}

fn plural(noun: &str) -> String {
    NOUNS
        .lines()
        .find_map(|line| {
            let (one, many) = line.split_once('\t')?;
            (one == noun).then(|| many.to_string())
        })
        .unwrap_or_else(|| third_by_rule(noun))
}

/// How many syllables a word has, by its runs of vowels, a silent final e not counted. The
/// same count as tools/english_verbs.py, which leaves out of data/english-adjectives.tsv every
/// adjective this gets right.
fn syllables(word: &str) -> usize {
    let mut runs = 0;
    let mut before = false;
    for (at, c) in word.chars().enumerate() {
        let vowel = VOWELS.contains(&c) || (c == 'y' && at > 0);
        if vowel && !before {
            runs += 1;
        }
        before = vowel;
    }
    if word.ends_with('e')
        && !word.ends_with("le")
        && !word.ends_with("ee")
        && !word.ends_with("ye")
        && runs > 1
    {
        runs -= 1;
    }
    runs.max(1)
}

/// An adjective's comparative and superlative: as the English Wiktionary lists them where no
/// rule makes them ("better", "best"), and otherwise by the rule - one syllable, or two ending
/// in a y after a consonant, take -er and -est, and the rest "more" and "most".
fn degrees(adjective: &str) -> (String, String) {
    if let Some((more, most)) = ADJECTIVES.lines().find_map(|line| {
        let mut parts = line.split('\t');
        (parts.next()? == adjective)
            .then(|| Some((parts.next()?.to_string(), parts.next()?.to_string())))?
    }) {
        return (more, most);
    }
    let a = adjective;
    let chars: Vec<char> = a.chars().collect();
    let n = chars.len();
    if a.ends_with('y') && n > 1 && !VOWELS.contains(&chars[n - 2]) && syllables(a) <= 2 {
        let stem: String = chars[..n - 1].iter().collect();
        return (format!("{stem}ier"), format!("{stem}iest"));
    }
    if syllables(a) > 1 {
        return (format!("more {a}"), format!("most {a}"));
    }
    if a.ends_with('e') {
        return (format!("{a}r"), format!("{a}st"));
    }
    if doubles(a) {
        let last = chars[n - 1];
        return (format!("{a}{last}er"), format!("{a}{last}est"));
    }
    (format!("{a}er"), format!("{a}est"))
}

/// The value a place has in one category.
fn value<'a>(place: &'a Place, category: &str) -> Option<&'a str> {
    place
        .iter()
        .find(|(_, of)| *of == category)
        .map(|(tag, _)| tag.as_str())
}

/// The head of a meaning and what follows it: "to go out with" is "go" and " out with". Only
/// the first of several meanings, and nothing a parenthesis or a semicolon adds.
fn head(meaning: &str) -> Option<(String, String)> {
    // A sense written under a heading - "Used as a copula. to be" - means what follows it.
    let meaning = meaning
        .split(". ")
        .find(|part| part.trim_start().starts_with("to "))
        .unwrap_or(meaning);
    // A label in parentheses before the meaning - "(intransitive) to run" - is not the meaning.
    let mut meaning = meaning.trim();
    while let Some(after) = meaning
        .strip_prefix('(')
        .and_then(|rest| rest.split_once(')'))
    {
        meaning = after.1.trim_start();
    }
    // And a note in square brackets anywhere in it: "to run [auxiliary essere or avere]".
    let mut unbracketed = String::with_capacity(meaning.len());
    let mut depth = 0usize;
    for c in meaning.chars() {
        match c {
            '[' => depth += 1,
            ']' => depth = depth.saturating_sub(1),
            _ if depth == 0 => unbracketed.push(c),
            _ => {}
        }
    }
    let first = unbracketed.split([',', ';', '(']).next()?.trim();
    let first = first.strip_prefix("to ").unwrap_or(first).trim();
    let first = first.split_whitespace().collect::<Vec<_>>().join(" ");
    let first = first.as_str();
    if first.is_empty()
        || !first
            .chars()
            .all(|c| c.is_ascii_alphabetic() || c == ' ' || c == '-')
    {
        return None;
    }
    let (word, rest) = match first.split_once(' ') {
        Some((word, rest)) => (word, format!(" {rest}")),
        None => (first, String::new()),
    };
    Some((word.to_lowercase(), rest))
}

/// [meaning] said in the place [place] names, for a word of the kind [pos]: "to walk" in the
/// third person singular of the preterite indicative is "he walked". Nothing where the place
/// names nothing English marks, or the meaning is not a word English inflects.
pub fn english(meaning: &str, pos: &str, place: &Place) -> Option<String> {
    english_with(meaning, pos, place, true)
}

/// The same without who does it - "is", "walked", "will walk" - which is what takes the
/// word's place on a page: the page has its own subject.
pub fn english_bare(meaning: &str, pos: &str, place: &Place) -> Option<String> {
    english_with(meaning, pos, place, false)
}

fn english_with(meaning: &str, pos: &str, place: &Place, subject: bool) -> Option<String> {
    let (word, rest) = head(meaning)?;
    match pos {
        "verb" => verb(&word, &rest, place, subject),
        "noun" => match value(place, "number") {
            Some("plural") => Some(format!("{}{rest}", plural(&word))),
            Some("singular") => Some(format!("{word}{rest}")),
            _ => None,
        },
        // The degree an adjective is in; its case and number English does not show.
        "adj" => match value(place, "degree") {
            Some("comparative") => Some(format!("{}{rest}", degrees(&word).0)),
            Some("superlative") => Some(format!("{}{rest}", degrees(&word).1)),
            _ => None,
        },
        _ => None,
    }
}

/// The verbs English does not put in the progressive, so its continuous past is its simple one.
const STATIVE: [&str; 14] = [
    "be",
    "have",
    "know",
    "want",
    "like",
    "love",
    "need",
    "seem",
    "own",
    "believe",
    "mean",
    "understand",
    "belong",
    "exist",
];

fn verb(base: &str, rest: &str, place: &Place, subject: bool) -> Option<String> {
    let [third, ing, past, participle] = verb_forms(base);
    let person = value(place, "person");
    let number = value(place, "number").unwrap_or("singular");
    let who = match (person, number) {
        (Some("first-person"), "plural") => "we",
        (Some("first-person"), _) => "I",
        (Some("second-person"), "plural") => "you all",
        (Some("second-person"), _) => "you",
        (Some("third-person"), "plural") => "they",
        (Some("third-person"), _) => "he",
        _ => "",
    };
    let singular_third = who == "he";
    let be = base == "be";
    // "be" is the one verb with a form for each person.
    let present = if be {
        match who {
            "I" => "am".to_string(),
            "he" => "is".to_string(),
            _ => "are".to_string(),
        }
    } else if singular_third {
        third.clone()
    } else {
        base.to_string()
    };
    let simple_past = if be {
        if who == "I" || who == "he" {
            "was"
        } else {
            "were"
        }
        .to_string()
    } else {
        past.clone()
    };
    let was = if who == "I" || who == "he" {
        "was"
    } else {
        "were"
    };
    let has = if singular_third { "has" } else { "have" };
    let with = |who: &str, verb: String| -> String {
        if who.is_empty() || !subject {
            format!("{verb}{rest}")
        } else {
            format!("{who} {verb}{rest}")
        }
    };
    let said = match (
        value(place, "nonfinite"),
        value(place, "mood"),
        value(place, "tense"),
    ) {
        (Some("infinitive"), _, _) => format!("to {base}{rest}"),
        (Some("gerund"), _, _) => format!("{ing}{rest}"),
        (Some("participle"), _, Some("present")) => format!("{ing}{rest}"),
        (Some("participle"), _, _) => format!("{participle}{rest}"),
        (None, Some("imperative"), _) => format!("{base}{rest}!"),
        (None, Some("subjunctive"), Some("present")) => {
            let said = with(who, base.to_string());
            if subject {
                format!("(that) {said}")
            } else {
                said
            }
        }
        (None, Some("subjunctive"), Some("imperfect" | "past" | "preterite")) => {
            let were = if be { "were".to_string() } else { past.clone() };
            let said = with(who, were);
            if subject {
                format!("(if) {said}")
            } else {
                said
            }
        }
        (None, Some("subjunctive"), Some("future")) => {
            let said = with(who, present);
            if subject {
                format!("(if) {said}")
            } else {
                said
            }
        }
        (None, _, Some("present")) => with(who, present),
        (None, _, Some("preterite" | "past")) => with(who, simple_past),
        // What went on, for an action - "he was walking" - and what was so, for a verb English
        // does not put in the progressive: "era" is "he was", not "he was being".
        (None, _, Some("imperfect")) if STATIVE.contains(&base) => with(who, simple_past),
        (None, _, Some("imperfect")) => with(who, format!("{was} {ing}")),
        (None, _, Some("future")) => with(who, format!("will {base}")),
        (None, _, Some("conditional")) => with(who, format!("would {base}")),
        (None, _, Some("perfect")) => with(who, format!("{has} {participle}")),
        (None, _, Some("pluperfect")) => with(who, format!("had {participle}")),
        _ => return None,
    };
    Some(said)
}

/// [lemma] said in the place [place] names, in German, from the German dictionary's own table
/// of it: "gehen" in the third person singular of the preterite is "er ging". The future and
/// the conditional are said with "werden" and "würde", the way German says them, and a
/// subjunctive's past with "würde" too, which is how it is said in speech.
pub fn german<D: AsRef<[u8]>>(
    lemma: &str,
    pos: &str,
    place: &Place,
    pack: &lexpack::Pack<D>,
) -> Option<String> {
    german_with(lemma, pos, place, pack, true)
}

/// The same without who does it - "ging", "wird gehen" - for the word's place on a page.
pub fn german_bare<D: AsRef<[u8]>>(
    lemma: &str,
    pos: &str,
    place: &Place,
    pack: &lexpack::Pack<D>,
) -> Option<String> {
    german_with(lemma, pos, place, pack, false)
}

fn german_with<D: AsRef<[u8]>>(
    lemma: &str,
    pos: &str,
    place: &Place,
    pack: &lexpack::Pack<D>,
    subject: bool,
) -> Option<String> {
    let entry = pack
        .lookup(lemma)
        .into_iter()
        .find(|entry| entry.lemma == lemma && entry.pos == pos)?;
    match pos {
        "noun" => match value(place, "number") {
            Some("plural") => cell(&entry, &[("nominative", "case"), ("plural", "number")])
                .or_else(|| cell(&entry, &[("plural", "number")])),
            Some("singular") => Some(lemma.to_string()),
            _ => None,
        },
        "verb" => {
            let person = value(place, "person");
            let number = value(place, "number").unwrap_or("singular");
            let who = match (person, number) {
                (Some("first-person"), "plural") => "wir",
                (Some("first-person"), _) => "ich",
                (Some("second-person"), "plural") => "ihr",
                (Some("second-person"), _) => "du",
                (Some("third-person"), "plural") => "sie",
                (Some("third-person"), _) => "er",
                _ => return None,
            };
            let person = person?;
            let finite = |entry: &lexpack::Entry, tense: &str| -> Option<String> {
                cell(
                    entry,
                    &[(person, "person"), (number, "number"), (tense, "tense")],
                )
                // A table lists no form spelled like its lemma, and "wir gehen", "sie gehen"
                // are spelled so.
                .or_else(|| {
                    (tense == "present" && number == "plural" && person != "second-person")
                        .then(|| entry.lemma.clone())
                })
            };
            let helper = |subjunctive: bool| -> Option<String> {
                let werden = pack
                    .lookup("werden")
                    .into_iter()
                    .find(|entry| entry.lemma == "werden" && entry.pos == "verb")?;
                if subjunctive {
                    cell(
                        &werden,
                        &[
                            (person, "person"),
                            (number, "number"),
                            ("subjunctive", "mood"),
                        ],
                    )
                    .filter(|form| form.starts_with("wü"))
                    .or_else(|| {
                        Some(
                            match (person, number) {
                                ("second-person", "plural") => "würdet",
                                ("second-person", _) => "würdest",
                                (_, "plural") => "würden",
                                _ => "würde",
                            }
                            .to_string(),
                        )
                    })
                } else {
                    finite(&werden, "present")
                }
            };
            let said = match (value(place, "mood"), value(place, "tense")) {
                (Some("imperative"), _) => return None,
                (Some("subjunctive"), _) => format!("{} {lemma}", helper(true)?),
                (_, Some("present")) => finite(&entry, "present")?,
                (_, Some("preterite" | "past" | "imperfect")) => finite(&entry, "preterite")?,
                (_, Some("future")) => format!("{} {lemma}", helper(false)?),
                (_, Some("conditional")) => format!("{} {lemma}", helper(true)?),
                _ => return None,
            };
            Some(if subject {
                format!("{who} {said}")
            } else {
                said
            })
        }
        _ => None,
    }
}

/// The spelling in [entry]'s table of the place that has every one of [wanted], in the
/// indicative unless a mood is among them, the one naming fewest other values where several do.
fn cell(entry: &lexpack::Entry, wanted: &[(&str, &str)]) -> Option<String> {
    let mood_wanted = wanted.iter().any(|(_, category)| *category == "mood");
    entry
        .forms
        .iter()
        .flat_map(|row| {
            crate::paradigm::places(&row.label)
                .into_iter()
                .map(move |place| (row.spelling.clone(), place))
        })
        .filter(|(_, place)| {
            wanted
                .iter()
                .all(|(tag, _)| place.iter().any(|(had, _)| had == tag))
                && (mood_wanted || value(place, "mood").is_none_or(|mood| mood == "indicative"))
        })
        .min_by_key(|(_, place)| place.len())
        .map(|(spelling, _)| spelling)
}

#[cfg(test)]
mod tests {
    #[test]
    fn an_adjective_is_said_in_its_degree() {
        let place = |tag: &str| vec![(tag.to_string(), "degree")];
        assert_eq!(
            super::english("big, large", "adj", &place("superlative")).as_deref(),
            Some("biggest")
        );
        assert_eq!(
            super::english("good", "adj", &place("comparative")).as_deref(),
            Some("better")
        );
        assert_eq!(
            super::english("famous", "adj", &place("superlative")).as_deref(),
            Some("most famous")
        );
        assert_eq!(
            super::english("happy", "adj", &place("comparative")).as_deref(),
            Some("happier")
        );
        assert_eq!(
            super::english("large", "adj", &place("superlative")).as_deref(),
            Some("largest")
        );
    }

    use super::*;

    fn place(tags: &[&str]) -> Place {
        tags.iter()
            .filter_map(|tag| {
                crate::paradigm::category_of(tag).map(|category| (tag.to_string(), category))
            })
            .collect()
    }

    fn said(meaning: &str, pos: &str, tags: &[&str]) -> Option<String> {
        english(meaning, pos, &place(tags))
    }

    #[test]
    fn a_verb_is_said_in_its_person_tense_and_mood() {
        let with = |tense: &'static str| ["third-person", "singular", "indicative", tense];
        assert_eq!(
            said("to walk, to go", "verb", &with("preterite")).as_deref(),
            Some("he walked")
        );
        assert_eq!(
            said("to walk", "verb", &with("present")).as_deref(),
            Some("he walks")
        );
        assert_eq!(
            said("to walk", "verb", &with("imperfect")).as_deref(),
            Some("he was walking")
        );
        assert_eq!(
            said("to walk", "verb", &with("future")).as_deref(),
            Some("he will walk")
        );
        assert_eq!(
            said("to walk", "verb", &with("conditional")).as_deref(),
            Some("he would walk")
        );
        assert_eq!(
            said("to go", "verb", &["third-person", "plural", "preterite"]).as_deref(),
            Some("they went")
        );
        assert_eq!(
            said("to be", "verb", &["first-person", "singular", "present"]).as_deref(),
            Some("I am")
        );
        assert_eq!(
            said(
                "to walk",
                "verb",
                &["third-person", "singular", "imperfect", "subjunctive"]
            )
            .as_deref(),
            Some("(if) he walked")
        );
        assert_eq!(
            said("to stop", "verb", &["participle", "past"]).as_deref(),
            Some("stopped")
        );
        assert_eq!(
            said(
                "to go out with",
                "verb",
                &["first-person", "plural", "future"]
            )
            .as_deref(),
            Some("we will go out with")
        );
    }

    #[test]
    fn a_state_is_said_in_the_simple_past() {
        assert_eq!(
            said("to be", "verb", &["third-person", "singular", "imperfect"]).as_deref(),
            Some("he was")
        );
    }

    #[test]
    fn a_noun_is_said_in_its_number() {
        assert_eq!(
            said("house", "noun", &["singular"]).as_deref(),
            Some("house")
        );
        assert_eq!(
            said("dog, hound", "noun", &["plural"]).as_deref(),
            Some("dogs")
        );
        assert_eq!(
            said("child", "noun", &["nominative", "plural"]).as_deref(),
            Some("children")
        );
        assert_eq!(said("dog", "noun", &["genitive"]), None);
    }
}
