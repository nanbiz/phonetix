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
#[derive(Clone, Copy)]
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
    /// What the translator made of the sentence this word is in, where the host had it
    /// translated. The strongest signal there is about which word a spelling is: the engine
    /// read the whole sentence to produce it, which is context no table has.
    pub said: Option<&'a str>,
    /// The trained classifier for this language, where the host has one open.
    ///
    /// It outranks the neighbour rule, because it was trained on how the word is really used
    /// and the rule is a generalisation about parts of speech. Where it says nothing, the rule
    /// still has its say.
    pub classifier: Option<&'a crate::homographs::Classifier>,
    /// Every pack the host holds, by language, for a line in another language than the one
    /// the screen is read in: an English notice on a German page is looked up in English.
    pub others: Option<&'a std::collections::HashMap<String, Pack<D>>>,
}

impl<'a, D: AsRef<[u8]>> Open<'a, D> {
    /// The same packs, read as [lang] where it is not the language they were opened for and
    /// the host holds a pack for it.
    pub fn reading(&self, lang: &str, source: &str) -> Open<'a, D> {
        match self.others.and_then(|all| all.get(lang)) {
            Some(pack) if lang != source => Open {
                source: Some(pack),
                accent: "",
                accent_pack: None,
                classifier: None,
                ..*self
            },
            _ => Open { ..*self },
        }
    }
}

