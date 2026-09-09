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
        if !code.eq_ignore_ascii_case(lang) {
            skipped.other_language += 1;
            return None;
        }
    }
    let word = value.get("word").and_then(|v| v.as_str()).unwrap_or("").trim();
    let pos = value.get("pos").and_then(|v| v.as_str()).unwrap_or("").trim();
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
        },
        forms: forms(&value, word),
    })
}

/// How the word is said, as the dump records it.
///
/// Only the transcriptions, and only in the notation this reads. A sound entry can be a
/// recording, a rhyme or a respelling instead, and each of those is somebody else's field.
fn pronunciations(value: &Value) -> Vec<String> {
    let mut out = Vec::new();
    for sound in value.get("sounds").and_then(|v| v.as_array()).into_iter().flatten() {
        if let Some(ipa) = sound.get("ipa").and_then(|v| v.as_str()) {
            let ipa = ipa.trim().trim_matches(|c| c == '/' || c == '[' || c == ']').trim();
            if !ipa.is_empty() && !out.iter().any(|had: &String| had == ipa) {
                out.push(ipa.to_string());
            }
        }
    }
    out
}

/// What the word means, in English, which is what joins two packs into a pair.
fn senses(value: &Value) -> Vec<Sense> {
    let mut out = Vec::new();
    for sense in value.get("senses").and_then(|v| v.as_array()).into_iter().flatten() {
        // A sense with no gloss is a cross reference or a form-of stub: it carries no meaning
        // of its own and nothing can be joined to it.
        let glosses = strings(sense.get("glosses"));
        let Some(gloss) = glosses.first() else { continue };
        if gloss.trim().is_empty() {
            continue;
        }
        out.push(Sense {
            gloss: gloss.trim().to_string(),
            marks: strings(sense.get("tags")),
            example: sense
                .get("examples")
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
fn forms(value: &Value, word: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for form in value.get("forms").and_then(|v| v.as_array()).into_iter().flatten() {
        let Some(spelling) = form.get("form").and_then(|v| v.as_str()) else { continue };
        let spelling = spelling.trim();
        if spelling.is_empty() || spelling == word || spelling == "-" {
            continue;
        }
        // A table header the extractor kept as a form, which is a label rather than a word.
        if form.get("tags").is_some_and(|tags| {
            strings(Some(tags)).iter().any(|t| t == "table-tags" || t == "inflection-template")
        }) {
            continue;
        }
        if !out.iter().any(|had| had == spelling) {
            out.push(spelling.to_string());
        }
    }
    out
}

fn strings(value: Option<&Value>) -> Vec<String> {
    value
        .and_then(|v| v.as_array())
        .map(|list| {
            list.iter().filter_map(|v| v.as_str()).map(|s| s.trim().to_string()).collect()
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
        assert_eq!(read.entry.senses[0].example.as_deref(), Some("El perro ladra."));
        assert_eq!(read.entry.senses[1].gloss, "a despicable person");
        // The lemma reaches itself, a table header is not a word, and a form is not repeated.
        assert_eq!(read.forms, vec!["perros"]);
        assert_eq!(skipped, Skipped::default());
    }

    #[test]
    fn a_line_of_another_language_is_left_alone() {
        let mut skipped = Skipped::default();
        assert!(read_line(PERRO, "de", &mut skipped).is_none());
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
        let stub = r#"{"word":"perro","pos":"noun","lang_code":"es","senses":[{"tags":["form-of"]}]}"#;
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
        let sound = r#"{"word":"perro","pos":"noun","lang_code":"es","sounds":[{"ipa":"/ˈpe.ro/"}]}"#;
        let read = read_line(sound, "es", &mut skipped).unwrap();
        assert!(read.entry.senses.is_empty());
        assert_eq!(read.entry.ipa, vec!["ˈpe.ro"]);
    }
}
