//! The pack format: one file per language, read the same way in a browser and on a phone.
//!
//! A pack holds what one language's words are, how they are said, and what their senses mean
//! in English. Two packs make a pair without either knowing about the other, because both
//! carry the same English glosses and the gloss index below is what joins them.
//!
//! The file is read where it lies. A reader opens it as bytes and pays for the entries it
//! actually asks about: the key index is a transducer over the spellings, and the entries are
//! compressed in small blocks so that answering one word decompresses one block rather than a
//! language. Writing needs the reference compressor and happens once, in CI; reading uses a
//! Rust decompressor so the same code serves WebAssembly and every Android ABI.

use std::collections::HashMap;

pub mod varint;

mod reader;
pub use reader::{Entry, Form, Pack, PackError, Sense};

#[cfg(feature = "write")]
mod writer;
#[cfg(feature = "write")]
pub use writer::{Builder, WriteError};

/// What every pack starts with, so a file that is not one is refused rather than parsed.
pub const MAGIC: &[u8; 8] = b"LEXPACK\x01";

/// The format itself, not the data in it. A reader refuses a version it was not written
/// against, and packs are rebuilt and re-released rather than migrated on a device.
pub const FORMAT: u32 = 3;

/// How many entries share one compressed block.
///
/// The whole cost of a lookup is decompressing the block its entry sits in, so a small block
/// answers faster; a small block also compresses worse, because zstd has less to find
/// repetition in. Sixty-four is the first guess and is written here rather than spread through
/// the code so that measuring it later changes one number.
pub const ENTRIES_PER_BLOCK: usize = 64;

/// The sections of the file, in the order the header lists them.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Section {
    /// Spelling to a run of entry numbers: every lemma, and every inflected form the dump
    /// resolves. A run rather than one number, because a spelling is regularly several words -
    /// "book" is a noun and a verb, and a pack that could hold only one of them would lose half
    /// of every language's commonest words.
    Keys = 0,
    /// Normalised English gloss head to a run of senses that carry it. This is the whole of
    /// what makes a language pair cost nothing to build.
    Glosses = 1,
    /// The runs the gloss index points at: entry number and sense number, varint coded.
    GlossHits = 2,
    /// The entries, in blocks, each block compressed on its own.
    Blocks = 3,
    /// Where each block starts and how long it is, and where each entry starts inside its
    /// block once decompressed.
    BlockIndex = 4,
    /// The runs the key index points at: how many entries a spelling reaches, then their
    /// numbers.
    KeyHits = 5,
}

/// How many sections there are, which is what the header's table is sized by.
pub const SECTIONS: usize = 6;

/// What kind of pack this is: the transcriptions alone, or the whole lexicon.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    Ipa = 0,
    Lex = 1,
}

impl Kind {
    pub fn from_byte(b: u8) -> Option<Kind> {
        match b {
            0 => Some(Kind::Ipa),
            1 => Some(Kind::Lex),
            _ => None,
        }
    }
}

/// Where the parenthesis a gloss ends with opens, counting nested ones, where something comes
/// before it.
fn trailing_parenthesis(text: &str) -> Option<usize> {
    let text = text.trim_end();
    if !text.ends_with(')') {
        return None;
    }
    let mut depth = 0i32;
    for (at, c) in text.char_indices().rev() {
        match c {
            ')' => depth += 1,
            '(' => {
                depth -= 1;
                if depth == 0 {
                    return (at > 0).then_some(at);
                }
            }
            _ => {}
        }
    }
    None
}

/// Where an English word's translations into a pack's language are filed in its gloss index.
///
/// The English Wiktionary's translation tables say, per sense, what an English word is in each
/// language, written by people who speak both; a pack is built with the rows for its own
/// language (packbuild --tables) and files each English word's translations under a key no
/// gloss term can be, its part of speech and the word behind a byte no text holds. Ahead of
/// the join on glosses, which only guesses: "you" joined that way came out "vuestro".
pub fn translation_key(english: &str, pos: &str) -> String {
    format!("\u{1}{pos}\u{1}{}", english.to_lowercase())
}

/// The terms of a gloss's own list, split at the commas and semicolons outside any
/// parenthesis. Split inside one too, "(person, etc)" came apart into "another (person" and
/// "etc)", and every sense that ended a parenthetical list with "etc" was a sense of every
/// other such: "other" reached "chajazo", a knife wound.
fn listed(text: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut depth = 0i32;
    let mut from = 0;
    for (at, c) in text.char_indices() {
        match c {
            '(' | '“' => depth += 1,
            ')' | '”' => depth = (depth - 1).max(0),
            ',' | ';' if depth == 0 => {
                out.push(&text[from..at]);
                from = at + 1;
            }
            // Two spellings of one word, as Wiktionary sets them apart: "color / colour" is
            // either, and as one term no table has it. A slash inside a word is not that:
            // "him/her", "km/h".
            '/' if depth == 0 && text[..at].ends_with(' ') && text[at + 1..].starts_with(' ') => {
                out.push(&text[from..at]);
                from = at + 1;
            }
            _ => {}
        }
    }
    out.push(&text[from..]);
    out
}

