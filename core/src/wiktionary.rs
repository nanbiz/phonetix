//! What a Wiktionary page says about how a word is said.
//!
//! A pack holds what the dump held, and the dump is a snapshot. The page itself carries two
//! things worth having beyond it: a transcription a person wrote, and a recording of a person
//! saying the word. Both are what a reader trusts most, and neither is in the pack.
//!
//! Reading the page is the same job on both platforms, so it is here; fetching it is not, so
//! that stays with each host. Only the English edition is read, because that is the edition
//! the packs are built from and the one the card links to.

use crate::languages;

/// What one language's section of a page says.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct Said {
    /// The language the section is for, as a code.
    pub lang: String,
    /// Every transcription in it, in the order the page gives them, which is the order a
    /// reader would meet them: the general one first, the regional ones after.
    pub ipa: Vec<String>,
    /// Recordings, as Commons file names. A person saying the word, which is worth more than
    /// anything synthesised.
    pub audio: Vec<String>,
}

/// Read what a page says about a word in one language.
///
/// The wanted language is looked for first and the rest ignored: a page for "banco" holds
/// Spanish, Italian and Portuguese, and the reader is reading one of them.
pub fn parse(wikitext: &str, want: Option<&str>) -> Option<Said> {
    let sections = split(wikitext);
    if let Some(want) = want {
        for (lang, body) in &sections {
            if lang == want {
                return Some(read(lang, body));
            }
        }
        return None;
    }
    sections.first().map(|(lang, body)| read(lang, body))
}

/// The page's level-two sections, each headed by a language's English name.
fn split(wikitext: &str) -> Vec<(String, &str)> {
    let mut out: Vec<(String, &str)> = Vec::new();
    let mut heading: Option<String> = None;
    let mut from = 0usize;
    for (at, line) in offsets(wikitext) {
        let trimmed = line.trim();
        let is_language = trimmed.starts_with("==")
            && !trimmed.starts_with("===")
            && trimmed.ends_with("==")
            && trimmed.len() > 4;
        if !is_language {
            continue;
        }
        if let Some(lang) = heading.take() {
            out.push((lang, &wikitext[from..at]));
        }
        let name = trimmed.trim_matches('=').trim();
        heading = languages::code_of(name).map(|code| code.to_string());
        from = at;
    }
    if let Some(lang) = heading {
        out.push((lang, &wikitext[from..]));
    }
    out
}

/// Each line of a text with where it starts.
fn offsets(text: &str) -> Vec<(usize, &str)> {
    let mut out = Vec::new();
    let mut at = 0;
    for line in text.split('\n') {
        out.push((at, line));
        at += line.len() + 1;
    }
    out
}

fn read(lang: &str, body: &str) -> Said {
    let mut said = Said {
        lang: lang.to_string(),
        ..Default::default()
    };
    for template in templates(body) {
        let mut parts = template.split('|').map(str::trim);
        let Some(name) = parts.next() else { continue };
        let name = name.to_lowercase();
        let rest: Vec<&str> = parts.collect();
        if name == "ipa" || name == "ipa-lite" {
            for part in &rest {
                // A transcription is written between slashes or brackets; everything else in
                // the template is a language code or a label.
                let text = part.trim();
                let inner = text
                    .strip_prefix('/')
                    .and_then(|t| t.strip_suffix('/'))
                    .or_else(|| text.strip_prefix('[').and_then(|t| t.strip_suffix(']')));
                if let Some(inner) = inner {
                    let inner = inner.trim().to_string();
                    if !inner.is_empty() && !said.ipa.contains(&inner) {
                        said.ipa.push(inner);
                    }
                }
            }
        }
        // A recording is named either by the audio template or inside a language's own
        // pronunciation template, which writes it as "audio:File.wav". Spanish and Italian
        // use the second and would otherwise come back with no recording at all.
        for file in sounds(template) {
            if !said.audio.contains(&file) {
                said.audio.push(file);
            }
        }
    }
    said
}

/// Every sound file named anywhere in a template.
///
/// A file is recognised by its own name rather than by where it sits, because the parameter
/// it sits in differs per language and the name does not.
fn sounds(template: &str) -> Vec<String> {
    let mut out = Vec::new();
    for part in template.split(['|', '<', '>', '=']) {
        let file = part.trim().trim_start_matches("audio:").trim();
        let lower = file.to_lowercase();
        if [".ogg", ".oga", ".wav", ".mp3", ".flac"]
            .iter()
            .any(|end| lower.ends_with(end))
        {
            out.push(file.to_string());
        }
    }
    out
}

/// The templates of a section: what is between {{ and the matching }}.
fn templates(body: &str) -> Vec<&str> {
    let bytes = body.as_bytes();
    let mut out = Vec::new();
    let mut at = 0;
    while at + 1 < bytes.len() {
        if bytes[at] == b'{' && bytes[at + 1] == b'{' {
            let start = at + 2;
            let mut depth = 1;
            let mut cursor = start;
            while cursor + 1 < bytes.len() {
                if bytes[cursor] == b'{' && bytes[cursor + 1] == b'{' {
                    depth += 1;
                    cursor += 2;
                } else if bytes[cursor] == b'}' && bytes[cursor + 1] == b'}' {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                    cursor += 2;
                } else {
                    cursor += 1;
                }
            }
            if depth == 0 && body.is_char_boundary(start) && body.is_char_boundary(cursor) {
                out.push(&body[start..cursor]);
                at = cursor + 2;
                continue;
            }
        }
        at += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const PAGE: &str = r#"
==Spanish==

===Pronunciation===
* {{IPA|es|/ˈpe.ro/|[ˈpe.ɾo]}}
* {{audio|es|LL-Q1321 (spa)-perro.wav|a=Spain}}

===Noun===
{{es-noun|m}}

==Portuguese==

===Pronunciation===
* {{IPA|pt|/ˈpɛ.ʁu/}}
"#;

    #[test]
    fn the_wanted_language_is_the_one_read() {
        let said = parse(PAGE, Some("pt")).expect("the page has Portuguese");
        assert_eq!(said.lang, "pt");
        assert_eq!(said.ipa, ["ˈpɛ.ʁu"]);
    }

    #[test]
    fn a_transcription_a_person_wrote_comes_out_whole() {
        let said = parse(PAGE, Some("es")).expect("the page has Spanish");
        assert_eq!(said.ipa, ["ˈpe.ro", "ˈpe.ɾo"]);
    }

    #[test]
    fn a_recording_is_a_file_to_fetch() {
        let said = parse(PAGE, Some("es")).expect("the page has Spanish");
        assert_eq!(said.audio, ["LL-Q1321 (spa)-perro.wav"]);
    }

    #[test]
    fn a_recording_inside_a_languages_own_template_is_found_too() {
        // What the Spanish section of "perro" actually holds: no transcription in the
        // wikitext at all, since a module writes it, and the recording named inside the
        // template's own parameter syntax.
        let page = "==Spanish==\n{{es-pr|+<audio:LL-Q1321 (spa)-Millars-perro.wav<a:Spain>>}}\n";
        let said = parse(page, Some("es")).expect("the page has Spanish");
        assert_eq!(said.audio, ["LL-Q1321 (spa)-Millars-perro.wav"]);
        assert!(said.ipa.is_empty(), "the wikitext states none");
    }

    #[test]
    fn a_language_the_page_does_not_hold_is_nothing() {
        assert!(parse(PAGE, Some("de")).is_none());
    }

    #[test]
    fn a_page_that_is_not_one_says_nothing() {
        assert!(parse("", Some("es")).is_none());
        assert!(parse("just some text", Some("es")).is_none());
    }
}
