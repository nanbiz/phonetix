//! What the symbols of a transcription are, and how one is shown.
//!
//! A transcription is a row of sounds a reader can ask about one at a time, so something has
//! to split it into those sounds, say what each is called, and decide how much detail to show.
//! All three were written twice, once for each platform, which is how one sound gets two
//! names. They are here now, and both platforms ask.
//!
//! The table itself is authored in data/ipa-symbols.json and compiled in, so holding it costs
//! no parser and no file.

mod table;

pub use table::Row;

/// What is known about one symbol, after any marks on it have been accounted for.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Symbol {
    /// The symbol as it is written, marks included.
    pub token: String,
    pub name: String,
    /// Vowel, consonant, suprasegmental or diacritic.
    pub kind: String,
    /// A word it is heard in, where the table has one.
    pub example: String,
    /// A Commons recording of it, a Wikipedia article, a sagittal section, a Seeing Speech
    /// deep link. Empty where the table has none, because a row that leads nowhere is worse
    /// than no row.
    pub audio: String,
    pub wiki: String,
    pub diagram: String,
    pub seeing: String,
}

impl Symbol {
    fn of(token: &str, row: &Row) -> Symbol {
        Symbol {
            token: token.to_string(),
            name: row.name.to_string(),
            kind: row.kind.to_string(),
            example: row.example.to_string(),
            audio: row.audio.to_string(),
            wiki: row.wiki.to_string(),
            diagram: row.diagram.to_string(),
            seeing: row.seeing.to_string(),
        }
    }
}

fn row(token: &str) -> Option<&'static Row> {
    table::SYMBOLS.iter().find(|row| row.token == token)
}

fn diacritic(c: char) -> Option<&'static str> {
    let mut buffer = [0u8; 4];
    let text = c.encode_utf8(&mut buffer);
    table::DIACRITICS
        .iter()
        .find(|(mark, _)| *mark == text)
        .map(|(_, name)| *name)
}

/// Whether a mark is a symbol of its own rather than something hanging off one.
fn standalone(c: char) -> bool {
    let mut buffer = [0u8; 4];
    let text = c.encode_utf8(&mut buffer);
    table::STANDALONE.iter().any(|mark| *mark == text)
}

fn combining(c: char) -> bool {
    matches!(c as u32, 0x0300..=0x036F)
}

fn modifier(c: char) -> bool {
    matches!(c as u32, 0x02B0..=0x02FF)
}

fn tie(c: char) -> bool {
    c as u32 == 0x0361 || c as u32 == 0x035C
}

/// Split a transcription into the symbols a reader can ask about.
///
/// A base letter takes whatever hangs off it: the marks are not separate sounds, and offering
/// a reader a tap on a mark rather than on a sound is offering them nothing. A tie bar takes
/// the letter after it too, because an affricate is one sound written with two letters.
/// Stress and length stand alone, since those are what they are.
pub fn tokenize(ipa: &str) -> Vec<String> {
    let chars: Vec<char> = ipa.chars().collect();
    let mut out = Vec::new();
    let mut at = 0;
    while at < chars.len() {
        let mut token = String::new();
        token.push(chars[at]);
        at += 1;
        while at < chars.len() {
            let c = chars[at];
            if combining(c) || (modifier(c) && !standalone(c)) {
                token.push(c);
                at += 1;
                if tie(c) && at < chars.len() {
                    token.push(chars[at]);
                    at += 1;
                }
            } else {
                break;
            }
        }
        out.push(token);
    }
    out
}

