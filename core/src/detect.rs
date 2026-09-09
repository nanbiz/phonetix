//! What language a piece of text is in, from the shape of its characters.
//!
//! A port of eld (Nito T.M., Apache-2.0). Text is cut at everything that is not a letter, each
//! word is walked in four-byte steps of three, and each run votes for the languages that use
//! it, weighted by how few of them do: a run only one language uses says far more than one
//! they all share.
//!
//! The alternative was the commonest words of each language, which answers a page of sentences
//! and little else. A screen of labels holds no function words at all, and "Video" is among
//! Italian's commonest words and not among English's. This decides "Speichern" on its own.
//!
//! The model is a file the host hands over, the same file on both platforms, because a
//! detector that disagreed with itself would put German pronunciations on an English page in
//! one place and not the other.

/// What the text was found to be.
#[derive(Clone, Debug, PartialEq)]
pub struct Guess {
    /// The language, or nothing when the text said too little.
    pub language: Option<String>,
    /// Whether the finding is worth acting on. Short text - a tab, a button, a name - is what
    /// this rules out, and a caller answers for those some other way.
    pub reliable: bool,
    /// What every language scored, for a caller weighing this against what else it knows.
    pub scores: Vec<(String, f32)>,
}

impl Guess {
    fn nothing() -> Guess {
        Guess {
            language: None,
            reliable: false,
            scores: Vec::new(),
        }
    }
}

/// eld multiplies an ngram's frequency in the text by this before comparing it with the
/// frequency in the model, which was built at fifteen thousand.
const FREQUENCY: f32 = 13200.0;
/// What an ngram only one language uses is worth, against the one to eight of a shared one.
const ALONE: f32 = 27.0;
/// Brings the total into roughly nought to one.
const DIVISOR: f32 = 3.2;
/// How many ngrams a text needs before its answer means anything, and how much of a language's
/// usual per-ngram score the winner has to reach.
const ENOUGH_NGRAMS: usize = 3;
const RELIABLE_SHARE: f32 = 0.24;
/// How much of a text, and of any one word, eld reads.
const MAX_TEXT: usize = 1000;
const MAX_WORD: usize = 70;

/// The ngram table, as the model file holds it.
///
/// Keys sorted and searched rather than hashed: fifty thousand ngrams cost two arrays and no
/// allocation per lookup.
pub struct Model {
    codes: Vec<String>,
    /// What a language's ngrams score on average, which a result is held against.
    averages: Vec<f32>,
    byte_to_index: [u8; 256],
    keys: Vec<u64>,
    starts: Vec<u32>,
    langs: Vec<u8>,
    scores: Vec<u16>,
}

/// What is wrong with a file that is not a model.
#[derive(Debug, PartialEq, Eq)]
pub enum ModelError {
    NotAModel,
    Truncated,
    Inconsistent,
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, many: usize) -> Result<&'a [u8], ModelError> {
        let end = self.at.checked_add(many).ok_or(ModelError::Truncated)?;
        let slice = self.bytes.get(self.at..end).ok_or(ModelError::Truncated)?;
        self.at = end;
        Ok(slice)
    }

    fn u16(&mut self) -> Result<u16, ModelError> {
        let bytes = self.take(2)?;
        Ok(u16::from_le_bytes([bytes[0], bytes[1]]))
    }

    fn u32(&mut self) -> Result<u32, ModelError> {
        let bytes = self.take(4)?;
        Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn u64(&mut self) -> Result<u64, ModelError> {
        let bytes = self.take(8)?;
        let mut value = [0u8; 8];
        value.copy_from_slice(bytes);
        Ok(u64::from_le_bytes(value))
    }
}

