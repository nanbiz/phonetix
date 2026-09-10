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
    /// What form the spelling is, where the dump named it: "plural", "past participle".
    ///
    /// The lemma alone does not say. A reader who met "ging" is owed that it is the past of
    /// "gehen" rather than being handed the two words and left to work out the relation.
    pub form: Option<String>,
    pub pos: Option<String>,
    /// How it is said, as the pack records it.
    pub ipa: Vec<String>,
    /// The first transcription, symbol by symbol, so a card can offer each sound on its own
    /// without a table of its own to look them up in.
    pub symbols: Vec<crate::symbols::Symbol>,
    /// The answer in the reader's own language, best first. More than one means the join was
    /// ambiguous and the card says so rather than choosing.
    pub says: Vec<String>,
    /// What the word means, in English, which is the anchor the card shows when the join is
    /// ambiguous or absent.
    pub glosses: Vec<String>,
    /// What the dump marks each of those senses as, in the same order: "colloquial",
    /// "archaic", "Latin America". A sense a reader would not use is worth knowing about
    /// before they use it, and the pack has carried these all along with nothing reading them.
    pub marks: Vec<Vec<String>>,
    /// Each word this spelling is, where it is more than one. "book" is a noun and a verb, and
    /// which of them a reader met is theirs to say: the card offers the readings and the
    /// cascade does not choose.
    pub readings: Vec<Reading>,
    /// The applying sense's example, where the dump had one. One line of the word in use is
    /// worth more than a second gloss, and a made-up sentence would be worth less than
    /// nothing, so this is empty rather than invented.
    pub example: Option<String>,
    pub provenance: Option<Provenance>,
    pub source: Lang,
    pub target: Lang,
}

/// One of the words a spelling is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Reading {
    pub pos: Option<String>,
    pub ipa: Vec<String>,
    /// What this reading answers with, in the reader's language where the join reached one.
    pub says: Vec<String>,
    /// And in English, which is what a reader is left with when it did not.
    pub glosses: Vec<String>,
}

