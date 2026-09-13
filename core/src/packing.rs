//! Building a pack where the product runs.
//!
//! The dictionaries a reader gets are a word and how it is said, sixty of them, as the
//! compressed JSON maps the extension has always shipped. The cascade reads packs, so one of
//! the two has to change shape, and doing it on the device is what lets a reader have every
//! language without fetching anything: the maps travel as a fraction of the size a pack is,
//! and a language becomes a pack the first time it is read.
//!
//! The blocks such a pack stores are not compressed - the reference compressor is C and does
//! not build for WebAssembly - so what is written here is bigger on disk than what CI writes.
//! It is the same format, read by the same reader, and it is a disk rather than a download.

use lexpack::{Builder, Entry, Kind};

/// Turn one language's `{word: how it is said}` map into a pack.
///
/// The bytes are the file as it ships, gzipped. Nothing is inferred and nothing is dropped
/// except a row with no word or no pronunciation, which is a row that could answer nothing.
pub fn ipa_pack(lang: &str, gzipped: &[u8], built: u64) -> Option<Vec<u8>> {
    let words = read_map(gzipped)?;
    let mut pack = Builder::new(lang, Kind::Ipa, built);
    let mut taken = 0usize;
    for (word, ipa) in words {
        if word.is_empty() || ipa.is_empty() {
            continue;
        }
        let entry = Entry {
            lemma: word,
            pos: String::new(),
            ipa: vec![ipa],
            tags: Vec::new(),
            senses: Vec::new(),
            // A pronunciation pack is a word and how it is said; inflections are the
            // lexicon's business and this map holds none.
            forms: Vec::new(),
        };
        if pack.add::<&str>(entry, &[]).is_ok() {
            taken += 1;
        }
    }
    if taken == 0 {
        return None;
    }
    pack.finish().ok()
}

/// The map itself: gunzipped where it is gzipped, then read as pairs in the order the file
/// has them.
///
/// Either shape, because the two platforms hand it over differently: a browser reads the file
/// as it ships, and an Android build unpacks a `.gz` asset while packaging it, so what the app
/// opens is the JSON itself.
///
/// Written by hand rather than with a JSON library so that the core carries no parser it does
/// not otherwise need: the file is a flat object of string to string, which is four states and
/// an escape.
fn read_map(bytes: &[u8]) -> Option<Vec<(String, String)>> {
    let plain = if bytes.starts_with(&[0x1f, 0x8b]) {
        gunzip(bytes)?
    } else {
        bytes.to_vec()
    };
    let text = String::from_utf8(plain).ok()?;
    let mut out: Vec<(String, String)> = Vec::new();
    let mut chars = text.char_indices().peekable();
    let mut key: Option<String> = None;
    while let Some((_, c)) = chars.next() {
        match c {
            '"' => {
                let said = string_from(&mut chars)?;
                match key.take() {
                    // A string on its own is the key until the colon says the next one is the
                    // value; a map of anything but strings is not this file.
                    None => key = Some(said),
                    Some(was) => out.push((was, said)),
                }
            }
            ':' | ',' | '{' | '}' => {}
            c if c.is_whitespace() => {}
            // Anything else - a number, a nested object - is a file this was not written for.
            _ => return None,
        }
    }
    Some(out)
}

/// One JSON string, the opening quote already taken.
fn string_from(chars: &mut std::iter::Peekable<std::str::CharIndices>) -> Option<String> {
    let mut out = String::new();
    while let Some((_, c)) = chars.next() {
        match c {
            '"' => return Some(out),
            '\\' => {
                let (_, escaped) = chars.next()?;
                match escaped {
                    'n' => out.push('\n'),
                    't' => out.push('\t'),
                    'r' => out.push('\r'),
                    'b' => out.push('\u{8}'),
                    'f' => out.push('\u{c}'),
                    'u' => {
                        let mut code = 0u32;
                        for _ in 0..4 {
                            let (_, digit) = chars.next()?;
                            code = code * 16 + digit.to_digit(16)?;
                        }
                        // A surrogate pair, which is how a JSON file writes anything above the
                        // basic plane.
                        let ch = if (0xD800..0xDC00).contains(&code) {
                            let (_, slash) = chars.next()?;
                            let (_, u) = chars.next()?;
                            if slash != '\\' || u != 'u' {
                                return None;
                            }
                            let mut low = 0u32;
                            for _ in 0..4 {
                                let (_, digit) = chars.next()?;
                                low = low * 16 + digit.to_digit(16)?;
                            }
                            char::from_u32(0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00))?
                        } else {
                            char::from_u32(code)?
                        };
                        out.push(ch);
                    }
                    other => out.push(other),
                }
            }
            other => out.push(other),
        }
    }
    None
}

/// Gunzip, through the decompressor the reader already carries.
fn gunzip(bytes: &[u8]) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut out = Vec::new();
    flate2::read::GzDecoder::new(bytes)
        .read_to_end(&mut out)
        .ok()?;
    Some(out)
}
