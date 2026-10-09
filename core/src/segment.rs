//! Where the words are in a run of text.
//!
//! Both hosts hand over runs of text and neither is allowed to decide what a word is: a
//! browser would ask Intl.Segmenter, a phone would ask its own tokenizer, and the two would
//! annotate different things on the same sentence. So the boundaries are found here, by the
//! Unicode text segmentation rules, and the offsets that come back are UTF-16 because that is
//! how both hosts index the strings they gave.
//!
//! What this does not do is split a script that writes without spaces and needs a dictionary
//! to be read: Chinese, Japanese, Thai, Khmer, Lao and Burmese. The Unicode rules leave those
//! runs whole, and a run left whole is reported as one word rather than as a wrong guess at
//! several. Those languages need a segmenter with a lexicon behind it, which is a pack this
//! does not have yet.

use unicode_segmentation::UnicodeSegmentation;

/// A word found in a run, with where it sits in the host's own indexing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Word {
    /// The word as it is written, which is what a lookup is done with.
    pub text: String,
    /// UTF-16 offsets into the run, so a host can find it again without recounting.
    pub start: u32,
    pub end: u32,
}

/// Whether a run is in a script this can find words in.
///
/// A run that is not gets no inline annotation, because dividing it would be a guess about
/// where the words are, and a wrong division annotates a word nobody wrote.
pub fn separable(text: &str) -> bool {
    !text.chars().any(needs_a_lexicon)
}

/// Scripts that write without spaces between words.
fn needs_a_lexicon(c: char) -> bool {
    matches!(c as u32,
        0x3400..=0x4DBF      // CJK extension A
        | 0x4E00..=0x9FFF    // CJK unified ideographs
        | 0xF900..=0xFAFF    // CJK compatibility ideographs
        | 0x3040..=0x30FF    // Hiragana and Katakana
        | 0x0E00..=0x0E7F    // Thai
        | 0x0E80..=0x0EFF    // Lao
        | 0x1000..=0x109F    // Burmese
        | 0x1780..=0x17FF    // Khmer
    )
}

/// Whether a segment is a word rather than punctuation or space.
///
/// A word has a letter in it and nothing that makes it a name for a machine. Numbers, dashes
/// and quotation marks are not words a reader needs a transcription of, and neither is what a
/// page writes for a computer to read: a version, a user name, a file, a site or a name out of
/// code ("v31.55", "justinking3062", "MXXX.sqlite", "example.com", "v_dev", "useState"), which
/// said letter by letter and number by number is said the way nobody says it.
fn is_a_word(text: &str) -> bool {
    text.chars().any(char::is_alphabetic) && !for_a_machine(text)
}

/// The run of text between spaces a segment is part of, without the punctuation around it.
///
/// What a name for a machine is made of is not always one segment: the rules split
/// "user@example.com" at the at sign and "src/ext/index.ts" at each slash, and a browser's own
/// segmenter splits "MXXX.sqlite" at the dot where these rules do not. Judged by the whole
/// run, each of its pieces is what the run is.
pub fn chunk(text: &str, start: usize, end: usize) -> &str {
    let from = text[..start]
        .char_indices()
        .rev()
        .find(|(_, c)| c.is_whitespace())
        .map_or(0, |(at, c)| at + c.len_utf8());
    let to = text[end..]
        .char_indices()
        .find(|(_, c)| c.is_whitespace())
        .map_or(text.len(), |(at, _)| end + at);
    text[from..to].trim_matches(|c: char| {
        matches!(
            c,
            '"' | '\''
                | '('
                | ')'
                | '['
                | ']'
                | '{'
                | '}'
                | '<'
                | '>'
                | '«'
                | '»'
                | '“'
                | '”'
                | '‘'
                | '’'
                | '„'
                | ','
                | ';'
                | '!'
                | '?'
                | '¿'
                | '¡'
                | '*'
        )
    })
}