impl Answer {
    fn nothing(state: AnswerState, spelling: &str, source: &Lang, target: &Lang) -> Answer {
        Answer {
            state,
            spelling: spelling.to_string(),
            lemma: None,
            form: None,
            pos: None,
            ipa: Vec::new(),
            symbols: Vec::new(),
            says: Vec::new(),
            glosses: Vec::new(),
            marks: Vec::new(),
            readings: Vec::new(),
            example: None,
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
    /// The accent the reader asked to hear the language in, as a code. Empty for the standard.
    pub accent: &'a str,
    /// That accent's own pack, where it has one. A few thousand words a dictionary tagged for
    /// one country, which is the half of an accent that no rule can produce.
    pub accent_pack: Option<&'a Pack<D>>,
}

impl<D: AsRef<[u8]>> Default for Open<'_, D> {
    fn default() -> Self {
        Open {
            source: None,
            target: None,
            ipa_only: false,
            accent: "",
            accent_pack: None,
        }
    }
}

/// Several words a reader selected, answered as one.
///
/// A phrase is not a word and no dictionary holds it, so what answers it is always the host's
/// engine and the answer is always a guess. It is built here rather than by either host so
/// that a phrase card and a word card are the same shape, made in the same place, and neither
/// platform can invent an answer of its own.
///
/// There is no transcription: a machine reading a whole clause aloud says nothing a reader
/// asked for, and the card has no row for it.
pub fn phrase(text: &str, said: &str, source: &Lang, target: &Lang) -> Answer {
    let mut answer = Answer::nothing(AnswerState::Phrase, text, source, target);
    if !said.is_empty() {
        answer.says = vec![said.to_string()];
        answer.provenance = Some(Provenance::Guess {
            engine: "bergamot".to_string(),
        });
    }
    answer
}

/// A spelling as the page wrote it, or failing that as the language writes it.
///
/// A page capitalises for its own reasons: the first word of a sentence, every word of a
/// heading, a list of settings written in title case. The dump keys a word the way the
/// language spells it, so "Search" at the top of a screen is not in it and "search" is - and a
/// reader looking at a real app's screen, where most text is a label or a heading, saw nearly
/// nothing answered.
///
/// As written first, because case is not always the page's doing: German capitalises every
/// noun, so "Bank" and "bank" are two different words and the pack holds both.
fn lookup_either_case<D: AsRef<[u8]>>(pack: &Pack<D>, spelling: &str) -> Vec<Entry> {
    let found = pack.lookup(spelling);
    if !found.is_empty() {
        return found;
    }
    let lowered = spelling.to_lowercase();
    if lowered == spelling {
        return Vec::new();
    }
    pack.lookup(&lowered)
}

/// Whether two spellings are the same word, which case alone does not decide.
///
/// A word met at the start of a sentence is not an inflected form of itself, and reporting it
/// as one puts "Search" on a card as a form of "search".
fn same_word(one: &str, other: &str) -> bool {
    one == other || one.to_lowercase() == other.to_lowercase()
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
    let found = lookup_either_case(pack, spelling);
    if found.is_empty() {
        // The pack is open and does not hold the word. That is a miss for the engines, not a
        // missing pack, and the card says so differently.
        return Answer::nothing(AnswerState::None, spelling, source, target);
    }

    let mut answers: Vec<Answer> = found
        .iter()
        .map(|entry| resolve_one(spelling, entry, source, target, pack, open.target))
        .collect();
    // How this reader's accent says it, decided here so that the inline layer, the card, the
    // lens and the audio cannot show four different transcriptions of the same word.
    //
    // An accent is two things and this is where they meet. Where its own pack holds the word,
    // that reading wins outright and no rule touches it: the data already is the accent, and
    // shifting it again would move a sound the dictionary put there deliberately. Where the
    // pack says nothing - which is most of a vocabulary, since a pack of a few thousand words
    // is what a dictionary tags for a country - the rule stands in, because a rule reaches
    // every word including the ones no data set lists.
    if !open.accent.is_empty() {
        let said = open
            .accent_pack
            .and_then(|pack| {
                lookup_either_case(pack, spelling)
                    .into_iter()
                    .next()
                    .or_else(|| {
                        lookup_either_case(pack, &answers[0].lemma.clone().unwrap_or_default())
                            .into_iter()
                            .next()
                    })
            })
            .map(|entry| entry.ipa)
            .filter(|ipa| !ipa.is_empty());
        for answer in answers.iter_mut() {
            answer.ipa = match &said {
                Some(ipa) => ipa.clone(),
                None => answer
                    .ipa
                    .iter()
                    .map(|ipa| crate::accent::apply(ipa, open.accent, spelling))
                    .collect(),
            };
            answer.symbols = answer
                .ipa
                .first()
                .map(|ipa| crate::symbols::explain(ipa))
                .unwrap_or_default();
        }
    }
    // The commonest word first, which the dump lists first, so a reader who does not choose
    // still gets the likely one.
    let mut first = answers.remove(0);
    if answers.is_empty() {
        return first;
    }
    // Several words under one spelling. The card shows them and the reader picks: nothing here
    // knows which of "book" they met, and guessing would be the confident wrong answer again.
    first.readings = std::iter::once(&first)
        .chain(answers.iter())
        .map(|answer| Reading {
            pos: answer.pos.clone(),
            ipa: answer.ipa.clone(),
            says: answer.says.clone(),
            glosses: answer.glosses.clone(),
        })
        .collect();
    first.state = AnswerState::Homograph;
    first
}

/// One of the words a spelling is, resolved on its own.
fn resolve_one<D: AsRef<[u8]>>(
    spelling: &str,
    entry: &Entry,
    source: &Lang,
    target: &Lang,
    pack: &Pack<D>,
    other: Option<&Pack<D>>,
) -> Answer {
    // A spelling that is not the lemma got here through the forms index, and the reader is
    // owed the connection: they tapped "perros" and the answer is about "perro".
    let inflected = !same_word(&entry.lemma, spelling);
    let glosses: Vec<String> = entry.senses.iter().map(|s| s.gloss.clone()).collect();
    // The first sense's, because that is the sense the card leads with.
    let example = entry.senses.first().and_then(|s| s.example.clone());

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
            entry,
            glosses.clone(),
            glosses,
            example,
            pack,
            source,
            target,
        );
    }

    let Some(other) = other else {
        // The reader's language has no pack, so the English gloss is all there is. It is shown
        // as the anchor and the engines are asked for the rest.
        return finish(
            AnswerState::IpaOnly,
            spelling,
            entry,
            Vec::new(),
            glosses,
            example,
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

    // A gloss that reaches two words equally well is not two answers. Nothing in the data
    // separates them, so the dictionary has nothing to say: handing both to a reader dressed as
    // an answer is the confident wrong answer this whole join is shaped to avoid, only twice
    // over. The word falls to the host's engine and the English gloss stands as the anchor
    // above whatever that guesses.
    if tied {
        says.clear();
    }
    let state = match (says.len(), inflected) {
        (0, _) => AnswerState::IpaOnly,
        (_, true) => AnswerState::Form,
        (_, false) => AnswerState::Entry,
    };
    finish(
        state, spelling, entry, says, glosses, example, pack, source, target,
    )
}

#[allow(clippy::too_many_arguments)]
fn finish<D: AsRef<[u8]>>(
    state: AnswerState,
    spelling: &str,
    entry: &Entry,
    says: Vec<String>,
    glosses: Vec<String>,
    example: Option<String>,
    pack: &Pack<D>,
    source: &Lang,
    target: &Lang,
) -> Answer {
    Answer {
        state,
        spelling: spelling.to_string(),
        lemma: if same_word(&entry.lemma, spelling) {
            None
        } else {
            Some(entry.lemma.clone())
        },
        // Only for the spelling that was actually met, and only where the dump named it.
        form: entry
            .forms
            .iter()
            .find(|form| same_word(&form.spelling, spelling))
            .map(|form| form.label.clone())
            .filter(|label| !label.is_empty()),
        pos: if entry.pos.is_empty() {
            None
        } else {
            Some(entry.pos.clone())
        },
        symbols: entry
            .ipa
            .first()
            .map(|ipa| crate::symbols::explain(ipa))
            .unwrap_or_default(),
        ipa: entry.ipa.clone(),
        says,
        glosses,
        marks: entry.senses.iter().map(|s| s.marks.clone()).collect(),
        readings: Vec::new(),
        example,
        provenance: Some(Provenance::Dictionary {
            pack: format!("lex-{}", pack.lang()),
        }),
        source: source.clone(),
        target: target.clone(),
    }
}