impl<D: AsRef<[u8]>> Default for Open<'_, D> {
    fn default() -> Self {
        Open {
            source: None,
            target: None,
            ipa_only: false,
            accent: "",
            accent_pack: None,
            said: None,
            classifier: None,
            others: None,
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
    let lowered = spelling.to_lowercase();
    if lowered == spelling {
        return found;
    }
    // Capitalised, so possibly only because it starts a sentence. The word written in small
    // letters is looked up as well and comes before a name that happens to be spelled the
    // same: "Le" at the start of a French sentence is the article, not the surname. A word
    // that is only ever capitalised - a German noun - has no small-letter entry to compete.
    let small = pack.lookup(&lowered);
    if small.is_empty() {
        return found;
    }
    let (names, words): (Vec<Entry>, Vec<Entry>) =
        found.into_iter().partition(|entry| entry.pos == "name");
    words.into_iter().chain(small).chain(names).collect()
}

/// Where an entry stands among the ones a spelling reaches: the word itself, then what it is
/// an inflection of, then entries that are only notes pointing elsewhere.
fn rank_of(entry: &Entry, spelling: &str) -> u8 {
    // "form-of" for an inflection, "alt-of" for a spelling of another word: either way the
    // sense is a pointer, not a meaning.
    let pointing = |sense: &lexpack::Sense| {
        sense
            .marks
            .iter()
            .any(|mark| mark == "form-of" || mark == "alt-of")
    };
    let kind = by_kind(&entry.pos);
    // A letter of the alphabet is an entry under every one-letter spelling and under its
    // plural - "es" is the plural of "e" - and a mark like "è" has an entry explaining the
    // accent. Neither is ever the word a reader was reading.
    let about_itself = matches!(entry.pos.as_str(), "character" | "letter" | "symbol")
        || (!entry.senses.is_empty()
            && entry
                .senses
                .iter()
                .all(|sense| names_a_letter(&sense.gloss)));
    if about_itself {
        return 40;
    }
    // An entry that only points at another word says less than the word it points at - except
    // for the words that hold a sentence together, which the dictionary files as forms of each
    // other: German "das" is "neuter singular of der: the", Portuguese "pelo" is "por + o". Those
    // are still the words a reader meets on every line.
    if kind != 0 && !entry.senses.is_empty() && entry.senses.iter().all(pointing) {
        return 32 + kind;
    }
    let leads_with_meaning =
        entry.senses.first().is_some_and(|sense| !pointing(sense)) || kind == 0;
    // The word itself, written exactly as it is on the page: a capitalised German noun before
    // the lowercase word it would also match, "Weg" before "weg". Then the word regardless of
    // case - where a sentence's first word competes with a name spelled the same, which is
    // left to the kind of word to decide. Then what the spelling is an inflection of.
    // Only a noun is capitalised for being what it is; any other word written with a capital is
    // more likely starting a sentence, and the word in small letters is the one meant - German
    // "Er" at the start of a line is "er", "he", not the old polite "Er", "you".
    let capital = spelling.chars().next().is_some_and(char::is_uppercase);
    let as_written = entry.lemma == spelling && entry.pos != "name";
    let level = if as_written && leads_with_meaning && (!capital || entry.pos == "noun") {
        0
    } else if same_word(&entry.lemma, spelling) && leads_with_meaning {
        1
    } else {
        2
    };
    // And within that, the kind of word it is. A word that holds a sentence together - an
    // article, a preposition, a contraction of the two - is met on nearly every line, and a
    // word that happens to share its spelling almost never is: "la" the musical note, "sulla"
    // the plant, "verso" the line of verse. A pronoun sharing an article's spelling is met more
    // often than those and less often than the article. The dump lists them in whatever order
    // its pages happen to be in.
    // A rare kind of word is never the likelier reading on the strength of its spelling
    // alone: Portuguese "é" is "is" far more often than it is "é!", "yes", though only the
    // interjection is written exactly so.
    let level = if kind == 3 { level.max(2) } else { level };
    level * 4 + kind
}

/// How often a kind of word is the reading meant, among readings that are otherwise equal.
fn by_kind(pos: &str) -> u8 {
    match pos {
        "article" | "det" | "contraction" | "prep" | "postp" | "conj" | "particle" => 0,
        "pron" => 1,
        // An interjection spelled like a verb form - Portuguese "é", "is", and "é!", "yes" - is
        // the rarer of the two in anything written.
        "name" | "character" | "letter" | "symbol" | "intj" => 3,
        _ => 2,
    }
}

/// Whether a word is one that holds a sentence together rather than one a reader learns: an
/// article, a preposition, a conjunction, a pronoun, a contraction of those, or one of the
/// verbs English builds its tenses and moods with.
///
/// Such a word is on every line, so any share of its occurrences is still a replacement on
/// every line; replaced, it does not agree with the words around it, which are left as the
/// page wrote them - "el comment", "un comedy" - and it has nothing to teach.
pub fn holds_together(answer: &Answer, spelling: &str, lang: &Lang) -> bool {
    let Some(pos) = answer.pos.as_deref() else {
        return false;
    };
    if by_kind(pos) <= 1 {
        return true;
    }
    if lang.0 != "en" || pos != "verb" {
        return false;
    }
    let lemma = answer
        .lemma
        .clone()
        .unwrap_or_else(|| spelling.to_string())
        .to_lowercase();
    AUXILIARIES.contains(&lemma.as_str())
}

/// The verbs English makes its tenses, questions and moods with.
const AUXILIARIES: &[&str] = &[
    "be", "have", "do", "will", "would", "shall", "should", "can", "could", "may", "might", "must",
];

/// Whether a sense is a letter of the alphabet naming itself.
fn names_a_letter(gloss: &str) -> bool {
    let lowered = gloss.to_lowercase();
    lowered.contains("letter")
        && (lowered.contains("name of the")
            || lowered.contains("of the") && lowered.contains("alphabet"))
}

/// Whether two spellings are the same word, which case alone does not decide.
///
/// A word met at the start of a sentence is not an inflected form of itself, and reporting it
/// as one puts "Search" on a card as a form of "search".
fn same_word(one: &str, other: &str) -> bool {
    one == other || one.to_lowercase() == other.to_lowercase()
}

/// How good a word is as the answer to what was typed, best first when sorted.
///
/// How often the word is met decides among the ones that answer equally well: "perro" and the
/// poetic "can" are both "dog", and one of them is the word a Spanish speaker says. Where the
/// pack was built without a frequency list, the number of senses stands in, since the words a
/// language uses most are the ones its dictionary says most about.
#[derive(PartialEq, Eq, PartialOrd, Ord)]
struct Rank {
    // A word of the wrong kind, or one a dictionary marks as not the ordinary word, loses to
    // any plain one, however well its gloss matches: "can" is glossed "dog, hound" exactly and
    // is the formal word, "perro" only "dog" and is the word.
    wrong_part: bool,
    marked: bool,
    /// Whether the answer is a phrase rather than a word: "from" is "de" before it is
    /// "a partir de", and drawn over a page a phrase for every word read like a sentence.
    phrase: bool,
    /// Whether the word is missing from the list of words met in running text, where the pack
    /// has one: "enel" is glossed "in the" exactly and nobody writes it, "Uds" is "you", and
    /// "en el" and "tú" are what a speaker says.
    unheard: bool,
    shared: std::cmp::Reverse<usize>,
    asked: usize,
    /// Whether what was typed is a later sense of the word rather than its first: "caja" is a
    /// box first and a bank somewhere down its list, "banco" a bank first.
    later: bool,
    met: std::cmp::Reverse<u64>,
    sense: u32,
    common: std::cmp::Reverse<usize>,
    kind: u8,
}

/// How often a word is met in running text, as its pack recorded it, or nothing where the
/// pack was built without a frequency list or the list does not have the word.
pub fn how_often(entry: &Entry) -> Option<u64> {
    entry
        .tags
        .iter()
        .find_map(|tag| tag.strip_prefix("count:"))
        .and_then(|count| count.parse().ok())
}

/// Senses a dictionary marks as no longer in use.
const GONE: &[&str] = &["obsolete", "archaic"];

/// Senses a dictionary marks as not the ordinary word for something.
const UNUSUAL: &[&str] = &[
    "slang",
    "colloquial",
    "informal",
    "formal",
    "vulgar",
    "derogatory",
    "rare",
    "archaic",
    "obsolete",
    "dated",
    "dialectal",
    "neologism",
    "regional",
    "poetic",
    "literary",
    "nonstandard",
    "humorous",
    "euphemistic",
    "figuratively",
    "Internet",
];

/// The word for something a reader wants to say, out of the dictionaries alone.
///
/// The panel asks the translation engine first, which reads a whole phrase and knows which
/// word is commonest. Where there is no model for the direction - not every pair of languages
/// has one, and one has to be fetched - the dictionaries still know: every sense in every pack
/// is glossed in English, so "dog" typed by a reader of English is found through the gloss
/// index of the language they are learning, and "Hund" typed by a reader of German is looked
/// up in German first and found through its English gloss.
///
/// Best first: the words whose senses share the most with what was typed, then those where it
/// is an earlier sense, since a dictionary lists a word's commonest meanings first. Nothing
/// that is only a pointer to another word, a name or a letter.
pub fn word_for<D: AsRef<[u8]>>(
    text: &str,
    typed_in: &Lang,
    wanted: &Pack<D>,
    typed: Option<&Pack<D>>,
) -> Vec<String> {
    let text = text.trim();
    if text.is_empty() {
        return Vec::new();
    }
    // What was typed, as English glosses to look for, each with the part of speech it had.
    let glosses: Vec<(String, Option<String>)> = if typed_in.0 == "en" {
        vec![(text.to_string(), None)]
    } else {
        let Some(pack) = typed else {
            return Vec::new();
        };
        let mut found = lookup_either_case(pack, text);
        found.sort_by_key(|entry| rank_of(entry, text));
        let Some(entry) = found.into_iter().next() else {
            return Vec::new();
        };
        entry
            .senses
            .iter()
            .filter(|sense| !crate::annotate::about_grammar(&sense.gloss))
            .take(3)
            .map(|sense| (sense.gloss.clone(), Some(entry.pos.clone())))
            .collect()
    };
    // Typed in English without "to", it is not a verb: "house" is a house, not "to house".
    let typed_verb = typed_in.0 == "en" && text.to_lowercase().starts_with("to ");
    glossed_as(
        &glosses,
        (typed_in.0 == "en").then_some(typed_verb),
        wanted,
        3,
    )
}

/// The words of [wanted] whose senses are glossed as [glosses], best first.
///
/// [verb] says whether what is looked for is a verb, where only that is known: an English word
/// with no part of speech, where a gloss that is a verb is written with "to".
fn glossed_as<D: AsRef<[u8]>>(
    glosses: &[(String, Option<String>)],
    verb: Option<bool>,
    wanted: &Pack<D>,
    most: usize,
) -> Vec<String> {
    // The same question comes back all the time: a page says "the" and "a" dozens of times
    // and is read again whenever it moves, and a common English word reaches hundreds of
    // senses in the other pack, each one unpacked to be weighed. Answered once per pack.
    let key = format!(
        "{}\u{1}{}\u{1}{}\u{1}{:?}\u{1}{most}\u{1}{glosses:?}",
        wanted.lang(),
        wanted.built(),
        wanted.len(),
        verb
    );
    if let Some(had) = GLOSSED.with(|memo| memo.borrow().get(&key).cloned()) {
        return had;
    }
    let words = weighed(glosses, verb, wanted, most);
    GLOSSED.with(|memo| {
        let mut memo = memo.borrow_mut();
        if memo.len() >= GLOSSED_KEPT {
            memo.clear();
        }
        memo.insert(key, words.clone());
    });
    words
}

thread_local! {
    /// What [glossed_as] answered, by question. Cleared whole when it fills rather than kept
    /// in order: what a reader reads again is the page in front of them, which refills it.
    static GLOSSED: std::cell::RefCell<std::collections::HashMap<String, Vec<String>>> =
        std::cell::RefCell::new(std::collections::HashMap::new());
}
const GLOSSED_KEPT: usize = 8192;

/// How many of the senses a gloss term reaches are unpacked to be weighed. The best of them
/// share the most terms with it and are early senses of their word, and a term like "a"
/// reaches thousands.
const WEIGHED_AT_MOST: usize = 160;

fn weighed<D: AsRef<[u8]>>(
    glosses: &[(String, Option<String>)],
    verb: Option<bool>,
    wanted: &Pack<D>,
    most: usize,
) -> Vec<String> {
    let mut scored: Vec<(Rank, String)> = Vec::new();
    for (asked, (gloss, pos)) in glosses.iter().enumerate() {
        let mut hits = wanted.senses_matching(gloss);
        hits.sort_by_key(|((which, sense), shared)| (std::cmp::Reverse(*shared), *sense, *which));
        hits.truncate(WEIGHED_AT_MOST);
        for ((which, sense), shared) in hits {
            let Some(entry) = wanted.entry(which) else {
                continue;
            };
            if let Some(pos) = pos {
                if !pos.is_empty() && !entry.pos.is_empty() && !same_part(&entry.pos, pos) {
                    continue;
                }
            }
            let kind = by_kind(&entry.pos);
            // An entry that only points at another word is not the word - except a function
            // word, which a dictionary describes by its grammar: "el" is "masculine singular
            // definite article; the".
            // One whose notes end in a meaning is the word for it: "tú" is "second person
            // pronoun in singular tense; you".
            let pointer = kind != 0
                && entry
                    .senses
                    .iter()
                    .all(|sense| meaning_of(&sense.gloss).is_none());
            if kind == 3 || pointer {
                continue;
            }
            let marks: &[String] = entry
                .senses
                .get(sense as usize)
                .map(|sense| sense.marks.as_slice())
                .unwrap_or(&[]);
            // A sense nobody uses any more is not handed to a reader at all, even where it is
            // the only one that answers: "enel" is "in the" and has not been written for
            // centuries.
            if marks.iter().any(|mark| GONE.contains(&mark.as_str())) {
                continue;
            }
            // Nor a sense that is another word's form: "la" as the accusative of "ella" is
            // "her", and the count it carries is the article's.
            if marks.iter().any(|mark| mark == "form-of") {
                continue;
            }
            // How formal a pronoun is, is which pronoun it is rather than a register it is in:
            // "tú" is marked informal and is the ordinary word for "you".
            let pronoun = entry.pos == "pron";
            let marked = marks.iter().any(|mark| {
                UNUSUAL.contains(&mark.as_str())
                    && !(pronoun && matches!(mark.as_str(), "formal" | "informal"))
            });
            let wrong_part = verb.is_some_and(|verb| (entry.pos == "verb") != verb);
            scored.push((
                Rank {
                    wrong_part,
                    marked,
                    phrase: entry.lemma.contains(' '),
                    unheard: how_often(&entry).is_none(),
                    shared: std::cmp::Reverse(shared),
                    asked,
                    later: sense > 0,
                    met: std::cmp::Reverse(how_often(&entry).unwrap_or(0)),
                    sense,
                    common: std::cmp::Reverse(entry.senses.len()),
                    kind,
                },
                entry.lemma,
            ));
        }
    }
    scored.sort();
    let mut words: Vec<String> = Vec::new();
    for (_, lemma) in scored {
        if !words.contains(&lemma) {
            words.push(lemma);
        }
        if words.len() == most {
            break;
        }
    }
    words
}

/// How many of an English word's translations are looked for in its translated line.
const LOOKED_FOR_IN_LINE: usize = 12;

/// Which of the words [glosses] could be in [wanted]'s language the translated line uses, the
/// likeliest first where the line holds several, in the form the line has it: "the beans are
/// roasted" is "tostados" there, and the infinitive over it read as "the beans are to roast".
fn in_line<D: AsRef<[u8]>>(
    glosses: &[(String, Option<String>)],
    wanted: &Pack<D>,
    said: &str,
) -> Option<String> {
    let sentence = normalised(said);
    if sentence.is_empty() {
        return None;
    }
    glossed_as(glosses, None, wanted, LOOKED_FOR_IN_LINE)
        .into_iter()
        .find_map(|word| {
            // As the pack spells them, since the case is the word's own: a German noun.
            let mut forms = vec![word.clone()];
            for entry in wanted.lookup(&word) {
                if same_word(&entry.lemma, &word) {
                    forms.extend(entry.forms.iter().map(|form| form.spelling.clone()));
                }
            }
            forms
                .into_iter()
                .find(|form| holds(&sentence, &normalised(form)))
        })
}

/// What a sense means, where it can be looked for in another language: the sense itself, or
/// where it is a note about grammar, the meaning it ends with - "nominative masculine singular
/// definite article, the", "dative masculine/neuter singular of der: the". A German article is
/// filed that way, and skipped as grammar it reached no Spanish word at all.
fn meaning_of(gloss: &str) -> Option<String> {
    if !crate::annotate::about_grammar(gloss) {
        return Some(gloss.to_string());
    }
    let at = gloss.rfind([':', ',', ';'])?;
    let tail = gloss[at + 1..].trim();
    (!tail.is_empty()
        && tail.split_whitespace().count() <= 3
        && !crate::annotate::about_grammar(tail))
    .then(|| tail.to_string())
}

/// Whether two parts of speech are the same kind of word, as two dictionaries file them: one
/// calls "the" a determiner and another calls "el" an article.
fn same_part(one: &str, other: &str) -> bool {
    fn kind(pos: &str) -> &str {
        match pos {
            "article" | "det" => "det",
            other => other,
        }
    }
    kind(one) == kind(other)
}

/// Whether an entry is a reading nobody means in ordinary text: a letter or a symbol, only a
/// pointer to how another word is or once was spelled, or a word every sense of which the dictionary marks
/// as dialectal, obsolete, slang and the like.
fn is_minor(entry: &Entry) -> bool {
    if by_kind(&entry.pos) == 3 && entry.pos != "name" && entry.pos != "intj" {
        return true;
    }
    if entry.senses.is_empty() {
        return false;
    }
    let spelled_elsewhere = entry.senses.iter().all(|sense| {
        let gloss = sense.gloss.to_lowercase();
        crate::annotate::about_grammar(&gloss)
            && ["spelling of", "obsolete form of", "archaic form of"]
                .iter()
                .any(|note| gloss.contains(note))
    });
    let unusual = entry.senses.iter().all(|sense| {
        sense
            .marks
            .iter()
            .any(|mark| UNUSUAL.contains(&mark.as_str()))
    });
    spelled_elsewhere || unusual
}

/// Look one word up.
pub fn look_up<D: AsRef<[u8]>>(
    spelling: &str,
    source: &Lang,
    target: &Lang,
    open: &Open<D>,
) -> Answer {
    read_in_context(spelling, None, source, target, open)
}

/// Look one word up, knowing the word before it.
///
/// A spelling that is several words is decided by what surrounds it. The neighbour is the
/// signal that works in every language: a determiner is followed by a noun, an infinitive
/// marker by a verb. Where it decides, the card leads with that reading and offers the others
/// quietly; where it does not, the reader is asked, because a wrong word wearing a
/// dictionary's authority is what the whole cascade is shaped to avoid.
pub fn read_in_context<D: AsRef<[u8]>>(
    spelling: &str,
    before: Option<&str>,
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
    let mut found = lookup_either_case(pack, spelling);
    if found.is_empty() {
        // The pack is open and does not hold the word. That is a miss for the engines, not a
        // missing pack, and the card says so differently.
        return Answer::nothing(AnswerState::None, spelling, source, target);
    }
    // The entry that is this word before one it is only a form of, and a stub that does
    // nothing but point at another entry last. The pack lists hits in the order the entries
    // were built, which is the dump's alphabetical order, so "caminar" - whose forms include
    // "camino" - came before "camino" itself, and "camino" was read as "to walk".
    found.sort_by_key(|entry| rank_of(entry, spelling));

    // With the line translated, each entry leads with the sense the line is about: "banco" on
    // a line about a bench is the bench, though the dictionary lists the bank first.
    let found: Vec<Entry> = match open.said {
        Some(said) => found
            .into_iter()
            .map(|entry| sense_in_line(entry, said, spelling, source, target, pack, open))
            .collect(),
        None => found,
    };
    // Only the ordinary words, where there is one. A spelling the dictionary also files as a
    // letter, a braille cell, another alphabet's spelling of it, or a word nobody uses any more
    // is not a second word a reader could have meant: English "and" is a conjunction, and the
    // dialectal "breath", the obsolete "envy" and the Shavian spelling beside it made every
    // "and" a question and filled its card.
    let found: Vec<Entry> = if found.iter().any(|entry| !is_minor(entry)) {
        found.into_iter().filter(|entry| !is_minor(entry)).collect()
    } else {
        found
    };
    let mut answers: Vec<Answer> = found
        .iter()
        .map(|entry| {
            resolve_one(
                spelling,
                entry,
                source,
                target,
                pack,
                open.target,
                open.said,
            )
        })
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
    let first = answers.remove(0);
    if answers.is_empty() {
        return first;
    }
    // Several words under one spelling. What the word before it makes likely decides, where
    // it decides clearly; otherwise the card shows the readings and the reader picks, because
    // guessing would be the confident wrong answer again.
    let mut all: Vec<Answer> = std::iter::once(first).chain(answers).collect();
    // The first confident signal decides. A classifier trained on how this word is really used
    // outranks a rule about parts of speech; where it says nothing, the rule still has its say.
    // Strongest first. What the translator made of the whole sentence outranks both tables:
    // it read the sentence, and they read a word and its neighbour.
    let meant: Vec<Vec<Vec<String>>> = all
        .iter()
        .map(|answer| meant_words(&answer.says, answer.pos.as_deref(), target, pack, open))
        .collect();
    let decided = chosen_by_translation(&meant, open.said)
        .or_else(|| chosen_by_training(&all, spelling, before, open))
        // One reading's answer met far more often than any other's outweighs the word before
        // it: "from someone" is a preposition before a pronoun, and read as one before a noun
        // it was "a person of importance".
        .or_else(|| commonest_answer(&all, open))
        .or_else(|| chosen_by_neighbour(&all, before, pack))
        // With nothing else to go on, a word that is a word of grammar is that word: "the" is
        // the article, not the adverb of "the more the merrier".
        .or_else(|| {
            all.first()
                .and_then(|first| first.pos.as_deref())
                .is_some_and(|pos| by_kind(pos) == 0)
                .then_some(0)
        });
    if let Some(at) = decided {
        all.swap(0, at);
    }
    let mut first = all.remove(0);
    first.readings = std::iter::once(&first)
        .chain(all.iter())
        .map(|answer| Reading {
            pos: answer.pos.clone(),
            ipa: answer.ipa.clone(),
            says: answer.says.clone(),
            glosses: answer.glosses.clone(),
        })
        .collect();
    // Decided, so the card leads with it and keeps the others under the grammar line rather
    // than asking. Undecided, so it asks.
    if decided.is_none() {
        first.state = AnswerState::Homograph;
    }
    first
}

/// The reading whose answer in the reader's language is met most often in running text, where
/// the pack read into counts its words and one answer is met far more than the rest.
fn commonest_answer<D: AsRef<[u8]>>(all: &[Answer], open: &Open<D>) -> Option<usize> {
    let pack = open.target?;
    let counts: Vec<u64> = all
        .iter()
        .map(|answer| {
            answer
                .says
                .first()
                .map(|word| {
                    pack.lookup(word)
                        .iter()
                        .filter(|entry| same_word(&entry.lemma, word))
                        .filter_map(how_often)
                        .max()
                        .unwrap_or(0)
                })
                .unwrap_or(0)
        })
        .collect();
    let (best, most) = counts.iter().enumerate().max_by_key(|(_, count)| **count)?;
    let second = counts
        .iter()
        .enumerate()
        .filter(|(at, _)| *at != best)
        .map(|(_, count)| *count)
        .max()
        .unwrap_or(0);
    (*most > 0 && *most >= second.saturating_mul(COMMONER_BY)).then_some(best)
}

/// How many times as often one reading's answer has to be met as any other's to be drawn first.
const COMMONER_BY: u64 = 4;

/// Which reading the sentence's own translation says this is.
///
/// When the reader is being given a translation at all, the engine has already read the whole
/// sentence and answered it: "modern" that came back as "rot" is the verb, and one that came
/// back as "modern" is the adjective. That is context no table here has, which is why it
/// outranks both of them.
///
/// What is compared is the words each reading means - see [meant_words] - against the words of
/// the translated sentence, whole words only. It decides only when exactly one reading is in
/// there: two readings both present is the sentence saying nothing about which of them this
/// word was, and none present is the engine having chosen words neither reading lists.
///
/// This is the evidence the translation gives rather than the alignment it was meant to give:
/// the published WebAssembly build of the engine exposes the translated text and nothing else
/// (there is no alignment accessor in its bindings), so which target span this source word
/// became cannot be asked for. Matching the sentence is weaker where a reading's word appears
/// for some other reason, which is why it has to be the only one present to decide anything.
fn chosen_by_translation(meant: &[Vec<Vec<String>>], said: Option<&str>) -> Option<usize> {
    let said = said?;
    if said.trim().is_empty() {
        return None;
    }
    let sentence = normalised(said);
    let mut found: Option<(usize, Vec<&Vec<String>>)> = None;
    for (at, words) in meant.iter().enumerate() {
        let mut matched: Vec<&Vec<String>> =
            words.iter().filter(|word| holds(&sentence, word)).collect();
        if matched.is_empty() {
            continue;
        }
        matched.sort();
        matched.dedup();
        match &found {
            // Two readings found through the same words of the sentence mean the same thing
            // there - "est" as a form of "être" and as an old spelling of it both come back
            // as "is" - so the sentence has not been asked to choose between them, and the
            // one ranked first stands.
            Some((_, first)) if *first == matched => {}
            // Two of them are in the sentence through different words, so it says nothing
            // about which this word was.
            Some(_) => return None,
            None => found = Some((at, matched)),
        }
    }
    found.map(|(at, _)| at)
}

/// An entry with the sense its translated line is about put first.
///
/// Each of the first senses is looked for in the line the way a reading is (see
/// [meant_words]): for a reader of English by the sense's own terms, for a reader of another
/// language by what that one sense joins to in their pack. It moves only when the line holds
/// exactly one sense's words; a line with the words of two senses, or of none, leaves the
/// dictionary's order as it was.
fn sense_in_line<D: AsRef<[u8]>>(
    entry: Entry,
    said: &str,
    spelling: &str,
    source: &Lang,
    target: &Lang,
    pack: &Pack<D>,
    open: &Open<D>,
) -> Entry {
    if entry.senses.len() < 2 {
        return entry;
    }
    let meant: Vec<Vec<Vec<String>>> = entry
        .senses
        .iter()
        .take(crate::annotate::SENSES_CONSIDERED)
        .map(|sense| {
            if crate::annotate::about_grammar(&sense.gloss) {
                return Vec::new();
            }
            let says = if target.0 == "en" {
                vec![sense.gloss.clone()]
            } else {
                let alone = Entry {
                    senses: vec![sense.clone()],
                    ..entry.clone()
                };
                resolve_one(spelling, &alone, source, target, pack, open.target, None).says
            };
            meant_words(&says, Some(entry.pos.as_str()), target, pack, open)
        })
        .collect();
    match chosen_by_translation(&meant, Some(said)) {
        Some(at) if at > 0 => {
            let mut entry = entry;
            let sense = entry.senses.remove(at);
            entry.senses.insert(0, sense);
            entry
        }
        _ => entry,
    }
}

/// A gloss's terms, each beside the part of the gloss it came from, so what the
/// normalising took away - the "to" of an infinitive - can still be read.
fn raw_terms(gloss: &str) -> Vec<(String, String)> {
    let head = gloss.split('(').next().unwrap_or(gloss);
    head.split([',', ';'])
        .filter_map(|part| {
            let term = crate::gloss::terms(part).into_iter().next()?;
            Some((part.to_string(), term))
        })
        .collect()
}

/// The words that follow an English verb to make a phrasal verb of it.
const PARTICLES: &[&str] = &[
    "down", "up", "out", "off", "on", "in", "over", "away", "back", "around", "through",
];

/// The words a reading means, each as it could be written in a translated sentence.
///
/// What a reading answers with is what a dictionary writes for a card: for a reader of
/// English, the gloss itself - "east", "short, brief", "third-person singular present
/// indicative of être". A translated sentence has none of those as they stand. It has the
/// terms of a gloss, one at a time, and a verb inflected: "court" is "runs" there, not "to
/// run". And a reading that only points at another word means what that word means, so the
/// form of "être" is looked for as "is", which is what the engine wrote.
///
/// For a reader of another language the reading already answers with words in it, and those
/// are looked for in every form the reader's own pack lists for them.
fn meant_words<D: AsRef<[u8]>>(
    says: &[String],
    pos: Option<&str>,
    target: &Lang,
    pack: &Pack<D>,
    open: &Open<D>,
) -> Vec<Vec<String>> {
    let mut words: Vec<Vec<String>> = Vec::new();
    // A verb by what the dictionary filed it as, or by the "to" a gloss gives an infinitive.
    let add = |term: &str, verb: bool, words: &mut Vec<Vec<String>>| {
        let mut pieces = normalised(term);
        if pieces.is_empty() || pieces.len() > 4 {
            return;
        }
        let forms = if target.0 == "en" {
            crate::gloss::english_forms(pieces.last().map(String::as_str).unwrap_or_default(), verb)
        } else {
            let lemma = pieces.join(" ");
            let mut forms = vec![lemma.clone()];
            if let Some(theirs) = open.target {
                for entry in theirs.lookup(&lemma) {
                    if same_word(&entry.lemma, &lemma) {
                        forms.extend(entry.forms.iter().map(|form| form.spelling.to_lowercase()));
                    }
                }
            }
            pieces.clear();
            forms
        };
        let head = pieces.len().saturating_sub(1);
        for form in forms {
            let mut word: Vec<String> = pieces[..head].to_vec();
            word.extend(normalised(&form));
            if !words.contains(&word) {
                words.push(word);
            }
        }
        // A verb with its particle - "to sit down", "to give up" - is inflected on the verb,
        // and a translation often leaves the particle out: "me siento en el banco" is "I sit
        // on the bench". Both are looked for.
        if target.0 == "en" && verb && pieces.len() == 2 && PARTICLES.contains(&pieces[1].as_str())
        {
            for form in crate::gloss::english_forms(&pieces[0], true) {
                for word in [vec![form.clone(), pieces[1].clone()], vec![form]] {
                    if !words.contains(&word) {
                        words.push(word);
                    }
                }
            }
        }
    };
    let verb_of = |pos: Option<&str>, raw: &str| {
        pos == Some("verb") || raw.trim_start().to_lowercase().starts_with("to ")
    };
    for said in says {
        if target.0 != "en" {
            add(said, false, &mut words);
            continue;
        }
        for (raw, term) in raw_terms(said) {
            if !crate::annotate::about_grammar(&term) {
                add(&term, verb_of(pos, &raw), &mut words);
            }
        }
        // A note about grammar means the word it points at, or the meaning it quotes.
        if crate::annotate::about_grammar(said) {
            if let Some(quoted) = crate::annotate::quoted(said) {
                for (raw, term) in raw_terms(&quoted) {
                    add(&term, verb_of(pos, &raw), &mut words);
                }
            } else if let Some(pointed) = crate::annotate::points_at(said) {
                for entry in lookup_either_case(pack, &pointed) {
                    let filed = Some(entry.pos.as_str());
                    for sense in &entry.senses {
                        if crate::annotate::about_grammar(&sense.gloss) {
                            continue;
                        }
                        for (raw, term) in raw_terms(&sense.gloss) {
                            add(&term, verb_of(filed, &raw), &mut words);
                        }
                    }
                }
            }
        }
    }
    words
}

/// The words of a text, lowercased, with everything that is not a letter or a digit dropped.
///
/// Written as words rather than as one string, because "rot" is in "rotten" and a reading that
/// matched half of another word would be a signal that decides by accident.
fn normalised(text: &str) -> Vec<String> {
    text.split(|c: char| !c.is_alphanumeric())
        .filter(|word| !word.is_empty())
        .map(|word| word.to_lowercase())
        .collect()
}

/// Whether a sentence holds this answer, which may itself be several words, in order.
fn holds(sentence: &[String], answer: &[String]) -> bool {
    if answer.is_empty() || answer.len() > sentence.len() {
        return false;
    }
    sentence
        .windows(answer.len())
        .any(|window| window == answer)
}

/// What a reading answers with in the reader's own language, which is what a translated
/// sentence can be searched for.
pub trait HasAnswers {
    fn answers_with(&self) -> &[String];
}

impl HasAnswers for Answer {
    fn answers_with(&self) -> &[String] {
        &self.says
    }
}

impl HasAnswers for Reading {
    fn answers_with(&self) -> &[String] {
        &self.says
    }
}

/// Which reading the training says this is, where a classifier is open and a rule matched.
///
/// The classifier answers with a pronunciation and, where the training recorded one, a part of
/// speech. Which of the pack's readings that is comes from matching one or the other: the two
/// tables were built from the same dump but they are two tables, and a reading it names that
/// the pack does not have is a disagreement to leave alone rather than to resolve.
fn chosen_by_training<D: AsRef<[u8]>, R: HasReading>(
    readings: &[R],
    spelling: &str,
    before: Option<&str>,
    open: &Open<D>,
) -> Option<usize> {
    let classifier = open.classifier?;
    // The neighbour is all the context this side has. A decision list reads a window of five
    // words either side and will use what it is given; a rule keyed on the word before is the
    // one kind it can still match.
    let around: Vec<&str> = match before {
        Some(before) => vec![before, spelling],
        None => vec![spelling],
    };
    let at = around.len() - 1;
    let said = classifier.read(spelling, &around, at)?;
    // By pronunciation first, which is the thing both tables agree about most closely.
    if let Some(found) = readings.iter().position(|reading| {
        reading
            .ipa_of()
            .is_some_and(|ipa| crate::symbols::same_sound(ipa, &said.pronunciation))
    }) {
        return Some(found);
    }
    if said.label.is_empty() {
        return None;
    }
    let wanted = said.label.to_lowercase();
    let mut matching = readings
        .iter()
        .enumerate()
        .filter(|(_, reading)| reading.pos_of().unwrap_or_default().to_lowercase() == wanted);
    let first = matching.next()?;
    // Two readings with the same part of speech is the classifier naming a distinction this
    // pack does not draw, which decides nothing.
    if matching.next().is_some() {
        return None;
    }
    Some(first.0)
}

/// Which reading the word before this one makes likely, where one clearly wins.
///
/// Nothing where the neighbour is not in the pack, where it is itself several words, or where
/// two readings are equally preferred: each of those is the signal saying it does not know,
/// which is different from it choosing.
fn chosen_by_neighbour<D: AsRef<[u8]>, R: HasPos>(
    readings: &[R],
    before: Option<&str>,
    pack: &Pack<D>,
) -> Option<usize> {
    let before = before?;
    let neighbours = lookup_either_case(pack, before);
    // A neighbour that is itself ambiguous says nothing: reading one guess by another is how
    // a mistake becomes two.
    if neighbours.len() != 1 {
        return None;
    }
    let its_pos = neighbours[0].pos.to_lowercase();
    let wants: Vec<&str> = crate::neighbours::RULES
        .iter()
        .filter(|rule| rule.after == its_pos)
        .map(|rule| rule.prefers)
        .collect();
    if wants.is_empty() {
        return None;
    }
    let mut votes: Vec<(usize, u32)> = readings
        .iter()
        .enumerate()
        .map(|(at, reading)| {
            let pos = reading.pos_of().unwrap_or_default().to_lowercase();
            let score = wants.iter().filter(|want| **want == pos).count() as u32;
            (at, score)
        })
        .collect();
    votes.sort_by_key(|(_, score)| std::cmp::Reverse(*score));
    let (best, most) = votes[0];
    if most == 0 {
        return None;
    }
    let runner_up = votes.get(1).map(|(_, score)| *score).unwrap_or(0);
    if most < runner_up + crate::neighbours::MARGIN {
        return None;
    }
    Some(best)
}

/// What both an Answer and a Reading can say about themselves, so one rule reads either.
pub trait HasPos {
    fn pos_of(&self) -> Option<&str>;
}

impl HasPos for Answer {
    fn pos_of(&self) -> Option<&str> {
        self.pos.as_deref()
    }
}

impl HasPos for Reading {
    fn pos_of(&self) -> Option<&str> {
        self.pos.as_deref()
    }
}

/// The same, for the signal that matches on how a word is said as well as what it is.
pub trait HasReading: HasPos {
    fn ipa_of(&self) -> Option<&str>;
}

impl HasReading for Answer {
    fn ipa_of(&self) -> Option<&str> {
        self.ipa.first().map(|it| it.as_str())
    }
}

impl HasReading for Reading {
    fn ipa_of(&self) -> Option<&str> {
        self.ipa.first().map(|it| it.as_str())
    }
}

/// One of the words a spelling is, resolved on its own.
fn resolve_one<D: AsRef<[u8]>>(
    spelling: &str,
    entry: &Entry,
    source: &Lang,
    target: &Lang,
    pack: &Pack<D>,
    other: Option<&Pack<D>>,
    said: Option<&str>,
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

    // An English word is its own gloss. The English dictionary defines its words rather than
    // translating them - "and" is "used simply to connect two noun phrases" - and no other
    // language glosses a word that way, so the word itself is what is looked for in the
    // reader's pack: "and" is where Spanish "y" is glossed.
    if source.0 == "en" {
        let asked = [(entry.lemma.clone(), Some(entry.pos.clone()))];
        // With the line translated, the word the engine wrote for it, where it is one this
        // word can be: "reviews" of products are "reseñas" there, though "review" alone ranks
        // "repaso" first, and "accurate" is "exacto" rather than the verb "acertar".
        let found = said
            .and_then(|said| in_line(&asked, other, said))
            .map(|word| vec![word])
            .unwrap_or_else(|| glossed_as(&asked, None, other, 1));
        if !found.is_empty() {
            let state = if inflected {
                AnswerState::Form
            } else {
                AnswerState::Entry
            };
            return finish(
                state, spelling, entry, found, glosses, example, pack, source, target,
            );
        }
    }

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
    // What the line translated uses, where it is one of the words this could be; otherwise,
    // where the join above tied or reached nothing, the likeliest of the words its first
    // senses are glossed as, ranked the way a word typed into the panel is: "für" is glossed
    // "for", which Spanish glosses "para" and "por" alike, and leaving it unanswered drew the
    // English gloss over a page being read in Spanish.
    let asked: Vec<(String, Option<String>)> = glosses
        .iter()
        .filter_map(|gloss| meaning_of(gloss))
        .take(3)
        .map(|gloss| (gloss, Some(entry.pos.clone())))
        .collect();
    if let Some(word) = said.and_then(|said| in_line(&asked, other, said)) {
        says = vec![word];
    } else {
        let ranked = glossed_as(&asked, None, other, 1);
        if !ranked.is_empty() {
            says = ranked;
        } else if tied || !asked.is_empty() {
            // What the ranking turned down - a word nobody has used for centuries - is not
            // brought back by the plainer join above.
            says.clear();
        }
    }
    let state = match (says.len(), inflected) {
        // The entry is here and the reader's pack is open; what is missing is a join between
        // them. The English gloss anchors it and the engine is asked for the rest.
        (0, _) => AnswerState::ViaEn,
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
