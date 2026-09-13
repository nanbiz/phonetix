//! What a reader gets for a word, through every branch of the cascade.
//!
//! The packs here are built the way a real one is, from lines of the dump, so that what is
//! tested is the whole path a word travels rather than the last step of it.

use lexcore::answer::{AnswerState, Lang, Provenance};
use lexcore::resolve::{look_up, read_in_context, Open};
use lexpack::{Builder, Entry, Kind, Pack, Sense};

fn lang(code: &str) -> Lang {
    Lang(code.to_string())
}

fn sense(gloss: &str) -> Sense {
    Sense {
        gloss: gloss.to_string(),
        marks: Vec::new(),
        example: None,
    }
}

fn word(lemma: &str, pos: &str, ipa: &str, glosses: &[&str]) -> Entry {
    Entry {
        lemma: lemma.to_string(),
        pos: pos.to_string(),
        tags: Vec::new(),
        ipa: vec![ipa.to_string()],
        senses: glosses.iter().map(|g| sense(g)).collect(),
        forms: Vec::new(),
    }
}

fn spanish() -> Vec<u8> {
    let mut pack = Builder::new("es", Kind::Lex, 0);
    pack.add(word("perro", "noun", "ˈpe.ro", &["dog"]), &["perros"])
        .unwrap();
    pack.add(
        word("camino", "noun", "kaˈmi.no", &["way, route"]),
        &["caminos"],
    )
    .unwrap();
    pack.add(word("banco", "noun", "ˈbaŋ.ko", &["bench"]), &[] as &[&str])
        .unwrap();
    pack.finish().unwrap()
}

fn german() -> Vec<u8> {
    let mut pack = Builder::new("de", Kind::Lex, 0);
    pack.add(word("Hund", "noun", "hʊnt", &["dog, hound"]), &["Hunde"])
        .unwrap();
    pack.add(
        word(
            "Weg",
            "noun",
            "veːk",
            &["route, way (to get from one place to another)"],
        ),
        &[] as &[&str],
    )
    .unwrap();
    pack.add(
        word("Weise", "noun", "ˈvaɪ̯zə", &["way, manner"]),
        &[] as &[&str],
    )
    .unwrap();
    // Two German words glossed "bench" and nothing to choose between them.
    pack.add(word("Bank", "noun", "baŋk", &["bench"]), &[] as &[&str])
        .unwrap();
    pack.add(
        word("Sitzbank", "noun", "ˈzɪt͡sbaŋk", &["bench"]),
        &[] as &[&str],
    )
    .unwrap();
    pack.finish().unwrap()
}

