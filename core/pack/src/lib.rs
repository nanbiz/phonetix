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
pub use reader::{Entry, Pack, PackError, Sense};

#[cfg(feature = "write")]
mod writer;
#[cfg(feature = "write")]
pub use writer::{Builder, WriteError};

/// What every pack starts with, so a file that is not one is refused rather than parsed.
pub const MAGIC: &[u8; 8] = b"LEXPACK\x01";

/// The format itself, not the data in it. A reader refuses a version it was not written
/// against, and packs are rebuilt and re-released rather than migrated on a device.
pub const FORMAT: u32 = 2;

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

/// The head phrase of an English gloss, as both sides of a join have to spell it.
///
/// Two packs are joined on glosses written by the same community in the same register, so what
/// has to be stripped is the writing habits rather than the meaning: case, a leading article, a
/// verb's leading "to", and a trailing parenthesis that qualifies rather than names. What is
/// left is the head phrase. "A chair (to sit on)" and "chair" both come out "chair"; "to book"
/// comes out "book", which is also where the noun lands, and the part of speech separates them
/// at lookup rather than here.
pub fn gloss_head(gloss: &str) -> String {
    let mut text = gloss.trim();
    // Only a trailing parenthesis, and only when something precedes it: a gloss that is all
    // parenthesis is naming the thing in it.
    if let Some(open) = text.rfind('(') {
        if text.trim_end().ends_with(')') && open > 0 {
            text = text[..open].trim_end();
        }
    }
    // The first term of a list is the head; the rest narrow it and are indexed separately by
    // the caller.
    if let Some(comma) = text.find(',') {
        text = text[..comma].trim_end();
    }
    if let Some(semi) = text.find(';') {
        text = text[..semi].trim_end();
    }
    let lower = text.trim().to_lowercase();
    for lead in ["to ", "the ", "a ", "an "] {
        if let Some(rest) = lower.strip_prefix(lead) {
            return rest.trim().to_string();
        }
    }
    lower
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
    let body = match body.rfind('(') {
        Some(open) if body.ends_with(')') && open > 0 => body[..open].trim_end(),
        _ => body,
    };
    for part in body.split([',', ';']).skip(1) {
        let term = gloss_head(part);
        if !term.is_empty() && !out.contains(&term) {
            out.push(term);
        }
    }
    out
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
