//! Reading one language out of the dump.
//!
//! The dump is one JSON object per line, one line per (word, part of speech), as wiktextract
//! writes it. What a pack needs from each is small: the word, what part of speech it is, how
//! it is said, what its senses mean in English, and the spellings that resolve to it. The rest
//! is left where it is.
//!
//! This is the half that can be tested without a gigabyte on disk. The binary streams a file
//! through it and writes the pack.

use lexpack::{Entry, Sense};
use serde_json::Value;

/// One line of the dump, as far as a pack cares.
pub struct Read {
    pub entry: Entry,
    /// The spellings that should reach this entry besides its own: inflections, and the
    /// alternative forms the dump lists.
    pub forms: Vec<String>,
}

/// What was skipped and why, so a build can say what it did not take rather than only what it
/// did.
#[derive(Default, Debug, PartialEq, Eq)]
pub struct Skipped {
    /// Lines that are not JSON at all.
    pub unreadable: usize,
    /// Entries with no word, or no part of speech.
    pub nameless: usize,
    /// Entries with nothing a reader could be shown: no gloss and no pronunciation.
    pub empty: usize,
    /// Entries of a language this pack is not for.
    pub other_language: usize,
}

/// Read one line of the dump.
///
/// Returns nothing when the line holds no entry this pack should carry, and says why through
/// [Skipped] so a build can report it.
pub fn read_line(line: &str, lang: &str, skipped: &mut Skipped) -> Option<Read> {
    read_line_filed_as(line, &[lang], skipped)
}

/// Read one line of the dump for a language the dump files under other codes.
///
/// Wiktionary keeps some languages under one heading that a reader knows as several:
/// Croatian, Serbian and Bosnian are all "Serbo-Croatian" there, and Norwegian is written as
/// Bokmål and Nynorsk. A pack for the language the product names is built from the entries
/// filed under any of these codes.
pub fn read_line_filed_as(line: &str, codes: &[&str], skipped: &mut Skipped) -> Option<Read> {
    let line = line.trim();
    if line.is_empty() {
        return None;
    }
    let Ok(value): Result<Value, _> = serde_json::from_str(line) else {
        skipped.unreadable += 1;
        return None;
    };
    // The dump's own language tag, which an extract is not guaranteed to be filtered by.
    if let Some(code) = value.get("lang_code").and_then(|v| v.as_str()) {
        if !codes.iter().any(|wanted| code.eq_ignore_ascii_case(wanted)) {
            skipped.other_language += 1;
            return None;
        }
    }
    let word = value
        .get("word")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim();
    let pos = value
        .get("pos")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim();
    if word.is_empty() || pos.is_empty() {
        skipped.nameless += 1;
        return None;
    }

    let ipa = pronunciations(&value);
    let senses = senses(&value);
    if senses.is_empty() && ipa.is_empty() {
        skipped.empty += 1;
        return None;
    }

    Some(Read {
        entry: Entry {
            lemma: word.to_string(),
            pos: pos.to_string(),
            tags: strings(value.get("tags")),
            ipa,
            senses,
            forms: forms(&value, word),
        },
        forms: Vec::new(),
    })
}

/// How the word is said, as the dump records it.
///
/// Only the transcriptions, and only in the notation this reads. A sound entry can be a
/// recording, a rhyme or a respelling instead, and each of those is somebody else's field.
/// Whether an entry does nothing but say which other word it is a form of, with no sound of
/// its own: "dogs, plural of dog".
///
/// Such an entry is the dump's way of filing an inflection under its own heading. The lemma
/// lists the same spelling among its forms, and the pack reaches the lemma from it through
/// that, with the form's name. Kept, it is a second answer to the same question - and in the
/// published English dictionary a third of all entries. One with a pronunciation of its own is
/// kept: "est" is said [ɛ], and "être", which it is a form of, is not.
pub fn is_bare_form(entry: &Entry) -> bool {
    !entry.senses.is_empty()
        && entry.ipa.is_empty()
        && entry
            .senses
            .iter()
            .all(|sense| sense.marks.iter().any(|mark| mark == "form-of"))
}

fn pronunciations(value: &Value) -> Vec<String> {
    let mut out = Vec::new();
    for sound in value
        .get("sounds")
        .and_then(|v| v.as_array())
        .into_iter()
        .flatten()
    {
        if let Some(ipa) = sound.get("ipa").and_then(|v| v.as_str()) {
            let ipa = ipa
                .trim()
                .trim_matches(|c| c == '/' || c == '[' || c == ']')
                .trim();
            if !ipa.is_empty() && !out.iter().any(|had: &String| had == ipa) {
                out.push(ipa.to_string());
            }
        }
    }
    out
}