/// What one symbol is, marks and all.
///
/// A symbol the table holds answers directly. One with marks on it is described rather than
/// looked up: listing every combination would be hopeless, and describing "kʰ" by its first
/// letter presents an aspirated sound as the plain one.
pub fn describe(token: &str) -> Option<Symbol> {
    if let Some(found) = row(token) {
        return Some(Symbol::of(token, found));
    }

    // A vowel with a mark may arrive as one code point, which no table of base sounds can
    // hold; taking it apart separates the sound from the mark on it.
    let mut marks: Vec<&str> = Vec::new();
    let mut base = String::new();
    for c in decomposed(token) {
        match diacritic(c) {
            Some(name) => {
                if !marks.contains(&name) {
                    marks.push(name)
                }
            }
            None => base.push(c),
        }
    }
    let found = row(&recomposed(&base))?;
    if marks.is_empty() {
        return Some(Symbol::of(token, found));
    }

    // A voicing mark replaces the sound's own voicing rather than adding to it: z̥ is
    // voiceless, not "voiceless voiced".
    let mut name = found.name.to_string();
    if marks.contains(&"voiceless") || marks.contains(&"voiced") {
        for lead in ["voiceless ", "voiced "] {
            if let Some(rest) = name.strip_prefix(lead) {
                name = rest.to_string();
                break;
            }
        }
    }
    let said = marks.join(", ");
    Some(Symbol {
        token: token.to_string(),
        name: format!("{said} {name}"),
        // The example belongs to the plain sound, and the mark is what makes this symbol
        // different from it, so the mark is named rather than exemplified.
        example: if found.example.is_empty() {
            String::new()
        } else {
            format!("{} ({said})", found.example)
        },
        ..Symbol::of(token, found)
    })
}

/// Every symbol of a transcription that the table can say something about.
pub fn explain(ipa: &str) -> Vec<Symbol> {
    tokenize(ipa).iter().filter_map(|t| describe(t)).collect()
}

/// The article on one term of a description, where Wikipedia has one.
///
/// A description stacks independent facts, so each term is linked on its own rather than the
/// whole phrase pointing at one of them.
pub fn term(word: &str) -> Option<&'static str> {
    let lower = word.to_lowercase();
    table::TERMS
        .iter()
        .find(|(term, _)| *term == lower)
        .map(|(_, article)| *article)
}

/// Marks that say how a sound is coloured rather than which sound it is.
///
/// "cause" is /kɔːz/ broadly and [kʰoːz̥] narrowly; dropping these turns the second back into
/// the first. Length, nasalisation and syllabicity are not here: they change the sound rather
/// than its shade.
fn narrow_detail(c: char) -> bool {
    matches!(
        c as u32,
        0x02B0
            | 0x02B1
            | 0x0325
            | 0x032C
            | 0x031D
            | 0x031E
            | 0x031F
            | 0x0320
            | 0x032A
            | 0x033A
            | 0x033B
            | 0x030A
            | 0x0308
            | 0x031A
            | 0x02DE
            | 0x02E0
            | 0x0334
            | 0x0318
            | 0x0319
            | 0x0339
            | 0x031C
    )
}

fn stress(c: char) -> bool {
    c == 'ˈ' || c == 'ˌ'
}

/// How a transcription is shown, given what the reader asked for.
///
/// The card always shows the full form; this is for the line over a word, where a syllable
/// break says nothing a reader cannot see and a length mark in brackets says the vowel may be
/// held or not, which is a fact about the word rather than about this reading of it.
/// Whether two transcriptions are of the same sounds, allowing for how they were written.
///
/// Two tables built from the same dump still write a word differently: one marks stress and
/// the other does not, one separates syllables with a dot, one writes length and one leaves it
/// off. What is being asked here is whether they describe the same word, which is a question
/// about the sounds and not about the notation.
pub fn same_sound(one: &str, other: &str) -> bool {
    fn bare(text: &str) -> String {
        text.chars()
            .filter(|c| {
                !matches!(
                    c,
                    'ˈ' | 'ˌ' | '.' | 'ː' | 'ˑ' | ' ' | '\'' | '/' | '[' | ']'
                )
            })
            .flat_map(|c| c.to_lowercase())
            .collect()
    }
    let (one, other) = (bare(one), bare(other));
    !one.is_empty() && one == other
}

pub fn display(ipa: &str, narrow: bool, hide_stress: bool) -> String {
    let mut out = String::with_capacity(ipa.len());
    let chars: Vec<char> = ipa.chars().collect();
    let mut at = 0;
    while at < chars.len() {
        let c = chars[at];
        // "(ː)" keeps the length and loses the brackets.
        if c == '('
            && at + 2 < chars.len()
            && (chars[at + 1] == 'ː' || chars[at + 1] == 'ˑ')
            && chars[at + 2] == ')'
        {
            out.push(chars[at + 1]);
            at += 3;
            continue;
        }
        at += 1;
        if c == '.' {
            continue;
        }
        if !narrow && narrow_detail(c) {
            continue;
        }
        if hide_stress && stress(c) {
            continue;
        }
        out.push(c);
    }
    out
}