/// Whether a run of text is a name written for a computer rather than a word: a digit or an
/// underscore in it; an at sign, or a slash beside a full stop or another slash (an address, a
/// path - "and/or" is still two words); a full stop or colon inside it that is not an
/// abbreviation's ("e.g.", "U.S." keep theirs, being single letters); or a capital after a
/// small letter at the start ("useState", "getElementById"), which no language writes a word
/// with.
pub fn for_a_machine(text: &str) -> bool {
    if text.chars().any(|c| c.is_numeric() || c == '_' || c == '@') {
        return true;
    }
    let slashes = text.matches(['/', '\\']).count();
    if slashes > 1 || (slashes == 1 && text.trim_end_matches(['.', ':']).contains('.')) {
        return true;
    }
    let inside = text.trim_end_matches(['.', ':']);
    if inside.contains(['.', ':']) {
        let abbreviation = inside
            .split(['.', ':'])
            .all(|part| part.chars().count() == 1);
        if !abbreviation {
            return true;
        }
    }
    let mut chars = text.chars();
    chars.next().is_some_and(char::is_lowercase) && chars.any(char::is_uppercase)
}

/// The words of a run, in order.
pub fn words(text: &str) -> Vec<Word> {
    let mut out = Vec::new();
    if !separable(text) {
        return out;
    }
    // The offsets have to be UTF-16, and the segmenter counts bytes, so the two are walked
    // together rather than converted afterwards: converting means re-encoding the whole run
    // once per word.
    let mut units: u32 = 0;
    let mut last_byte: usize = 0;
    for (at, piece) in text.split_word_bound_indices() {
        units += text[last_byte..at].encode_utf16().count() as u32;
        last_byte = at;
        let length = piece.encode_utf16().count() as u32;
        if is_a_word(piece) && !for_a_machine(chunk(text, at, at + piece.len())) {
            out.push(Word {
                text: piece.to_string(),
                start: units,
                end: units + length,
            });
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spellings(text: &str) -> Vec<String> {
        words(text).into_iter().map(|w| w.text).collect()
    }

    #[test]
    fn a_sentence_is_its_words() {
        assert_eq!(
            spellings("El perro corre por el camino."),
            ["El", "perro", "corre", "por", "el", "camino"]
        );
    }

    #[test]
    fn punctuation_and_numbers_are_not_words() {
        assert_eq!(spellings("a, b. 42 -- c!"), ["a", "b", "c"]);
        assert_eq!(spellings("v31.55 of mp3 files, 4K"), ["of", "files"]);
        assert_eq!(spellings("by justinking3062 on v_dev"), ["by", "on"]);
        assert_eq!(
            spellings("open MXXX.sqlite at example.com, see useState and getElementById"),
            ["open", "at", "see", "and"]
        );
        assert_eq!(
            spellings("e.g. the U.S. at 9 a.m."),
            ["e.g", "the", "U.S", "at", "a.m"]
        );
        assert_eq!(spellings("iPhone and McDonald"), ["and", "McDonald"]);
        assert_eq!(
            spellings(
                "mail me@example.org, read src/ext/index.ts or (https://x.org/a) and/or not."
            ),
            ["mail", "read", "or", "and", "or", "not"]
        );
        assert_eq!(spellings("\"Done.\" It ended."), ["Done", "It", "ended"]);
    }

    #[test]
    fn a_word_keeps_the_marks_that_are_part_of_it() {
        assert_eq!(spellings("murciélago Straße"), ["murciélago", "Straße"]);
        assert_eq!(spellings("don't"), ["don't"]);
    }

    #[test]
    fn offsets_are_where_the_host_will_look() {
        let found = words("El perro");
        assert_eq!(found[1].start, 3);
        assert_eq!(found[1].end, 8);
    }

    #[test]
    fn offsets_count_the_way_a_host_counts() {
        // An emoji is two UTF-16 units and four bytes; a host indexing the string sees two.
        let found = words("🙂 perro");
        assert_eq!(found[0].text, "perro");
        assert_eq!(found[0].start, 3);
        assert_eq!(found[0].end, 8);
    }

    #[test]
    fn a_script_that_needs_a_lexicon_is_left_alone() {
        assert!(!separable("日本語のテキスト"));
        assert!(words("日本語のテキスト").is_empty());
        assert!(separable("El perro"));
    }
}
