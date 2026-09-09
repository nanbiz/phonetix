//! What a word means, decided once.
//!
//! One value per word, produced here and used by the inline layer, the card, the lens and the
//! audio, so those four cannot disagree. The cascade is first hit wins: a spelling that is a
//! lemma answers as one, a spelling that is a form answers through its lemma, a language read
//! in itself answers with its own definitions, and everything that gets no further is handed
//! back as a miss for the host's engines with the state that says why.
//!
//! What is not decided here is anything a guess would decide. Where a gloss reaches two words
//! of the target equally well the answer is the ambiguity itself, because a wrong word wearing
//! a dictionary's authority is worse than an obvious guess, and the card has a state for it.

use lexpack::{Entry, Pack};

use crate::answer::{AnswerState, Lang, Provenance};

/// What the core knows about one word.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Answer {
    pub state: AnswerState,
    /// What the reader tapped, as it is written on the page.
    pub spelling: String,
    /// The dictionary form it belongs to, where that is a different word.
    pub lemma: Option<String>,
    pub pos: Option<String>,
    /// How it is said, as the pack records it.
    pub ipa: Vec<String>,
    /// The answer in the reader's own language, best first. More than one means the join was
    /// ambiguous and the card says so rather than choosing.
    pub says: Vec<String>,
    /// What the word means, in English, which is the anchor the card shows when the join is
    /// ambiguous or absent.
    pub glosses: Vec<String>,
    pub provenance: Option<Provenance>,
    pub source: Lang,
    pub target: Lang,
}

impl Answer {
    fn nothing(state: AnswerState, spelling: &str, source: &Lang, target: &Lang) -> Answer {
        Answer {
            state,
            spelling: spelling.to_string(),
            lemma: None,
            pos: None,
            ipa: Vec::new(),
            says: Vec::new(),
            glosses: Vec::new(),
            provenance: None,
            source: source.clone(),
            target: target.clone(),
        }
    }
}

/// The packs a lookup may use. Either may be missing, and which is missing decides the state.
pub struct Open<'a, D: AsRef<[u8]>> {
    /// The language being read.
    pub source: Option<&'a Pack<D>>,
    /// The reader's own language. The same pack as the source when a language is read in
    /// itself, and absent when the reader's language is English, which needs no join.
    pub target: Option<&'a Pack<D>>,
    /// Whether a pronunciation pack is open for the source, which is what separates "no
    /// dictionary yet" from "nothing at all".
    pub ipa_only: bool,
}

/// Look one word up.
pub fn look_up<D: AsRef<[u8]>>(
    spelling: &str,
    source: &Lang,
    target: &Lang,
    open: &Open<D>,
) -> Answer {
    let Some(pack) = open.source else {
        let state = if open.ipa_only {
            AnswerState::IpaOnly
        } else {
            AnswerState::NoPack
        };
        return Answer::nothing(state, spelling, source, target);
    };
    let Some(entry) = pack.lookup(spelling) else {
        // The pack is open and does not hold the word. That is a miss for the engines, not a
        // missing pack, and the card says so differently.
        return Answer::nothing(AnswerState::None, spelling, source, target);
    };

    // A spelling that is not the lemma got here through the forms index, and the reader is
    // owed the connection: they tapped "perros" and the answer is about "perro".
    let inflected = entry.lemma != spelling;
    let glosses: Vec<String> = entry.senses.iter().map(|s| s.gloss.clone()).collect();

    // Read in its own language, or read by a reader of English: either way the source pack
    // answers alone and there is no join to be ambiguous about.
    if source == target || target.0 == "en" {
        let state = if source == target {
            AnswerState::Mono
        } else if inflected {
            AnswerState::Form
        } else {
            AnswerState::Entry
        };
        return finish(
            state,
            spelling,
            &entry,
            glosses.clone(),
            glosses,
            pack,
            source,
            target,
        );
    }

    let Some(other) = open.target else {
        // The reader's language has no pack, so the English gloss is all there is. It is shown
        // as the anchor and the engines are asked for the rest.
        return finish(
            AnswerState::IpaOnly,
            spelling,
            &entry,
            Vec::new(),
            glosses,
            pack,
            source,
            target,
        );
    };

    // The join: this sense's English gloss, looked up in the reader's own pack. Every sense is
    // tried, best first within each, because the first sense of a word is not always the one a
    // reader met.
    let mut says: Vec<String> = Vec::new();
    let mut tied = false;
    for gloss in &glosses {
        // Each word reached, and how many of the gloss's terms reached it. A word reached
        // through two terms of "way, route" is a better answer than one reached through one,
        // and that is the whole of what separates Weg from Weise.
        let mut best: Vec<(String, usize)> = Vec::new();
        for ((which, _), shared) in other.senses_matching(gloss) {
            let Some(reached) = other.entry(which) else {
                continue;
            };
            // A noun is not answered with a verb that shares its gloss: "book" and "to book"
            // are the case this separates, and the part of speech is in both packs already.
            if !entry.pos.is_empty() && !reached.pos.is_empty() && reached.pos != entry.pos {
                continue;
            }
            match best.iter_mut().find(|(lemma, _)| *lemma == reached.lemma) {
                Some((_, had)) => *had = (*had).max(shared),
                None => best.push((reached.lemma, shared)),
            }
        }
        if best.is_empty() {
            continue;
        }
        let most = best.iter().map(|(_, shared)| *shared).max().unwrap_or(0);
        // Only what the gloss reached best. A word the gloss reached through fewer of its
        // terms is not a second answer, it is a worse one, and offering it beside the first
        // would make every multi-term gloss look ambiguous.
        says = best
            .into_iter()
            .filter(|(_, shared)| *shared == most)
            .map(|(l, _)| l)
            .collect();
        tied = says.len() > 1;
        break;
    }

    let state = match (says.len(), inflected) {
        (0, _) => AnswerState::IpaOnly,
        // Nothing in the data separates two words reached equally well, and the card shows
        // both rather than picking one.
        _ if tied => AnswerState::Homograph,
        (_, true) => AnswerState::Form,
        (_, false) => AnswerState::Entry,
    };
    finish(state, spelling, &entry, says, glosses, pack, source, target)
}

#[allow(clippy::too_many_arguments)]
fn finish<D: AsRef<[u8]>>(
    state: AnswerState,
    spelling: &str,
    entry: &Entry,
    says: Vec<String>,
    glosses: Vec<String>,
    pack: &Pack<D>,
    source: &Lang,
    target: &Lang,
) -> Answer {
    Answer {
        state,
        spelling: spelling.to_string(),
        lemma: if entry.lemma == spelling {
            None
        } else {
            Some(entry.lemma.clone())
        },
        pos: if entry.pos.is_empty() {
            None
        } else {
            Some(entry.pos.clone())
        },
        ipa: entry.ipa.clone(),
        says,
        glosses,
        provenance: Some(Provenance::Dictionary {
            pack: format!("lex-{}", pack.lang()),
        }),
        source: source.clone(),
        target: target.clone(),
    }
}
