//! Reading a pack.
//!
//! The bytes are borrowed, not copied: a browser hands over a buffer and a phone maps a file,
//! and neither pays for the pack twice. What a lookup costs is one walk of the key index and
//! one block decompressed, and the block is kept in case the next word is its neighbour, which
//! on a page of text it usually is.

use std::cell::RefCell;

use crate::{varint, Header, Kind, Section, FORMAT, MAGIC, SECTIONS};

/// One sense of a word: what it means, how it is marked, and an example if the dump had one.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct Sense {
    /// In English, whatever the language of the pack. This is what joins two packs into a pair.
    pub gloss: String,
    pub marks: Vec<String>,
    pub example: Option<String>,
}

/// One word: what it is, how it is said, and what it means.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct Entry {
    pub lemma: String,
    pub pos: String,
    pub tags: Vec<String>,
    pub ipa: Vec<String>,
    pub senses: Vec<Sense>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum PackError {
    /// Not a pack at all.
    NotAPack,
    /// A pack, but of a format this reader was not written against.
    WrongFormat(u32),
    /// A pack whose own table of contents does not fit inside it.
    Truncated,
    /// A section that will not parse as what the header says it is.
    Corrupt(&'static str),
}

/// An open pack.
///
/// Generic over what holds the bytes, so the same reader serves a host that hands over a
/// buffer it keeps and one that hands over a buffer it does not. The two indexes are copied
/// out of it, because a transducer has to own a contiguous run and the alternative is a
/// structure that points into itself.
pub struct Pack<D: AsRef<[u8]>> {
    bytes: D,
    header: Header,
    keys: fst::Map<Vec<u8>>,
    glosses: fst::Map<Vec<u8>>,
    /// Where each block sits in the file, and where each entry sits inside its block.
    blocks: Vec<(u64, u64)>,
    starts: Vec<u32>,
    /// The block last decompressed, kept because the next word asked about is usually a
    /// neighbour of the last one.
    warm: RefCell<Option<(usize, Vec<u8>)>>,
}

impl<D: AsRef<[u8]>> Pack<D> {
    pub fn open(held: D) -> Result<Pack<D>, PackError> {
        let bytes = held.as_ref();
        if bytes.len() < MAGIC.len() || &bytes[..MAGIC.len()] != MAGIC {
            return Err(PackError::NotAPack);
        }
        let mut at = MAGIC.len();
        let format = varint::get(bytes, &mut at).ok_or(PackError::Truncated)? as u32;
        if format != FORMAT {
            return Err(PackError::WrongFormat(format));
        }
        let lang = varint::get_str(bytes, &mut at).ok_or(PackError::Truncated)?;
        let kind = Kind::from_byte(*bytes.get(at).ok_or(PackError::Truncated)?)
            .ok_or(PackError::Corrupt("kind"))?;
        at += 1;
        let built = varint::get(bytes, &mut at).ok_or(PackError::Truncated)?;
        let entries = varint::get(bytes, &mut at).ok_or(PackError::Truncated)? as u32;
        let mut sections = [(0u64, 0u64); SECTIONS];
        for slot in sections.iter_mut() {
            let offset = varint::get(bytes, &mut at).ok_or(PackError::Truncated)?;
            let length = varint::get(bytes, &mut at).ok_or(PackError::Truncated)?;
            let end = offset.checked_add(length).ok_or(PackError::Truncated)?;
            if end > bytes.len() as u64 {
                return Err(PackError::Truncated);
            }
            *slot = (offset, length);
        }
        let header = Header { format, lang, kind, built, entries, sections };

        let keys = fst::Map::new(slice(bytes, header.at(Section::Keys)).to_vec())
            .map_err(|_| PackError::Corrupt("keys"))?;
        let glosses = fst::Map::new(slice(bytes, header.at(Section::Glosses)).to_vec())
            .map_err(|_| PackError::Corrupt("glosses"))?;

        // Where every block is, and where every entry starts inside its own block. Both are
        // read once here rather than walked per lookup: it is a few numbers per entry, and a
        // lookup that had to scan them would cost the whole index every time.
        let index = slice(bytes, header.at(Section::BlockIndex));
        let mut at = 0usize;
        let count = varint::get(index, &mut at).ok_or(PackError::Corrupt("blocks"))? as usize;
        let mut blocks = Vec::with_capacity(count);
        for _ in 0..count {
            let offset = varint::get(index, &mut at).ok_or(PackError::Corrupt("blocks"))?;
            let length = varint::get(index, &mut at).ok_or(PackError::Corrupt("blocks"))?;
            blocks.push((offset, length));
        }
        let mut starts = Vec::with_capacity(header.entries as usize);
        for _ in 0..header.entries {
            starts.push(varint::get(index, &mut at).ok_or(PackError::Corrupt("starts"))? as u32);
        }

        Ok(Pack { bytes: held, header, keys, glosses, blocks, starts, warm: RefCell::new(None) })
    }

    pub fn lang(&self) -> &str {
        &self.header.lang
    }

    pub fn kind(&self) -> Kind {
        self.header.kind
    }

    pub fn built(&self) -> u64 {
        self.header.built
    }

    pub fn len(&self) -> usize {
        self.header.entries as usize
    }

    pub fn is_empty(&self) -> bool {
        self.header.entries == 0
    }

    /// The entry a spelling names, whether it is the lemma or an inflected form of it.
    pub fn lookup(&self, spelling: &str) -> Option<Entry> {
        let which = self.keys.get(spelling)?;
        self.entry(which as u32)
    }

    /// One entry by number.
    pub fn entry(&self, which: u32) -> Option<Entry> {
        let start = *self.starts.get(which as usize)? as usize;
        let block = which as usize / crate::ENTRIES_PER_BLOCK;
        let bytes = self.block(block)?;
        let mut at = start;
        read_entry(&bytes, &mut at)
    }

    /// Which senses carry this English gloss term.
    ///
    /// The answer is a list of (entry, sense) rather than of words, because what a join needs
    /// is the sense: a lemma reached through the wrong sense of a shared gloss is exactly the
    /// confident wrong answer the format is shaped to avoid.
    pub fn senses_glossed(&self, term: &str) -> Vec<(u32, u32)> {
        let head = crate::gloss_head(term);
        let Some(at) = self.glosses.get(&head) else { return Vec::new() };
        let hits = slice(self.bytes.as_ref(), self.header.at(Section::GlossHits));
        let mut cursor = at as usize;
        let Some(count) = varint::get(hits, &mut cursor) else { return Vec::new() };
        let mut out = Vec::with_capacity(count as usize);
        for _ in 0..count {
            let Some(entry) = varint::get(hits, &mut cursor) else { break };
            let Some(sense) = varint::get(hits, &mut cursor) else { break };
            out.push((entry as u32, sense as u32));
        }
        out
    }

    /// Which senses this whole gloss reaches, best first, with how many of its terms each
    /// matched.
    ///
    /// The head term alone is not the answer where a gloss has more than one: "way" reaches
    /// both Weg and Weise, and "way, route" reaches Weg. Every term is looked up and the
    /// senses are scored by how many of them they share, which is the narrowing DR-2 gets for
    /// free from the data rather than from a measurement.
    ///
    /// Ties are left as ties. A gloss that reaches two lemmas equally well is the ambiguity the
    /// core refuses to guess through, and deciding it here would hide exactly the case that
    /// has to fall to the machine engine and be labelled a guess.
    pub fn senses_matching(&self, gloss: &str) -> Vec<((u32, u32), usize)> {
        let mut score: std::collections::HashMap<(u32, u32), usize> =
            std::collections::HashMap::new();
        for term in crate::gloss_terms(gloss) {
            for hit in self.senses_glossed(&term) {
                *score.entry(hit).or_insert(0) += 1;
            }
        }
        let mut out: Vec<((u32, u32), usize)> = score.into_iter().collect();
        // Best first, and stable among equals so a tie reads the same way twice.
        out.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
        out
    }

    /// Every spelling in the pack, in order. For tests and for the builder's own checks.
    pub fn spellings(&self) -> Vec<String> {
        use fst::Streamer;
        let mut out = Vec::new();
        let mut stream = self.keys.stream();
        while let Some((key, _)) = stream.next() {
            out.push(String::from_utf8_lossy(key).into_owned());
        }
        out
    }

    /// One block, decompressed, kept warm for the next word.
    fn block(&self, which: usize) -> Option<Vec<u8>> {
        if let Some((warm, bytes)) = self.warm.borrow().as_ref() {
            if *warm == which {
                return Some(bytes.clone());
            }
        }
        let (offset, length) = *self.blocks.get(which)?;
        let (base, _) = self.header.at(Section::Blocks);
        let from = (base + offset) as usize;
        let raw = self.bytes.as_ref().get(from..from + length as usize)?;
        let plain = unpack(raw)?;
        *self.warm.borrow_mut() = Some((which, plain.clone()));
        Some(plain)
    }
}

fn slice(bytes: &[u8], (offset, length): (u64, u64)) -> &[u8] {
    &bytes[offset as usize..(offset + length) as usize]
}

/// Decompress one block. Pure Rust, because this runs in a browser.
fn unpack(raw: &[u8]) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut decoder = ruzstd::StreamingDecoder::new(raw).ok()?;
    let mut out = Vec::new();
    decoder.read_to_end(&mut out).ok()?;
    Some(out)
}