/// How many of an entry's senses keep their example: as many as a translated line picks a
/// sense out of, since any of those can end up leading the card.
const EXAMPLES_KEPT: usize = 6;

/// What the word means, in English, which is what joins two packs into a pair.
fn senses(value: &Value) -> Vec<Sense> {
    let mut out: Vec<Sense> = Vec::new();
    for sense in value
        .get("senses")
        .and_then(|v| v.as_array())
        .into_iter()
        .flatten()
    {
        // A sense with no gloss is a cross reference or a form-of stub: it carries no meaning
        // of its own and nothing can be joined to it.
        // The last of them. A sense nested under another is written from the general to the
        // particular - ["As a copulative verb:", "to be"] - and what it means is the last
        // one; the first is the heading it sits under. Taking the first left German "sein"
        // without "to be" anywhere in it.
        let glosses = strings(sense.get("glosses"));
        let Some(gloss) = glosses.iter().rev().find(|g| !g.trim().is_empty()) else {
            continue;
        };
        if gloss.trim().is_empty() {
            continue;
        }
        out.push(Sense {
            gloss: gloss.trim().to_string(),
            marks: strings(sense.get("tags")),
            // Only for the first senses, which are the ones a card can lead with: the first,
            // or one a translated line picked out of the first few. The rest were a third of the
            // English dictionary and never shown.
            example: (out.len() < EXAMPLES_KEPT)
                .then(|| sense.get("examples"))
                .flatten()
                .and_then(|v| v.as_array())
                .and_then(|list| list.first())
                .and_then(|first| first.get("text"))
                .and_then(|v| v.as_str())
                .map(|text| text.trim().to_string())
                .filter(|text| !text.is_empty()),
        });
    }
    out
}

/// The other spellings that should reach this entry.
///
/// A form the dump gives without a spelling of its own, or one that merely repeats the lemma,
/// reaches it already. Duplicates are dropped here rather than at the pack, which refuses them
/// as an error.
fn forms(value: &Value, word: &str) -> Vec<lexpack::Form> {
    let mut out: Vec<lexpack::Form> = Vec::new();
    for form in value
        .get("forms")
        .and_then(|v| v.as_array())
        .into_iter()
        .flatten()
    {
        let Some(spelling) = form.get("form").and_then(|v| v.as_str()) else {
            continue;
        };
        let spelling = spelling.trim();
        if spelling.is_empty() || spelling == word || spelling == "-" {
            continue;
        }
        let tags = strings(form.get("tags"));
        // A table header the extractor kept as a form, which is a label rather than a word.
        if tags
            .iter()
            .any(|t| t == "table-tags" || t == "inflection-template")
        {
            continue;
        }
        // The headword as the dictionary prints it is kept - "amō" for "amo" - unless what the
        // extractor kept is a fragment of it: a German name is printed with its article, "der
        // Kosovo", and the row that reached the dump was "der" alone. Filed as a form of the
        // name, every "der" on a page led to Kosovo and every "die" to the CIA.
        if tags.iter().any(|t| t == "canonical") && !starts_alike(spelling, word) {
            continue;
        }
        if !out.iter().any(|had| had.spelling == spelling) {
            // What the dump calls it, as a reader would say it: "plural", "past participle".
            // Several tags joined, since a form is regularly more than one thing at once, and
            // nothing where the dump gave none.
            let label = strings(form.get("tags"))
                .into_iter()
                .filter(|tag| tag != "canonical" && tag != "inflection-template")
                .collect::<Vec<_>>()
                .join(" ");
            out.push(lexpack::Form {
                spelling: spelling.to_string(),
                label,
            });
        }
    }
    commonest(out)
}

/// How many spellings one entry may bring with it.
///
/// A Finnish noun is listed with over two hundred: every case, in both numbers, with every
/// possessive ending. Kept whole that is twenty-seven million spellings and a dictionary of
/// close to three hundred megabytes, which nobody downloads onto a phone to read a page. The
/// forms a reader actually meets are a few dozen of them.
const MAX_FORMS: usize = 48;

/// The forms most worth keeping, when an entry has more than [MAX_FORMS].
///
/// Fewer tags first, because a form that is one thing - "plural", "genitive" - is met far more
/// often than one that is five at once. A possessive ending, and the cases a modern text
/// hardly uses, go before anything else; within a rank the dump's own order stands, since it
/// lists the paradigm the way a grammar does.
fn commonest(mut forms: Vec<lexpack::Form>) -> Vec<lexpack::Form> {
    if forms.len() <= MAX_FORMS {
        return forms;
    }
    const RARE: [&str; 6] = [
        "possessive",
        "singular-possessive",
        "plural-possessive",
        "abessive",
        "comitative",
        "instructive",
    ];
    let rank = |form: &lexpack::Form| {
        let tags: Vec<&str> = form.label.split(' ').filter(|t| !t.is_empty()).collect();
        let rare = tags.iter().filter(|t| RARE.contains(t)).count();
        rare * 10 + tags.len()
    };
    // Stable, so the dump's order decides between forms of the same rank.
    forms.sort_by_key(rank);
    forms.truncate(MAX_FORMS);
    forms
}

