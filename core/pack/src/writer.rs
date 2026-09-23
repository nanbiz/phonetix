//! Writing a pack.
//!
//! This runs once per language in CI, on a real machine, so it may use the reference
//! compressor and hold what it is building in memory. The reader makes neither assumption.

use std::collections::BTreeMap;

use crate::{varint, Counts, Entry, Kind, Section, ENTRIES_PER_BLOCK, FORMAT, MAGIC, SECTIONS};

#[derive(Debug)]
pub enum WriteError {
    /// The key index would not build, which at this point can only be a key out of order.
    Index(String),
    Compress(String),
}

/// One block, as the reader will find it.
///
/// With the reference compressor, which is where a pack built on a real machine comes from.
#[cfg(feature = "zstd")]
fn squeeze(plain: &[u8]) -> Result<Vec<u8>, WriteError> {
    // Level three: the pack is downloaded once and read for as long as the language is
    // installed, and the levels above it cost build minutes for single-figure percents.
    zstd::encode_all(plain, 3).map_err(|e| WriteError::Compress(e.to_string()))
}

/// And without it: a zstd frame whose blocks are stored rather than compressed.
///
/// A pack built where the product runs - a browser, a phone - cannot carry the reference
/// compressor: it is C, and building it for WebAssembly needs a toolchain a reader does not
/// have. The frame below is written by hand and is a legal zstd stream, so the same reader
/// reads both kinds and neither the format nor the reader knows the difference. It costs the
/// size the compression would have saved, which is a device's disk rather than a download.
#[cfg(not(feature = "zstd"))]
fn squeeze(plain: &[u8]) -> Result<Vec<u8>, WriteError> {
    /// What one raw block may hold, from the format: 128 KiB.
    const MOST: usize = 128 * 1024;
    let mut out = Vec::with_capacity(plain.len() + 16);
    out.extend_from_slice(&0xFD2F_B528u32.to_le_bytes());
    // No dictionary, no checksum, no declared content size, and a window big enough for the
    // blocks below: the descriptor's exponent 10 is a window of a megabyte.
    out.push(0x00);
    out.push(10 << 3);
    let mut left = plain;
    loop {
        let take = left.len().min(MOST);
        let last = take == left.len();
        let header = ((take as u32) << 3) | (0 << 1) | u32::from(last);
        out.extend_from_slice(&header.to_le_bytes()[..3]);
        out.extend_from_slice(&left[..take]);
        left = &left[take..];
        if last {
            break;
        }
    }
    Ok(out)
}

/// A pack under construction.
pub struct Builder {
    lang: String,
    kind: Kind,
    built: u64,
    entries: Vec<Entry>,
    /// Spelling to the entries it reaches, kept sorted because the index is built in order.
    /// Several, because "book" is a noun and a verb and the pack holds both.
    keys: BTreeMap<String, Vec<u32>>,
    /// Gloss term to the senses that carry it, likewise.
    glosses: BTreeMap<String, Vec<(u32, u32)>>,
    /// Whether the gloss index is built at all. See [Builder::without_joins].
    joins: bool,
}

impl Builder {
    pub fn new(lang: &str, kind: Kind, built: u64) -> Builder {
        Builder {
            lang: lang.to_string(),
            kind,
            built,
            entries: Vec::new(),
            keys: BTreeMap::new(),
            glosses: BTreeMap::new(),
            joins: true,
        }
    }

    /// A pack nothing is ever joined into, so it carries no gloss index.
    ///
    /// The index is how a word in another language reaches this one: Spanish "perro" glosses
    /// "dog", and "dog" in the index is how German "Hund" is found. English is never the far
    /// side of that - a reader of English is handed the gloss itself - and in the English
    /// dictionary the index is fifty megabytes of its hundred and fifty.
    pub fn without_joins(mut self) -> Builder {
        self.joins = false;
        self
    }

    /// Add one word, under its lemma and under every form that resolves to it.
    ///
    /// The gloss index is filled here rather than in a pass of its own, because every term of
    /// every sense is wanted and the sense is in hand.
    pub fn add<S: AsRef<str>>(&mut self, entry: Entry, extra: &[S]) -> Result<u32, WriteError> {
        let which = self.entries.len() as u32;
        // The entry's own forms, and anything the caller adds beyond them. A form is a
        // spelling that reaches this entry whether or not the dump named what it is.
        let spellings = std::iter::once(entry.lemma.as_str())
            .chain(entry.forms.iter().map(|f| f.spelling.as_str()))
            .chain(extra.iter().map(|f| f.as_ref()));
        for spelling in spellings {
            let reached = self.keys.entry(spelling.to_string()).or_default();
            // A form listed twice for the same entry is the dump repeating itself, not a
            // second word.
            if !reached.contains(&which) {
                reached.push(which);
            }
        }
        for (number, sense) in entry.senses.iter().enumerate().filter(|_| self.joins) {
            for term in crate::gloss_terms(&sense.gloss) {
                self.glosses
                    .entry(term)
                    .or_default()
                    .push((which, number as u32));
            }
        }
        self.entries.push(entry);
        Ok(which)
    }

    pub fn counts(&self) -> Counts {
        Counts {
            entries: self.entries.len(),
            keys: self.keys.len(),
            glosses: self.glosses.len(),
        }
    }