#[test]
fn a_lemma_that_joins_is_an_entry() {
    let es = spanish();
    let de = german();
    let (es, de) = (Pack::open(&es).unwrap(), Pack::open(&de).unwrap());
    let open = Open {
        source: Some(&es),
        target: Some(&de),
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("perro", &lang("es"), &lang("de"), &open);
    assert_eq!(got.state, AnswerState::Entry);
    assert_eq!(got.says, vec!["Hund"]);
    assert_eq!(got.glosses, vec!["dog"]);
    assert_eq!(got.ipa, vec!["ˈpe.ro"]);
    assert_eq!(
        got.lemma, None,
        "the word tapped is the lemma, so there is nothing to say"
    );
    assert_eq!(got.pos.as_deref(), Some("noun"));
    assert_eq!(
        got.provenance,
        Some(Provenance::Dictionary {
            pack: "lex-es".to_string()
        }),
        "the card never hides where an answer came from",
    );
}

#[test]
fn an_inflected_spelling_answers_through_its_lemma() {
    let es = spanish();
    let de = german();
    let (es, de) = (Pack::open(&es).unwrap(), Pack::open(&de).unwrap());
    let open = Open {
        source: Some(&es),
        target: Some(&de),
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("perros", &lang("es"), &lang("de"), &open);
    assert_eq!(got.state, AnswerState::Form);
    assert_eq!(
        got.lemma.as_deref(),
        Some("perro"),
        "a reader who tapped a form is owed it"
    );
    assert_eq!(got.says, vec!["Hund"]);
}

#[test]
fn a_gloss_of_several_terms_reaches_the_word_that_shares_most_of_them() {
    let es = spanish();
    let de = german();
    let (es, de) = (Pack::open(&es).unwrap(), Pack::open(&de).unwrap());
    let open = Open {
        source: Some(&es),
        target: Some(&de),
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("camino", &lang("es"), &lang("de"), &open);
    assert_eq!(
        got.says,
        vec!["Weg"],
        "Weise shares one term of the gloss and Weg shares two"
    );
    assert_eq!(
        got.state,
        AnswerState::Entry,
        "a word the gloss reached through fewer of its terms is a worse answer, not a second \
         one, and offering it beside the first would make every multi-term gloss ambiguous",
    );
}

#[test]
fn two_words_reached_equally_well_are_no_dictionary_answer_at_all() {
    // Bank and Sitzbank are both glossed "bench" and nothing in the data separates them.
    // Offering both would be the confident wrong answer this join is shaped to avoid, twice
    // over, so the dictionary says nothing and the English gloss stands as the anchor above
    // whatever the host's engine guesses.
    let es = spanish();
    let de = german();
    let (es, de) = (Pack::open(&es).unwrap(), Pack::open(&de).unwrap());
    let open = Open {
        source: Some(&es),
        target: Some(&de),
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("banco", &lang("es"), &lang("de"), &open);
    // The pack holds the word and the reader's own pack is open; what is missing is a join.
    assert_eq!(got.state, AnswerState::ViaEn);
    assert!(got.says.is_empty(), "reached {:?}", got.says);
    assert_eq!(
        got.glosses,
        vec!["bench"],
        "the anchor a reader is left with"
    );
    assert_eq!(got.ipa, vec!["ˈbaŋ.ko"]);
}

#[test]
fn a_language_read_in_itself_answers_with_its_own_senses() {
    let es = spanish();
    let es = Pack::open(&es).unwrap();
    let open = Open {
        source: Some(&es),
        target: Some(&es),
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("perro", &lang("es"), &lang("es"), &open);
    assert_eq!(got.state, AnswerState::Mono);
    assert_eq!(
        got.says,
        vec!["dog"],
        "its own senses are the answer, not a translation"
    );
}

#[test]
fn a_reader_of_english_needs_no_join_at_all() {
    // The source sense's gloss is already in the reader's language, which is the case with the
    // richest data and the one that needs one pack rather than two.
    let es = spanish();
    let es = Pack::open(&es).unwrap();
    let open = Open {
        source: Some(&es),
        target: None,
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("perro", &lang("es"), &lang("en"), &open);
    assert_eq!(got.state, AnswerState::Entry);
    assert_eq!(got.says, vec!["dog"]);
}

#[test]
fn a_word_the_pack_does_not_hold_is_a_miss_and_not_a_missing_pack() {
    let es = spanish();
    let de = german();
    let (es, de) = (Pack::open(&es).unwrap(), Pack::open(&de).unwrap());
    let open = Open {
        source: Some(&es),
        target: Some(&de),
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("murciélago", &lang("es"), &lang("de"), &open);
    assert_eq!(got.state, AnswerState::None);
    assert!(got.says.is_empty() && got.glosses.is_empty());
}

#[test]
fn no_pack_and_a_pronunciation_pack_are_different_answers() {
    // No pack at all, so nothing says what holds a pack's bytes here.
    let open = Open::<&[u8]> {
        source: None,
        target: None,
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    assert_eq!(
        look_up("perro", &lang("es"), &lang("de"), &open).state,
        AnswerState::NoPack,
    );
    let offered = Open::<&[u8]> {
        source: None,
        target: None,
        ipa_only: true,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    assert_eq!(
        look_up("perro", &lang("es"), &lang("de"), &offered).state,
        AnswerState::IpaOnly,
        "a reader with only the sounds is offered the rest rather than told there is nothing",
    );
}

#[test]
fn a_word_that_joins_nowhere_still_gives_its_sound_and_its_english() {
    // The reader's language has a pack, the word is in the source pack, and no sense of it
    // reaches anything: what is left is the pronunciation and the English gloss as an anchor,
    // which is what the card shows above a machine guess.
    let mut only = Builder::new("es", Kind::Lex, 0);
    only.add(
        word("ornitorrinco", "noun", "oɾ.ni.toˈrin.ko", &["platypus"]),
        &[] as &[&str],
    )
    .unwrap();
    let es = only.finish().unwrap();
    let de = german();
    let (es, de) = (Pack::open(&es).unwrap(), Pack::open(&de).unwrap());
    let open = Open {
        source: Some(&es),
        target: Some(&de),
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("ornitorrinco", &lang("es"), &lang("de"), &open);
    // The entry is here and the reader's own pack is open; what is missing is a join between
    // them, which is a different answer from having no pack for the pair at all.
    assert_eq!(got.state, AnswerState::ViaEn);
    assert!(got.says.is_empty());
    assert_eq!(got.glosses, vec!["platypus"]);
    assert_eq!(got.ipa, vec!["oɾ.ni.toˈrin.ko"]);
}

#[test]
fn the_applying_senses_example_comes_with_the_answer() {
    // One line of the word in use is worth more than a second gloss, and the dump has it: the
    // cascade was dropping it on the floor.
    let mut pack = Builder::new("es", Kind::Lex, 0);
    pack.add(
        Entry {
            lemma: "perro".into(),
            pos: "noun".into(),
            tags: Vec::new(),
            ipa: vec!["ˈpe.ro".into()],
            senses: vec![Sense {
                gloss: "dog".into(),
                marks: Vec::new(),
                example: Some("El perro ladra.".into()),
            }],
            forms: Vec::new(),
        },
        &[] as &[&str],
    )
    .unwrap();
    let bytes = pack.finish().unwrap();
    let es = Pack::open(&bytes).unwrap();
    let open = Open {
        source: Some(&es),
        target: None,
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("perro", &lang("es"), &lang("en"), &open);
    assert_eq!(got.example.as_deref(), Some("El perro ladra."));
}

#[test]
fn a_sense_with_no_example_invents_none() {
    let es = spanish();
    let es = Pack::open(&es).unwrap();
    let open = Open {
        source: Some(&es),
        target: None,
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    assert_eq!(
        look_up("perro", &lang("es"), &lang("en"), &open).example,
        None
    );
}

#[test]
fn a_spelling_that_is_two_words_offers_both_readings() {
    // "banco" in Spanish is a bench and a bank, and nothing about the word says which a reader
    // met. The cascade offers both rather than picking, which is what the homograph state is
    // for; picking would be the confident wrong answer in its oldest form.
    let mut source = Builder::new("es", Kind::Lex, 0);
    source
        .add(word("banco", "noun", "ˈbaŋ.ko", &["bench"]), &[] as &[&str])
        .unwrap();
    source
        .add(
            word("banco", "verb", "ˈbaŋ.ko", &["to bank"]),
            &[] as &[&str],
        )
        .unwrap();
    let es = source.finish().unwrap();
    let es = Pack::open(&es).unwrap();
    let open = Open {
        source: Some(&es),
        target: None,
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("banco", &lang("es"), &lang("en"), &open);
    assert_eq!(got.state, AnswerState::Homograph);
    assert_eq!(got.readings.len(), 2);
    assert_eq!(got.readings[0].pos.as_deref(), Some("noun"));
    assert_eq!(got.readings[0].says, vec!["bench"]);
    assert_eq!(got.readings[1].pos.as_deref(), Some("verb"));
    assert_eq!(got.readings[1].says, vec!["to bank"]);
}

#[test]
fn a_spelling_that_is_one_word_offers_no_choice() {
    let es = spanish();
    let es = Pack::open(&es).unwrap();
    let open = Open {
        source: Some(&es),
        target: None,
        ipa_only: false,
        accent: "",
        accent_pack: None,
        said: None,
        classifier: None,
    };
    let got = look_up("perro", &lang("es"), &lang("en"), &open);
    assert!(got.readings.is_empty(), "nothing to choose between");
    assert_eq!(got.state, AnswerState::Entry);
}

/// A word an accent has its own reading of comes back in that reading, unshifted.
///
/// This is the half of an accent that is data rather than a rule: a few thousand words a
/// dictionary tags for one country. Where it speaks, it is the last word, and the rule table
/// must not touch what it said. "cut" is the case that shows it: American data writes the
/// vowel [ɐ], and the American rule turns a standard [ɐ] into [ɚ] because a standard
/// transcription that carries one is a British r. Applied on top of the data it would make
/// the word rhyme with "curt".
#[test]
fn an_accent_with_a_word_of_its_own_says_it_that_way() {
    let mut base = Builder::new("en", Kind::Ipa, 0);
    for (lemma, ipa) in [("schedule", "ˈʃɛdjuːl"), ("cut", "kʌt")] {
        base.add::<&str>(
            Entry {
                lemma: lemma.into(),
                pos: String::new(),
                ipa: vec![ipa.into()],
                tags: Vec::new(),
                senses: Vec::new(),
                forms: Vec::new(),
            },
            &[],
        )
        .expect("the pack takes it");
    }
    let base = Pack::open(base.finish().expect("written")).expect("opens");

    let mut american = Builder::new("en-us", Kind::Ipa, 0);
    for (lemma, ipa) in [("schedule", "ˈskɛdʒuːl"), ("cut", "kɐt")] {
        american
            .add::<&str>(
                Entry {
                    lemma: lemma.into(),
                    pos: String::new(),
                    ipa: vec![ipa.into()],
                    tags: Vec::new(),
                    senses: Vec::new(),
                    forms: Vec::new(),
                },
                &[],
            )
            .expect("the pack takes it");
    }
    let american = Pack::open(american.finish().expect("written")).expect("opens");

    let standard = look_up(
        "schedule",
        &Lang("en".into()),
        &Lang("en".into()),
        &Open {
            source: Some(&base),
            target: Some(&base),
            ipa_only: false,
            accent: "",
            accent_pack: None,
            said: None,
            classifier: None,
        },
    );
    assert_eq!(standard.ipa, ["ˈʃɛdjuːl"]);

    let said = look_up(
        "schedule",
        &Lang("en".into()),
        &Lang("en".into()),
        &Open {
            source: Some(&base),
            target: Some(&base),
            ipa_only: false,
            accent: "en-us",
            accent_pack: Some(&american),
            said: None,
            classifier: None,
        },
    );
    assert_eq!(said.ipa, ["ˈskɛdʒuːl"]);
    // And the symbols come with it, since a card offers each sound of what it shows.
    assert_eq!(said.symbols.first().map(|s| s.token.as_str()), Some("ˈ"));

    // The one the rule would have shifted had it run over the data.
    let cut = look_up(
        "cut",
        &Lang("en".into()),
        &Lang("en".into()),
        &Open {
            source: Some(&base),
            target: Some(&base),
            ipa_only: false,
            accent: "en-us",
            accent_pack: Some(&american),
            said: None,
            classifier: None,
        },
    );
    assert_eq!(cut.ipa, ["kɐt"]);
}

/// A word the accent's pack says nothing about is left to the accent's rule.
///
/// Which is the point of having both. A pack for one country holds a few thousand words and a
/// language has hundreds of thousands, so nearly every word a reader meets is answered by the
/// rule, and an accent with a pack that stopped ruling the rest would be an accent that showed
/// up on one word in fifty.
#[test]
fn a_word_the_accents_pack_does_not_hold_is_said_by_its_rule() {
    let mut base = Builder::new("en", Kind::Ipa, 0);
    base.add::<&str>(
        Entry {
            lemma: "water".into(),
            pos: String::new(),
            ipa: vec!["ˈwɔːtɐ".into()],
            tags: Vec::new(),
            senses: Vec::new(),
            forms: Vec::new(),
        },
        &[],
    )
    .expect("the pack takes it");
    let base = Pack::open(base.finish().expect("written")).expect("opens");
    let empty = Pack::open(
        Builder::new("en-us", Kind::Ipa, 0)
            .finish()
            .expect("written"),
    )
    .expect("opens");

    let said = look_up(
        "water",
        &Lang("en".into()),
        &Lang("en".into()),
        &Open {
            source: Some(&base),
            target: Some(&base),
            ipa_only: false,
            accent: "en-us",
            accent_pack: Some(&empty),
            said: None,
            classifier: None,
        },
    );
    assert_eq!(said.ipa, ["ˈwɔːtɚ"]);
    // The symbols are of what is shown, not of what the standard pack held.
    assert_eq!(said.symbols.last().map(|s| s.token.as_str()), Some("ɚ"));

    // And an accent with no pack at all is still an accent: most of them have none.
    let ruled = look_up(
        "water",
        &Lang("en".into()),
        &Lang("en".into()),
        &Open {
            source: Some(&base),
            target: Some(&base),
            ipa_only: false,
            accent: "en-us",
            ..Default::default()
        },
    );
    assert_eq!(ruled.ipa, ["ˈwɔːtɚ"]);
}

/// A word capitalised by the page is still the word the dictionary holds.
///
/// A page capitalises for reasons of its own: the first word of a sentence, every word of a
/// heading, a list of labels in title case. Nearly all the text on a real app's screen is one
/// of those, and while the cascade compared spellings byte for byte, nearly all of it answered
/// nothing at all - the screen came back almost bare, which is what a reader saw.
#[test]
fn a_word_the_page_capitalised_is_the_same_word() {
    let es = spanish();
    let de = german();
    let (es, de) = (Pack::open(&es).unwrap(), Pack::open(&de).unwrap());
    let open = Open {
        source: Some(&es),
        target: Some(&de),
        ..Default::default()
    };
    let got = look_up("Perro", &lang("es"), &lang("de"), &open);
    assert_eq!(got.state, AnswerState::Entry);
    assert_eq!(got.says, vec!["Hund"]);
    assert_eq!(got.ipa, vec!["ˈpe.ro"]);
    // What the reader is looking at, not what the dictionary keys it under.
    assert_eq!(got.spelling, "Perro");
    // And it is not an inflected form of itself, which is what a card would otherwise say.
    assert_eq!(got.lemma, None);

    // A heading shouts, and that is still the same word.
    let shouted = look_up("CAMINO", &lang("es"), &lang("de"), &open);
    assert_eq!(shouted.says, vec!["Weg"]);
}

/// Case that belongs to the language is not the page's to undo.
///
/// German capitalises every noun, so "Bank" and "bank" are two different words wherever both
/// exist. The spelling as written is tried first for exactly this reason.
#[test]
fn a_language_that_capitalises_its_nouns_keeps_the_distinction() {
    let de = german();
    let de = Pack::open(&de).unwrap();
    let open = Open {
        source: Some(&de),
        target: Some(&de),
        ..Default::default()
    };
    let got = look_up("Bank", &lang("de"), &lang("de"), &open);
    assert_eq!(got.ipa, vec!["baŋk"]);
    assert_eq!(got.lemma, None, "the noun as the language writes it");
}

/// A reader who tapped an inflected spelling is told which form it is.
///
/// The lemma alone does not say it. "perros" and "perro" are two words on a card that gives
/// only the second, and a reader learning the language is left to work out the relation - which
/// is the thing they are trying to learn. The dump names the form and the pack now carries the
/// name, so the card can say it.
#[test]
fn a_form_says_what_form_it_is() {
    let mut pack = Builder::new("es", Kind::Lex, 0);
    pack.add::<&str>(
        Entry {
            lemma: "perro".into(),
            pos: "noun".into(),
            tags: Vec::new(),
            ipa: vec!["ˈpe.ro".into()],
            senses: vec![sense("dog")],
            forms: vec![lexpack::Form {
                spelling: "perros".into(),
                label: "plural".into(),
            }],
        },
        &[],
    )
    .expect("the pack takes it");
    let pack = Pack::open(pack.finish().expect("written")).expect("opens");
    let open = Open {
        source: Some(&pack),
        target: Some(&pack),
        ..Default::default()
    };

    let form = look_up("perros", &lang("es"), &lang("es"), &open);
    assert_eq!(form.state, AnswerState::Mono);
    assert_eq!(form.lemma.as_deref(), Some("perro"));
    assert_eq!(form.form.as_deref(), Some("plural"));

    // And the lemma itself is not a form of anything.
    let lemma = look_up("perro", &lang("es"), &lang("es"), &open);
    assert_eq!(lemma.lemma, None);
    assert_eq!(lemma.form, None);
}

/// A spelling that is several words is decided by the word before it, where that decides.
///
/// "book" is a noun and a verb. After "the" it is a noun; after "to" it is a verb. Neither
/// needs a model or a language this repository has data for: the parts of speech are in the
/// pack already, and a determiner is followed by a noun in every language that has both.
///
/// What it must not do is decide when it does not know. A reader handed the wrong word wearing
/// a dictionary's authority is worse off than one who was asked.
#[test]
fn the_word_before_decides_which_word_this_is() {
    let mut pack = Builder::new("en", Kind::Lex, 0);
    pack.add::<&str>(word("book", "verb", "bʊk", &["to reserve"]), &[])
        .expect("the pack takes it");
    pack.add::<&str>(word("book", "noun", "bʊk", &["a bound volume"]), &[])
        .expect("the pack takes it");
    pack.add::<&str>(word("the", "det", "ðə", &["the"]), &[])
        .expect("the pack takes it");
    pack.add::<&str>(word("to", "particle", "tuː", &["to"]), &[])
        .expect("the pack takes it");
    // A word that is itself two things says nothing about its neighbour.
    pack.add::<&str>(word("that", "det", "ðæt", &["that"]), &[])
        .expect("the pack takes it");
    pack.add::<&str>(word("that", "pron", "ðæt", &["that"]), &[])
        .expect("the pack takes it");
    let pack = Pack::open(pack.finish().expect("written")).expect("opens");
    let open = Open {
        source: Some(&pack),
        target: Some(&pack),
        ..Default::default()
    };

    let after_the = read_in_context("book", Some("the"), &lang("en"), &lang("en"), &open);
    assert_eq!(after_the.pos.as_deref(), Some("noun"));
    assert_ne!(
        after_the.state,
        AnswerState::Homograph,
        "decided, so the card leads with it rather than asking",
    );

    let after_to = read_in_context("book", Some("to"), &lang("en"), &lang("en"), &open);
    assert_eq!(after_to.pos.as_deref(), Some("verb"));

    // Both readings are still there: a decision is not a claim that the other word does not
    // exist, and the card shows it under the grammar line.
    assert_eq!(after_the.readings.len(), 2);

    // And where nothing decides, the reader is asked.
    let alone = look_up("book", &lang("en"), &lang("en"), &open);
    assert_eq!(alone.state, AnswerState::Homograph);
    let after_ambiguous = read_in_context("book", Some("that"), &lang("en"), &lang("en"), &open);
    assert_eq!(
        after_ambiguous.state,
        AnswerState::Homograph,
        "a neighbour that is itself two words says nothing",
    );
    let after_unknown = read_in_context("book", Some("qwerty"), &lang("en"), &lang("en"), &open);
    assert_eq!(after_unknown.state, AnswerState::Homograph);
}

/// The trained classifier decides, and outranks the rule about parts of speech.
///
/// Both signals answer the same question and they can disagree: the rule generalises about a
/// determiner being followed by a noun, and the classifier was trained on how this word is
/// really used. DR-1 says the first confident signal decides and names the classifier first.
#[test]
fn what_the_training_says_outranks_the_rule() {
    let mut pack = Builder::new("en", Kind::Lex, 0);
    pack.add::<&str>(word("record", "noun", "ˈɹɛkɚd", &["a bound account"]), &[])
        .expect("the pack takes it");
    pack.add::<&str>(word("record", "verb", "ɹəˈkɔːɹd", &["to write down"]), &[])
        .expect("the pack takes it");
    pack.add::<&str>(word("to", "particle", "tuː", &["to"]), &[])
        .expect("the pack takes it");
    let pack = Pack::open(pack.finish().expect("written")).expect("opens");

    // A classifier that says the opposite of what the rule would: after "to" the rule prefers
    // a verb, and this says the noun.
    let trained = a_classifier_saying(&[("record", "to", "ˈɹɛkɚd", "noun")]);
    let classifier = lexcore::homographs::Classifier::open(&trained).expect("opens");

    let by_rule = read_in_context(
        "record",
        Some("to"),
        &lang("en"),
        &lang("en"),
        &Open {
            source: Some(&pack),
            target: Some(&pack),
            ..Default::default()
        },
    );
    assert_eq!(by_rule.pos.as_deref(), Some("verb"), "the rule alone");

    let by_training = read_in_context(
        "record",
        Some("to"),
        &lang("en"),
        &lang("en"),
        &Open {
            source: Some(&pack),
            target: Some(&pack),
            said: None,
            classifier: Some(&classifier),
            ..Default::default()
        },
    );
    assert_eq!(
        by_training.pos.as_deref(),
        Some("noun"),
        "the training outranks the rule",
    );
    assert_ne!(by_training.state, AnswerState::Homograph);
}

/// One classifier, in the shape the tool writes: one word, two readings, one rule.
fn a_classifier_saying(rules: &[(&str, &str, &str, &str)]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(b"PXHG");
    out.push(1);
    lexpack::varint::put(&mut out, rules.len() as u64);
    for (spelling, after, pronunciation, label) in rules {
        lexpack::varint::put_str(&mut out, spelling);
        lexpack::varint::put(&mut out, 2); // two readings, so it is a homograph
        lexpack::varint::put(&mut out, 0);
        lexpack::varint::put_str(&mut out, "chosen");
        lexpack::varint::put_str(&mut out, label);
        lexpack::varint::put_str(&mut out, pronunciation);
        lexpack::varint::put(&mut out, 0);
        lexpack::varint::put_str(&mut out, "other");
        lexpack::varint::put_str(&mut out, "");
        lexpack::varint::put_str(&mut out, "");
        lexpack::varint::put(&mut out, 0);
        lexpack::varint::put(&mut out, 1); // one rule
        lexpack::varint::put(&mut out, 900);
        lexpack::varint::put_str(&mut out, &format!("-1:{after}"));
        lexpack::varint::put(&mut out, 0);
    }
    out
}

/// The real classifier, on the word that shows why a rule about parts of speech is not enough.
///
/// "read" is the same spelling, the same part of speech and two pronunciations: after "had"
/// it is [ɹɛd] and after "should" it is [ɹiːd]. No rule about determiners and verbs can tell
/// those apart, and a reader shown the wrong one is being told the word sounds like a word it
/// does not.
///
/// The words are the list's own: it trained "-1:had" for the past and "-1:should" for the
/// present, and a check that asked about a word the training never saw would be asking whether
/// this repository can guess rather than whether the classifier works.
#[test]
fn the_trained_list_tells_read_from_read() {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../assets/homographs/en.hg");
    let bytes = std::fs::read(path)
        .expect("the classifiers are built by tools/build_homographs.py; run it");
    let classifier = lexcore::homographs::Classifier::open(&bytes).expect("opens");

    let mut pack = Builder::new("en", Kind::Lex, 0);
    pack.add::<&str>(word("read", "verb", "ˈɹɛd", &["past of read"]), &[])
        .expect("the pack takes it");
    pack.add::<&str>(word("read", "verb", "ˈɹiːd", &["to take in writing"]), &[])
        .expect("the pack takes it");
    let pack = Pack::open(pack.finish().expect("written")).expect("opens");
    let open = Open {
        source: Some(&pack),
        target: Some(&pack),
        said: None,
        classifier: Some(&classifier),
        ..Default::default()
    };

    let past = read_in_context("read", Some("had"), &lang("en"), &lang("en"), &open);
    assert_eq!(past.ipa, ["ˈɹɛd"], "after 'had' it is the past");
    assert_ne!(past.state, AnswerState::Homograph, "decided, so not asked");

    let present = read_in_context("read", Some("should"), &lang("en"), &lang("en"), &open);
    assert_eq!(present.ipa, ["ˈɹiːd"], "after 'should' it is the present");

    // And where the list has nothing to say, the reader is still asked rather than guessed at.
    let alone = read_in_context("read", Some("qwerty"), &lang("en"), &lang("en"), &open);
    assert_eq!(alone.state, AnswerState::Homograph);
}

#[test]
fn the_sentences_own_translation_says_which_word_it_is() {
    // A spelling that is two words, in a sentence somebody is already translating. The engine
    // read the whole sentence to produce that translation, which is context no table here has,
    // so what it came back with decides: a translation carrying "bank" is the bank, one
    // carrying "bench" is the bench, and one carrying both says nothing about which.
    let mut source = Builder::new("es", Kind::Lex, 0);
    source
        .add(word("banco", "noun", "ˈbaŋ.ko", &["bench"]), &[] as &[&str])
        .unwrap();
    source
        .add(word("banco", "verb", "ˈbaŋ.ko", &["bank"]), &[] as &[&str])
        .unwrap();
    let es = source.finish().unwrap();
    let es = Pack::open(&es).unwrap();
    let read = |said: Option<&str>| {
        let open = Open {
            source: Some(&es),
            target: None,
            ipa_only: false,
            accent: "",
            accent_pack: None,
            said,
            classifier: None,
        };
        look_up("banco", &lang("es"), &lang("en"), &open)
    };

    // Nothing translated: the cascade offers both, as it always has.
    assert_eq!(read(None).state, AnswerState::Homograph);

    let bench = read(Some("He sat on the bench in the square."));
    assert_eq!(
        bench.state,
        AnswerState::Entry,
        "a sentence that decides is not a question to put to the reader",
    );
    assert_eq!(bench.says, vec!["bench"]);

    let bank = read(Some("He went to the bank on the corner."));
    assert_eq!(bank.state, AnswerState::Entry);
    assert_eq!(bank.says, vec!["bank"]);

    // Both readings in one sentence is the translation saying nothing about which this word
    // was, which is different from it saying the first one.
    let neither = read(Some("The bank has a bench outside it."));
    assert_eq!(neither.state, AnswerState::Homograph);

    // And a word of a reading inside a longer word decides nothing: "bench" is not in
    // "benchmark", and matching half a word would be a signal that decides by accident.
    let unrelated = read(Some("They published a benchmark of the banking system."));
    assert_eq!(unrelated.state, AnswerState::Homograph);
}