impl Model {
    /// Read a model. The bytes are the file as it ships, uncompressed.
    pub fn open(bytes: &[u8]) -> Result<Model, ModelError> {
        let mut read = Reader { bytes, at: 0 };
        if read.take(6)? != b"PXELD2" {
            return Err(ModelError::NotAModel);
        }
        let languages = read.u16()? as usize;
        let names = read.u16()? as usize;
        let names = read.take(names)?;
        let codes: Vec<String> = core::str::from_utf8(names)
            .map_err(|_| ModelError::NotAModel)?
            .split(',')
            .map(|code| code.to_string())
            .collect();
        if codes.len() != languages {
            return Err(ModelError::Inconsistent);
        }
        let mut averages = Vec::with_capacity(languages);
        for _ in 0..languages {
            averages.push(f32::from_bits(read.u32()?));
        }
        let mut byte_to_index = [0u8; 256];
        byte_to_index.copy_from_slice(read.take(256)?);

        let ngrams = read.u32()? as usize;
        let pairs = read.u32()? as usize;
        let alphabet = read.u16()? as usize;
        // Read and dropped: what a lookup needs is the index a byte maps to, and the table
        // above says that outright.
        let alphabet = read.take(alphabet)?;
        if alphabet.is_empty() {
            return Err(ModelError::Inconsistent);
        }

        let mut keys = Vec::with_capacity(ngrams);
        for _ in 0..ngrams {
            keys.push(read.u64()?);
        }
        let mut starts = Vec::with_capacity(ngrams + 1);
        for _ in 0..=ngrams {
            starts.push(read.u32()?);
        }
        let langs = read.take(pairs)?.to_vec();
        let mut scores = Vec::with_capacity(pairs);
        for _ in 0..pairs {
            scores.push(read.u16()?);
        }
        Ok(Model {
            codes,
            averages,
            byte_to_index,
            keys,
            starts,
            langs,
            scores,
        })
    }

    /// Which languages the model knows.
    pub fn languages(&self) -> &[String] {
        &self.codes
    }

    fn index(&self, byte: u8) -> u64 {
        self.byte_to_index[byte as usize] as u64
    }

    fn space(&self) -> u64 {
        self.index(b' ')
    }

    fn find(&self, key: u64) -> Option<usize> {
        self.keys.binary_search(&key).ok()
    }

    /// What language this text is in.
    pub fn detect(&self, text: &str) -> Guess {
        if text.is_empty() {
            return Guess::nothing();
        }
        let mut counts: Vec<(u64, u32)> = Vec::new();
        let mut total = 0u32;
        for word in words(text) {
            total += self.ngrams(&word, &mut counts);
        }
        if total == 0 {
            return Guess::nothing();
        }
        let mut totals = vec![0f32; self.codes.len()];
        for (key, count) in &counts {
            let Some(at) = self.find(*key) else { continue };
            let frequency = *count as f32 / total as f32 * FREQUENCY;
            let from = self.starts[at] as usize;
            let until = self.starts[at + 1] as usize;
            let spoken = until - from;
            let relevancy = if spoken == 1 {
                ALONE
            } else if spoken < 16 {
                (16 - spoken) as f32 / 2.0 + 1.0
            } else {
                1.0
            };
            for i in from..until {
                let world = self.scores[i] as f32;
                let share = if frequency > world {
                    world / frequency
                } else {
                    frequency / world
                };
                totals[self.langs[i] as usize] += share * relevancy + 2.0;
            }
        }
        let divisor = counts.len() as f32 * DIVISOR;
        let mut best: Option<usize> = None;
        let mut best_score = 0f32;
        let mut scores = Vec::new();
        for (at, total) in totals.iter().enumerate() {
            if *total <= 0.0 {
                continue;
            }
            let score = total / divisor;
            scores.push((self.codes[at].clone(), score));
            if score > best_score {
                best_score = score;
                best = Some(at);
            }
        }
        let Some(best) = best else {
            return Guess::nothing();
        };
        // eld's own rule: enough ngrams to be worth judging, and a winner scoring at least a
        // quarter of what that language usually scores per ngram.
        let reliable = counts.len() >= ENOUGH_NGRAMS
            && self.averages[best] * RELIABLE_SHARE <= best_score / counts.len() as f32;
        Guess {
            language: Some(self.codes[best].clone()),
            reliable,
            scores,
        }
    }