/// The head phrase of an English gloss, as both sides of a join have to spell it.
///
/// Two packs are joined on glosses written by the same community in the same register, so what
/// has to be stripped is the writing habits rather than the meaning: case, a leading article, a
/// verb's leading "to", and a trailing parenthesis that qualifies rather than names. What is
/// left is the head phrase. "A chair (to sit on)" and "chair" both come out "chair"; "to book"
/// comes out "book", which is also where the noun lands, and the part of speech separates them
/// at lookup rather than here.
pub fn gloss_head(gloss: &str) -> String {
    // A note in square brackets is about how the word is used, never what it means: Italian
    // "correre" is "to run [auxiliary essere or avere] (see above)", and with the note left
    // in it met no other language's "run".
    let unbracketed = without_square_notes(gloss);
    let mut text = unbracketed.trim();
    // Only a trailing parenthesis, and only when something precedes it: a gloss that is all
    // parenthesis is naming the thing in it. The whole of it, nested ones included:
    // "dog (the species Canis familiaris (sometimes ...))" is "dog".
    if let Some(open) = trailing_parenthesis(text) {
        text = text[..open].trim_end();
    }
    // The first term of a list is the head; the rest narrow it and are indexed separately by
    // the caller. A list inside a parenthesis is not the gloss's own: "long wound done with a
    // bladed weapon (knife, machete, etc); a slash" is headed by the wound, and the
    // parenthesis that qualifies the first term goes the way a trailing one does.
    text = listed(text).first().copied().unwrap_or("").trim_end();
    if let Some(open) = trailing_parenthesis(text) {
        text = text[..open].trim_end();
    }
    let lower = text.trim().to_lowercase();
    for lead in ["to ", "the ", "a ", "an "] {
        if let Some(rest) = lower.strip_prefix(lead) {
            return rest.trim().to_string();
        }
    }
    lower
}

/// A gloss without its notes in square brackets.
fn without_square_notes(gloss: &str) -> String {
    let mut out = String::with_capacity(gloss.len());
    let mut depth = 0u32;
    for c in gloss.chars() {
        match c {
            '[' => depth += 1,
            ']' if depth > 0 => depth -= 1,
            _ if depth == 0 => out.push(c),
            _ => {}
        }
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Every gloss term a sense should be indexed under: its head, and each further term of a list.
///
/// "way, route" is indexed under both "way" and "route", because the source sense that reaches
/// it may name either. The terms are what narrow a join: "way" alone reaches eight German
/// lemmas and "way, route" reaches one.
pub fn gloss_terms(gloss: &str) -> Vec<String> {
    let mut out = Vec::new();
    let head = gloss_head(gloss);
    if !head.is_empty() {
        out.push(head);
    }
    let body = gloss.trim();
    let body = match trailing_parenthesis(body) {
        Some(open) => body[..open].trim_end(),
        None => body,
    };
    for part in listed(body).into_iter().skip(1) {
        let term = gloss_head(part);
        if !term.is_empty() && !out.contains(&term) {
            out.push(term);
        }
    }
    // What a contraction says it means word for word, which is how two languages' contractions
    // meet: Spanish "al" is "contraction of a + el, literally “at the, to the”" and German
    // "zum" "contraction of zu + dem, literally “to the; for the”", and neither is named by
    // its head.
    for part in listed(body) {
        let quoted = part
            .trim()
            .strip_prefix("literally")
            .and_then(|rest| rest.split_once('“'))
            .and_then(|(_, rest)| rest.split_once('”'))
            .map(|(meant, _)| meant);
        for each in quoted.map(listed).unwrap_or_default() {
            let term = gloss_head(each);
            if !term.is_empty() && !out.contains(&term) {
                out.push(term);
            }
        }
    }
    // The hedges and ties are left out beside a term that names something, and kept where
    // there is none: a preposition's whole gloss is "with", and without it Spanish "con" was
    // reached by nothing and German "mit" read as "también".
    if out.iter().any(|term| !idle(term)) {
        out.retain(|term| !idle(term));
    }
    out
}

/// Words a gloss sets between commas to narrow what follows rather than to name anything:
/// "bleary-eyed, especially, having watery eyes" and "the second in a series, especially, the
/// second of an artist's albums" share only "especially", and as a term it made "sophomore"
/// the Salvadoran word for bleary-eyed.
const HEDGES: [&str; 14] = [
    "especially",
    "particularly",
    "chiefly",
    "mainly",
    "mostly",
    "usually",
    "often",
    "generally",
    "typically",
    "specifically",
    "sometimes",
    "originally",
    "figuratively",
    "by extension",
];

/// Words that only tie others together: a term of nothing but these names nothing. "Of,
/// relating to, or characteristic of a sophomore" and "of, from or relating to Courland" both
/// begin with the term "of", and through it "sophomoric" was "curo", a Courlander.
const TIES: [&str; 14] = [
    "of", "from", "in", "on", "at", "to", "for", "with", "by", "or", "and", "as", "etc", "etc.",
];

/// Whether a term of a gloss names nothing to be matched on.
fn idle(term: &str) -> bool {
    HEDGES.contains(&term) || term.split_whitespace().all(|word| TIES.contains(&word))
}

/// The header, which is everything a reader needs before it touches a section.
#[derive(Clone, Debug)]
pub struct Header {
    pub format: u32,
    pub lang: String,
    pub kind: Kind,
    /// When the pack was built, in seconds since the epoch, so a manifest row and a file on a
    /// device can be told apart without hashing either.
    pub built: u64,
    pub entries: u32,
    /// Where each section starts and how long it is.
    pub sections: [(u64, u64); SECTIONS],
}

impl Header {
    pub fn at(&self, which: Section) -> (u64, u64) {
        self.sections[which as usize]
    }
}

/// A count of what a pack holds, for the manifest and for tests.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Counts {
    pub entries: usize,
    pub keys: usize,
    pub glosses: usize,
}

/// Which senses of which entries a gloss term reaches.
pub type GlossHits = HashMap<String, Vec<(u32, u32)>>;
