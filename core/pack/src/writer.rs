//! Writing a pack.
//!
//! This runs once per language in CI, on a real machine, so it may use the reference
//! compressor and hold what it is building in memory. The reader makes neither assumption.

use std::collections::BTreeMap;

use crate::{varint, Counts, Entry, Kind, Section, ENTRIES_PER_BLOCK, FORMAT, MAGIC, SECTIONS};

#[derive(Debug)]
pub enum WriteError {
    /// Two entries claim the same spelling. The dump has one lemma per (word, part of speech),
    /// so this is the builder's caller merging them wrongly rather than the data.
    DuplicateKey(String),
    /// The key index would not build, which at this point can only be a key out of order.
    Index(String),
    Compress(String),
}

/// A pack under construction.
pub struct Builder {
    lang: String,
    kind: Kind,
    built: u64,
    entries: Vec<Entry>,
    /// Spelling to entry number, kept sorted because the index is built in order.
    keys: BTreeMap<String, u64>,
    /// Gloss term to the senses that carry it, likewise.
    glosses: BTreeMap<String, Vec<(u32, u32)>>,
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
        }
    }

    /// Add one word, under its lemma and under every form that resolves to it.
    ///
    /// The gloss index is filled here rather than in a pass of its own, because every term of
    /// every sense is wanted and the sense is in hand.
    pub fn add<S: AsRef<str>>(&mut self, entry: Entry, forms: &[S]) -> Result<u32, WriteError> {
        let which = self.entries.len() as u32;
        let spellings =
            std::iter::once(entry.lemma.as_str()).chain(forms.iter().map(|f| f.as_ref()));
        for spelling in spellings {
            if self
                .keys
                .insert(spelling.to_string(), which as u64)
                .is_some()
            {
                return Err(WriteError::DuplicateKey(spelling.to_string()));
            }
        }
        for (number, sense) in entry.senses.iter().enumerate() {
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
            // Level three: the pack is downloaded once and read for as long as the language is
            // installed, and the levels above it cost build minutes for single-figure percents.
            let block = zstd::encode_all(plain.as_slice(), 3)
                .map_err(|e| WriteError::Compress(e.to_string()))?;
            blocks.push((packed.len() as u64, block.len() as u64));
            packed.extend_from_slice(&block);
        }

        let keys = build_index(self.keys.iter().map(|(k, v)| (k.as_str(), *v)))?;

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
}

fn write_strings(out: &mut Vec<u8>, items: &[String]) {
    varint::put(out, items.len() as u64);
    for item in items {
        varint::put_str(out, item);
    }
}