    /// The ngrams of one word, as eld cuts them: four bytes at a time stepping three, the
    /// first with a space before it and the last with a space after, so that where a word
    /// begins and ends counts for as much as its middle.
    fn ngrams(&self, word: &[u8], counts: &mut Vec<(u64, u32)>) -> u32 {
        let length = word.len().min(MAX_WORD);
        let mut made = 0;
        let mut at = 0;
        let add = |key: u64, counts: &mut Vec<(u64, u32)>| match counts
            .iter_mut()
            .find(|(had, _)| *had == key)
        {
            Some((_, count)) => *count += 1,
            None => counts.push((key, 1)),
        };
        while at + 4 < length {
            let mut key = if at == 0 { self.space() } else { 0 };
            for byte in &word[at..at + 4] {
                key = (key << 8) | self.index(*byte);
            }
            add(key, counts);
            made += 1;
            at += 3;
        }
        let mut key = if at == 0 { self.space() } else { 0 };
        let from = if length != 3 {
            length.saturating_sub(4)
        } else {
            0
        };
        for byte in &word[from..length] {
            key = (key << 8) | self.index(*byte);
        }
        key = (key << 8) | self.space();
        add(key, counts);
        made + 1
    }
}

/// What a screenful of text is in, and whether it said enough to be worth asking.
#[derive(Clone, Debug, PartialEq)]
pub struct Screen {
    /// The language, or nothing when the text was too short or the detector was unsure.
    pub language: Option<String>,
    /// How many words it had to go on.
    pub words: usize,
    /// Whether there was enough text to judge at all. Below this a caller keeps doing
    /// whatever it did before, rather than acting on a guess made from three labels.
    pub enough: bool,
}

/// Below this there is not enough on a screen to judge it by.
const ENOUGH_WORDS: usize = 8;
/// Shorter than this is not a word for this purpose, matching what gets annotated.
const MIN_WORD: usize = 2;

/// What a screenful of text is in.
///
/// One rule for both platforms, thresholds included: a phone that needed eight words and a
/// browser that needed three would annotate the same screen differently, which is exactly the
/// drift that putting the detector here was for.
pub fn read_screen(model: Option<&Model>, text: &str) -> Screen {
    let words = words(text)
        .iter()
        .filter(|word| word.len() >= MIN_WORD)
        .count();
    if words < ENOUGH_WORDS {
        return Screen {
            language: None,
            words,
            enough: false,
        };
    }
    let Some(model) = model else {
        return Screen {
            language: None,
            words,
            enough: true,
        };
    };
    let guess = model.detect(text);
    Screen {
        // Only a finding the detector itself calls reliable: it says when a text told it too
        // little, and a caller acting on the rest would be acting on noise.
        language: if guess.reliable { guess.language } else { None },
        words,
        enough: true,
    }
}

/// The words of a text, lowercased, cut at everything that is not a letter.
fn words(text: &str) -> Vec<Vec<u8>> {
    let mut out = Vec::new();
    let mut word = String::new();
    let mut seen = 0;
    for c in text.chars() {
        // eld counts what it has read in UTF-16 units, because that is what the runtime it
        // was written for counts in, and reading a different amount of a long text would
        // score it differently.
        seen += c.len_utf16();
        if seen > MAX_TEXT {
            break;
        }
        if c.is_alphabetic() || c == '\'' || c == '\u{2019}' {
            for lower in c.to_lowercase() {
                word.push(lower);
            }
        } else if !word.is_empty() {
            out.push(word.as_bytes().to_vec());
            word.clear();
        }
    }
    if !word.is_empty() {
        out.push(word.into_bytes());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_file_that_is_not_a_model_is_refused() {
        assert!(matches!(
            Model::open(b"nothing"),
            Err(ModelError::NotAModel)
        ));
        assert!(matches!(Model::open(b"PXELD2"), Err(ModelError::Truncated)));
    }

    #[test]
    fn text_is_cut_into_the_words_eld_reads() {
        assert_eq!(
            words("Der Lesesaal, 22 Uhr!"),
            [
                b"der".to_vec(),
                "lesesaal".as_bytes().to_vec(),
                b"uhr".to_vec()
            ]
        );
    }

    #[test]
    fn a_word_keeps_its_apostrophe() {
        assert_eq!(words("don't"), [b"don't".to_vec()]);
    }
}