/// A string taken apart into base characters and marks, the way Unicode composes them.
fn decomposed(text: &str) -> Vec<char> {
    let mut out = Vec::new();
    for c in text.chars() {
        match precomposed(c) {
            Some((base, mark)) => {
                out.push(base);
                out.push(mark);
            }
            None => out.push(c),
        }
    }
    out
}

/// The few precomposed vowels a transcription actually carries, as their parts.
///
/// The whole of Unicode's decomposition is a table nobody here needs: what arrives in a
/// transcription is a vowel with a tilde, an acute or a diaeresis on it.
fn precomposed(c: char) -> Option<(char, char)> {
    let (base, mark) = match c {
        'ã' => ('a', '\u{0303}'),
        'ẽ' => ('e', '\u{0303}'),
        'ĩ' => ('i', '\u{0303}'),
        'õ' => ('o', '\u{0303}'),
        'ũ' => ('u', '\u{0303}'),
        'ñ' => ('n', '\u{0303}'),
        'ä' => ('a', '\u{0308}'),
        'ë' => ('e', '\u{0308}'),
        'ï' => ('i', '\u{0308}'),
        'ö' => ('o', '\u{0308}'),
        'ü' => ('u', '\u{0308}'),
        _ => return None,
    };
    Some((base, mark))
}

/// The base of a decomposition put back together, for looking it up in the table.
fn recomposed(base: &str) -> String {
    base.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_transcription_is_its_sounds() {
        assert_eq!(tokenize("kæt"), ["k", "æ", "t"]);
    }

    #[test]
    fn an_affricate_is_one_sound() {
        assert_eq!(tokenize("t͡ʃiz"), ["t͡ʃ", "i", "z"]);
    }

    #[test]
    fn stress_and_length_stand_alone() {
        assert_eq!(tokenize("ˈhɛloʊ"), ["ˈ", "h", "ɛ", "l", "o", "ʊ"]);
        assert_eq!(tokenize("aː"), ["a", "ː"]);
    }

    #[test]
    fn a_mark_belongs_to_the_sound_it_is_on() {
        assert_eq!(tokenize("kʰæt"), ["kʰ", "æ", "t"]);
        assert_eq!(tokenize("n̩"), ["n̩"]);
    }

    #[test]
    fn a_symbol_the_table_holds_is_named() {
        let found = describe("p").expect("p is in the table");
        assert_eq!(found.name, "voiceless bilabial plosive");
        assert_eq!(found.kind, "consonant");
        assert!(!found.wiki.is_empty());
    }

    #[test]
    fn a_marked_symbol_is_described_rather_than_looked_up() {
        let found = describe("kʰ").expect("k is in the table");
        assert_eq!(found.name, "aspirated voiceless velar plosive");
    }

    #[test]
    fn a_voicing_mark_replaces_the_sounds_own() {
        let found = describe("z̥").expect("z is in the table");
        assert_eq!(found.name, "voiceless alveolar fricative");
    }

    #[test]
    fn a_symbol_nothing_knows_about_is_nothing() {
        assert!(describe("§").is_none());
    }

    #[test]
    fn the_broad_form_drops_what_only_colours_a_sound() {
        assert_eq!(display("kʰoːz̥", false, false), "koːz");
        assert_eq!(display("kʰoːz̥", true, false), "kʰoːz̥");
    }

    #[test]
    fn running_text_loses_the_notation_that_is_not_a_sound() {
        assert_eq!(display("ˈtuː(ː)l.tɪp", false, false), "ˈtuːːltɪp");
        assert_eq!(display("ˈhɛ.loʊ", false, true), "hɛloʊ");
    }

    #[test]
    fn every_term_of_a_name_can_be_read_about() {
        assert!(term("plosive").is_some());
        assert!(term("Plosive").is_some());
    }
}