    /// Lay the pack out and return its bytes.
    pub fn finish(self) -> Result<Vec<u8>, WriteError> {
        // The entries first, because the header has to say where every section landed and the
        // blocks are what decide the sizes.
        let mut blocks: Vec<(u64, u64)> = Vec::new();
        let mut starts: Vec<u32> = Vec::with_capacity(self.entries.len());
        let mut packed: Vec<u8> = Vec::new();
        for chunk in self.entries.chunks(ENTRIES_PER_BLOCK) {
            let mut plain = Vec::new();
            for entry in chunk {
                starts.push(plain.len() as u32);
                write_entry(&mut plain, entry);
            }
            let block = squeeze(&plain)?;
            blocks.push((packed.len() as u64, block.len() as u64));
            packed.extend_from_slice(&block);
        }

        // Where each spelling's run of entries sits, and the runs themselves.
        let mut key_hits: Vec<u8> = Vec::new();
        let mut key_index: Vec<(&str, u64)> = Vec::with_capacity(self.keys.len());
        for (spelling, reached) in self.keys.iter() {
            key_index.push((spelling.as_str(), key_hits.len() as u64));
            varint::put(&mut key_hits, reached.len() as u64);
            for which in reached {
                varint::put(&mut key_hits, *which as u64);
            }
        }
        let keys = build_index(key_index.into_iter())?;

        // Where each gloss term's run of senses sits, and the runs themselves.
        let mut hits: Vec<u8> = Vec::new();
        let mut gloss_index: Vec<(&str, u64)> = Vec::with_capacity(self.glosses.len());
        for (term, senses) in self.glosses.iter() {
            gloss_index.push((term.as_str(), hits.len() as u64));
            varint::put(&mut hits, senses.len() as u64);
            for (entry, sense) in senses {
                varint::put(&mut hits, *entry as u64);
                varint::put(&mut hits, *sense as u64);
            }
        }
        let glosses = build_index(gloss_index.into_iter())?;

        let mut index: Vec<u8> = Vec::new();
        varint::put(&mut index, blocks.len() as u64);
        for (offset, length) in &blocks {
            varint::put(&mut index, *offset);
            varint::put(&mut index, *length);
        }
        for start in &starts {
            varint::put(&mut index, *start as u64);
        }

        // The header is written twice: once with the offsets unknown so that its own length is
        // settled, and once for real. A section table of variable-length numbers cannot say
        // where anything is until it knows how long it is itself.
        let sizes = [
            keys.len() as u64,
            glosses.len() as u64,
            hits.len() as u64,
            packed.len() as u64,
            index.len() as u64,
            key_hits.len() as u64,
        ];
        let mut sections = [(0u64, 0u64); SECTIONS];
        let mut header_len = self.header(&sections).len() as u64;
        loop {
            let mut at = header_len;
            for (slot, size) in sections.iter_mut().zip(sizes.iter()) {
                *slot = (at, *size);
                at += size;
            }
            let again = self.header(&sections).len() as u64;
            if again == header_len {
                break;
            }
            header_len = again;
        }

        let mut out = self.header(&sections);
        debug_assert_eq!(out.len() as u64, header_len);
        out.extend_from_slice(&keys);
        out.extend_from_slice(&glosses);
        out.extend_from_slice(&hits);
        out.extend_from_slice(&packed);
        out.extend_from_slice(&index);
        out.extend_from_slice(&key_hits);
        debug_assert_eq!(sections[Section::Keys as usize].0, header_len);
        Ok(out)
    }

    fn header(&self, sections: &[(u64, u64); SECTIONS]) -> Vec<u8> {
        let mut out = MAGIC.to_vec();
        varint::put(&mut out, FORMAT as u64);
        varint::put_str(&mut out, &self.lang);
        out.push(self.kind as u8);
        varint::put(&mut out, self.built);
        varint::put(&mut out, self.entries.len() as u64);
        for (offset, length) in sections {
            varint::put(&mut out, *offset);
            varint::put(&mut out, *length);
        }
        out
    }
}

fn build_index<'a, I: Iterator<Item = (&'a str, u64)>>(pairs: I) -> Result<Vec<u8>, WriteError> {
    let mut builder = fst::MapBuilder::memory();
    for (key, value) in pairs {
        builder
            .insert(key, value)
            .map_err(|e| WriteError::Index(e.to_string()))?;
    }
    builder
        .into_inner()
        .map_err(|e| WriteError::Index(e.to_string()))
}

fn write_entry(out: &mut Vec<u8>, entry: &Entry) {
    varint::put_str(out, &entry.lemma);
    varint::put_str(out, &entry.pos);
    write_strings(out, &entry.tags);
    write_strings(out, &entry.ipa);
    varint::put(out, entry.senses.len() as u64);
    for sense in &entry.senses {
        varint::put_str(out, &sense.gloss);
        write_strings(out, &sense.marks);
        match &sense.example {
            Some(text) => {
                varint::put(out, 1);
                varint::put_str(out, text);
            }
            None => varint::put(out, 0),
        }
    }
    varint::put(out, entry.forms.len() as u64);
    for form in &entry.forms {
        varint::put_str(out, &form.spelling);
        varint::put_str(out, &form.label);
    }
}

fn write_strings(out: &mut Vec<u8>, items: &[String]) {
    varint::put(out, items.len() as u64);
    for item in items {
        varint::put_str(out, item);
    }
}