/// Whether two spellings begin with the same letter, whatever the case.
fn starts_alike(one: &str, other: &str) -> bool {
    let first = |text: &str| {
        text.chars()
            .next()
            .map(|c| c.to_lowercase().collect::<String>())
    };
    first(one) == first(other)
}

fn strings(value: Option<&Value>) -> Vec<String> {
    value
        .and_then(|v| v.as_array())
        .map(|list| {
            list.iter()
                .filter_map(|v| v.as_str())
                .map(|s| s.trim().to_string())
                .collect()
        })
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    const PERRO: &str = r#"{"word":"perro","pos":"noun","lang_code":"es","lang":"Spanish",
      "senses":[{"glosses":["dog"],"tags":["masculine"],
                 "examples":[{"text":"El perro ladra."}]},
                {"glosses":["a despicable person"],"tags":["derogatory"]}],
      "sounds":[{"ipa":"/ˈpe.ro/"},{"ipa":"[ˈpe.ro]"},{"audio":"es-perro.ogg"}],
      "forms":[{"form":"perros","tags":["plural"]},{"form":"perro"},
               {"form":"inflection-table","tags":["table-tags"]}],
      "tags":["masculine"]}"#;

    /// An entry that only names what it is a form of, with no sound of its own, is a bare
    /// form; one with a pronunciation, or a meaning of its own, is not.
    #[test]
    fn a_bare_form_is_one_that_only_points_and_has_no_sound() {
        let mut skipped = Skipped::default();
        let bare = r#"{"word":"absinthiated","pos":"verb","lang_code":"en",
          "senses":[{"glosses":["past participle of absinthiate"],"tags":["form-of","past"]}]}"#;
        let sounded = r#"{"word":"est","pos":"verb","lang_code":"fr","sounds":[{"ipa":"/ɛ/"}],
          "senses":[{"glosses":["third-person singular present indicative of être"],
                     "tags":["form-of"]}]}"#;
        let meaning = r#"{"word":"dog","pos":"noun","lang_code":"en",
          "senses":[{"glosses":["a mammal"]}]}"#;
        assert!(is_bare_form(
            &read_line(bare, "en", &mut skipped).unwrap().entry
        ));
        assert!(!is_bare_form(
            &read_line(sounded, "fr", &mut skipped).unwrap().entry
        ));
        assert!(!is_bare_form(
            &read_line(meaning, "en", &mut skipped).unwrap().entry
        ));
    }

    #[test]
    fn a_word_comes_out_of_its_line() {
        let mut skipped = Skipped::default();
        let read = read_line(PERRO, "es", &mut skipped).unwrap();
        assert_eq!(read.entry.lemma, "perro");
        assert_eq!(read.entry.pos, "noun");
        assert_eq!(read.entry.tags, vec!["masculine"]);
        // The delimiters are the notation's, not the transcription's, and the same
        // transcription written twice is one.
        assert_eq!(read.entry.ipa, vec!["ˈpe.ro"]);
        assert_eq!(read.entry.senses.len(), 2);
        assert_eq!(read.entry.senses[0].gloss, "dog");
        assert_eq!(read.entry.senses[0].marks, vec!["masculine"]);
        assert_eq!(
            read.entry.senses[0].example.as_deref(),
            Some("El perro ladra.")
        );
        assert_eq!(read.entry.senses[1].gloss, "a despicable person");
        // The lemma reaches itself, a table header is not a word, and a form is not repeated.
        // The form carries what the dump calls it, so a card can say "plural of perro".
        assert_eq!(
            read.entry.forms,
            vec![lexpack::Form {
                spelling: "perros".into(),
                label: "plural".into(),
            }]
        );
        assert_eq!(skipped, Skipped::default());
    }

    #[test]
    fn a_line_of_another_language_is_left_alone() {
        let mut skipped = Skipped::default();
        assert!(read_line(PERRO, "de", &mut skipped).is_none());
        assert_eq!(skipped.other_language, 1);
    }

    #[test]
    fn an_entry_brings_the_forms_a_reader_meets_and_not_every_one() {
        let mut forms: Vec<serde_json::Value> = Vec::new();
        for n in 0..200 {
            forms.push(serde_json::json!({
                "form": format!("talo{n}ni"),
                "tags": ["singular", "inessive", "first-person", "singular-possessive"],
            }));
        }
        forms.push(serde_json::json!({"form": "talot", "tags": ["plural", "nominative"]}));
        forms.push(serde_json::json!({"form": "talossa", "tags": ["singular", "inessive"]}));
        let line = serde_json::json!({
            "word": "talo", "pos": "noun", "lang_code": "fi",
            "senses": [{"glosses": ["house"]}], "forms": forms,
        })
        .to_string();
        let mut skipped = Skipped::default();
        let read = read_line(&line, "fi", &mut skipped).unwrap();
        let kept: Vec<&str> = read
            .entry
            .forms
            .iter()
            .map(|f| f.spelling.as_str())
            .collect();
        assert_eq!(kept.len(), MAX_FORMS);
        assert!(kept.contains(&"talot"), "the plural a reader meets is kept");
        assert!(kept.contains(&"talossa"), "and the case");
    }

    #[test]
    fn a_nested_sense_means_its_own_gloss_not_its_heading() {
        let line = serde_json::json!({
            "word": "sein", "pos": "verb", "lang_code": "de",
            "senses": [{"glosses": ["As a copulative verb:", "to be"]}],
        })
        .to_string();
        let read = read_line(&line, "de", &mut Skipped::default()).unwrap();
        assert_eq!(read.entry.senses[0].gloss, "to be");
    }

    #[test]
    fn a_name_is_not_reached_through_its_article() {
        let line = serde_json::json!({
            "word": "Kosovo", "pos": "name", "lang_code": "de",
            "senses": [{"glosses": ["Kosovo"]}],
            "forms": [
                {"form": "der", "tags": ["canonical", "masculine", "neuter"]},
                {"form": "Kosovos", "tags": ["genitive"]},
            ],
        })
        .to_string();
        let read = read_line(&line, "de", &mut Skipped::default()).unwrap();
        let kept: Vec<&str> = read
            .entry
            .forms
            .iter()
            .map(|f| f.spelling.as_str())
            .collect();
        assert_eq!(
            kept,
            vec!["Kosovos"],
            "the article alone is not a form of the name"
        );

        // The headword printed with its marks is kept, since that is the same word.
        let latin = serde_json::json!({
            "word": "amo", "pos": "verb", "lang_code": "la",
            "senses": [{"glosses": ["to love"]}],
            "forms": [{"form": "amō", "tags": ["canonical"]}],
        })
        .to_string();
        let read = read_line(&latin, "la", &mut Skipped::default()).unwrap();
        assert_eq!(read.entry.forms.len(), 1);
    }

    #[test]
    fn a_language_filed_under_another_code_is_taken_from_it() {
        let mut skipped = Skipped::default();
        // Wanted under any of the codes the dump files it by, as Croatian is under "sh".
        assert!(read_line_filed_as(PERRO, &["pt", "es"], &mut skipped).is_some());
        assert!(read_line_filed_as(PERRO, &["pt", "gl"], &mut skipped).is_none());
        assert_eq!(skipped.other_language, 1);
    }

    #[test]
    fn a_line_that_is_not_json_is_counted_rather_than_stopping_the_build() {
        let mut skipped = Skipped::default();
        assert!(read_line("{not json", "es", &mut skipped).is_none());
        assert!(read_line("", "es", &mut skipped).is_none());
        assert_eq!(skipped.unreadable, 1);
    }

    #[test]
    fn a_word_with_nothing_to_show_is_skipped() {
        let mut skipped = Skipped::default();
        let stub =
            r#"{"word":"perro","pos":"noun","lang_code":"es","senses":[{"tags":["form-of"]}]}"#;
        assert!(read_line(stub, "es", &mut skipped).is_none());
        assert_eq!(skipped.empty, 1);
        let nameless = r#"{"pos":"noun","lang_code":"es","senses":[{"glosses":["dog"]}]}"#;
        assert!(read_line(nameless, "es", &mut skipped).is_none());
        assert_eq!(skipped.nameless, 1);
    }

    #[test]
    fn a_word_with_no_senses_but_a_pronunciation_is_kept() {
        // An ipa pack is exactly this: how a word is said, and nothing about what it means.
        let mut skipped = Skipped::default();
        let sound =
            r#"{"word":"perro","pos":"noun","lang_code":"es","sounds":[{"ipa":"/ˈpe.ro/"}]}"#;
        let read = read_line(sound, "es", &mut skipped).unwrap();
        assert!(read.entry.senses.is_empty());
        assert_eq!(read.entry.ipa, vec!["ˈpe.ro"]);
    }
}
