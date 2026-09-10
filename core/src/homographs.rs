//! Which word a spelling is, decided by what surrounds it.
//!
//! A homograph is a spelling that is several words. The neighbour's part of speech decides
//! some of them in any language (see [`crate::neighbours`]); a trained decision list decides
//! far more, in the ten languages one was trained for. These were trained for PolyPhoneme,
//! carried into the extension, and lost in the merge.
//!
//! A decision list is read best rule first and the first that matches wins - Yarowsky's
//! method, which is a list of "if the word before is X, it is this one" ordered by how much
//! each rule was worth on the training data. Where no rule matches, this says so rather than
//! falling back to the commonest reading: the caller has other signals and a reader to ask.
//!
//! The table is a file the host hands over, like the language model, because it is data rather
//! than code and a copy compiled into two binaries is two copies to keep in step.

use lexpack::varint;

/// One reading of a spelling: what it is, and how it is said.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Reading {
    /// What the training called this class, which is what its rules name.
    pub id: String,
    /// The part of speech, where the training recorded one.
    pub label: String,
    pub pronunciation: String,
    /// Short keywords, kept only for the one thing they answer: a CJK compound whose
    /// neighbouring character belongs to one reading and not the other.
    pub keywords: Vec<String>,
}

/// One rule: what to look for, which reading it names, and what it was worth.
#[derive(Clone, Debug)]
struct Rule {
    score: u32,
    feature: String,
    reading: usize,
}

/// Everything known about one spelling.
#[derive(Clone, Debug)]
pub struct Word {
    pub readings: Vec<Reading>,
    rules: Vec<Rule>,
}

/// One language's classifier.
#[derive(Clone, Debug, Default)]
pub struct Classifier {
    words: std::collections::HashMap<String, Word>,
}

/// What went wrong reading one.
#[derive(Debug, PartialEq, Eq)]
pub enum Bad {
    NotAClassifier,
    WrongVersion(u8),
    Truncated,
}

const MAGIC: &[u8; 4] = b"PXHG";
const VERSION: u8 = 1;

impl Classifier {
    /// Read a classifier built by tools/build_homographs.py.
    pub fn open(bytes: &[u8]) -> Result<Classifier, Bad> {
        if bytes.len() < 5 || &bytes[0..4] != MAGIC {
            return Err(Bad::NotAClassifier);
        }
        if bytes[4] != VERSION {
            return Err(Bad::WrongVersion(bytes[4]));
        }
        let mut at = 5usize;
        let count = varint::get(bytes, &mut at).ok_or(Bad::Truncated)?;
        let mut words = std::collections::HashMap::with_capacity(count as usize);
        for _ in 0..count {
            let spelling = varint::get_str(bytes, &mut at).ok_or(Bad::Truncated)?;
            let readings_len = varint::get(bytes, &mut at).ok_or(Bad::Truncated)?;
            // The default is written and read, so the format says which reading it is rather
            // than every caller agreeing it is the first.
            let _default = varint::get(bytes, &mut at).ok_or(Bad::Truncated)?;
            let mut readings = Vec::with_capacity(readings_len as usize);
            for _ in 0..readings_len {
                let id = varint::get_str(bytes, &mut at).ok_or(Bad::Truncated)?;
                let label = varint::get_str(bytes, &mut at).ok_or(Bad::Truncated)?;
                let pronunciation = varint::get_str(bytes, &mut at).ok_or(Bad::Truncated)?;
                let keywords_len = varint::get(bytes, &mut at).ok_or(Bad::Truncated)?;
                let mut keywords = Vec::with_capacity(keywords_len as usize);
                for _ in 0..keywords_len {
                    keywords.push(varint::get_str(bytes, &mut at).ok_or(Bad::Truncated)?);
                }
                readings.push(Reading {
                    id,
                    label,
                    pronunciation,
                    keywords,
                });
            }
            let rules_len = varint::get(bytes, &mut at).ok_or(Bad::Truncated)?;
            let mut rules = Vec::with_capacity(rules_len as usize);
            for _ in 0..rules_len {
                let score = varint::get(bytes, &mut at).ok_or(Bad::Truncated)? as u32;
                let feature = varint::get_str(bytes, &mut at).ok_or(Bad::Truncated)?;
                let reading = varint::get(bytes, &mut at).ok_or(Bad::Truncated)? as usize;
                rules.push(Rule {
                    score,
                    feature,
                    reading,
                });
            }
            words.insert(spelling.to_lowercase(), Word { readings, rules });
        }
        Ok(Classifier { words })
    }

    /// How many spellings it knows, which is what a host reports after loading one.
    pub fn len(&self) -> usize {
        self.words.len()
    }

    pub fn is_empty(&self) -> bool {
        self.words.is_empty()
    }