fn read_entry(bytes: &[u8], at: &mut usize) -> Option<Entry> {
    let lemma = varint::get_str(bytes, at)?;
    let pos = varint::get_str(bytes, at)?;
    let tags = read_strings(bytes, at)?;
    let ipa = read_strings(bytes, at)?;
    let senses = varint::get(bytes, at)?;
    let mut out = Vec::with_capacity(senses as usize);
    for _ in 0..senses {
        let gloss = varint::get_str(bytes, at)?;
        let marks = read_strings(bytes, at)?;
        let has_example = varint::get(bytes, at)?;
        let example = if has_example == 1 { Some(varint::get_str(bytes, at)?) } else { None };
        out.push(Sense { gloss, marks, example });
    }
    Some(Entry { lemma, pos, tags, ipa, senses: out })
}

fn read_strings(bytes: &[u8], at: &mut usize) -> Option<Vec<String>> {
    let count = varint::get(bytes, at)?;
    let mut out = Vec::with_capacity(count as usize);
    for _ in 0..count {
        out.push(varint::get_str(bytes, at)?);
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_file_that_is_not_a_pack_is_refused() {
        assert!(matches!(Pack::open(b"not a pack at all"), Err(PackError::NotAPack)));
        assert!(matches!(Pack::open(b""), Err(PackError::NotAPack)));
    }

    #[test]
    fn a_pack_cut_short_is_refused_rather_than_read() {
        let mut bytes = MAGIC.to_vec();
        varint::put(&mut bytes, FORMAT as u64);
        assert!(matches!(Pack::open(&bytes), Err(PackError::Truncated)));
    }
}