    /// Which reading this spelling is, given the words around it.
    ///
    /// Nothing where the classifier does not know the word, or knows it and no rule matched:
    /// a decision list that has run out is a classifier saying it does not know, which is not
    /// the same as it choosing the commonest reading. The caller has other signals.
    pub fn read<'a>(&'a self, spelling: &str, around: &[&str], at: usize) -> Option<&'a Reading> {
        let word = self.words.get(&spelling.to_lowercase())?;
        if word.readings.len() < 2 {
            return None;
        }
        let before = at
            .checked_sub(1)
            .and_then(|i| around.get(i))
            .map(|w| w.to_lowercase())
            .unwrap_or_default();
        let after = around
            .get(at + 1)
            .map(|w| w.to_lowercase())
            .unwrap_or_default();

        // A CJK compound: a character beside it that belongs to exactly one reading says which
        // word this is, and the rules trained on space-separated text cannot see it.
        let letters = spelling.chars().count();
        if letters <= 2 && spelling.chars().any(|c| (c as u32) > 0x2E80) {
            let beside: Vec<&str> = [before.as_str(), after.as_str()]
                .into_iter()
                .filter(|it| !it.is_empty())
                .collect();
            let mut touched = word.readings.iter().filter(|reading| {
                reading.keywords.iter().any(|keyword| {
                    beside
                        .iter()
                        .any(|next| next.contains(keyword.as_str()) || keyword.contains(*next))
                })
            });
            if let Some(only) = touched.next() {
                if touched.next().is_none() {
                    return Some(only);
                }
            }
        }

        // The decision list, best rule first: the first that matches decides.
        let mut window: Vec<String> = Vec::new();
        let from = at.saturating_sub(5);
        for (i, word) in around.iter().enumerate().skip(from).take(11) {
            if i != at {
                window.push(word.to_lowercase());
            }
        }
        let mut rules: Vec<&Rule> = word.rules.iter().collect();
        rules.sort_by_key(|rule| std::cmp::Reverse(rule.score));
        for rule in rules {
            let matched = if let Some(want) = rule.feature.strip_prefix("-1:") {
                before == want
            } else if let Some(want) = rule.feature.strip_prefix("+1:") {
                after == want
            } else if let Some(want) = rule.feature.strip_prefix("bi:") {
                match want.split_once('_') {
                    Some((one, other)) => before == one && after == other,
                    None => false,
                }
            } else if let Some(want) = rule.feature.strip_prefix("w:") {
                window.iter().any(|seen| seen == want)
            } else {
                false
            };
            if matched {
                return word.readings.get(rule.reading);
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One word with two readings and one rule, in the shape the tool writes.
    fn a_classifier() -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        out.push(VERSION);
        put(&mut out, 1); // one word
        text(&mut out, "record");
        put(&mut out, 2); // two readings
        put(&mut out, 0); // the first is the default
        text(&mut out, "record_nou");
        text(&mut out, "noun");
        text(&mut out, "ˈɹɛkɚd");
        put(&mut out, 0);
        text(&mut out, "record_vrb");
        text(&mut out, "verb");
        text(&mut out, "ɹəˈkɔːɹd");
        put(&mut out, 0);
        put(&mut out, 2); // two rules
        put(&mut out, 900);
        text(&mut out, "-1:the");
        put(&mut out, 0);
        put(&mut out, 800);
        text(&mut out, "-1:to");
        put(&mut out, 1);
        out
    }

    fn put(out: &mut Vec<u8>, value: u64) {
        lexpack::varint::put(out, value);
    }

    fn text(out: &mut Vec<u8>, value: &str) {
        lexpack::varint::put_str(out, value);
    }

    #[test]
    fn a_rule_that_matches_decides_which_word_it_is() {
        let it = Classifier::open(&a_classifier()).expect("opens");
        assert_eq!(it.len(), 1);
        let noun = it.read("record", &["the", "record", "shows"], 1).unwrap();
        assert_eq!(noun.label, "noun");
        let verb = it.read("record", &["to", "record", "it"], 1).unwrap();
        assert_eq!(verb.label, "verb");
    }

    #[test]
    fn a_word_no_rule_reaches_is_left_to_the_reader() {
        let it = Classifier::open(&a_classifier()).expect("opens");
        // Known, but nothing around it matched: a decision list that has run out says so.
        assert!(it.read("record", &["a", "record", "of"], 1).is_none());
        // And a word it never saw is not its business.
        assert!(it.read("bass", &["the", "bass", "player"], 1).is_none());
    }

    #[test]
    fn something_that_is_not_a_classifier_is_refused() {
        assert_eq!(Classifier::open(b"nope").unwrap_err(), Bad::NotAClassifier);
        let mut wrong = a_classifier();
        wrong[4] = 9;
        assert_eq!(Classifier::open(&wrong).unwrap_err(), Bad::WrongVersion(9));
    }
}
