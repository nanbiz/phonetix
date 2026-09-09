//! What a reader gets for a word, through every branch of the cascade.
//!
//! The packs here are built the way a real one is, from lines of the dump, so that what is
//! tested is the whole path a word travels rather than the last step of it.

use lexcore::answer::{AnswerState, Lang, Provenance};
use lexcore::resolve::{look_up, Open};
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
    };
    let got = look_up("banco", &lang("es"), &lang("de"), &open);
    assert_eq!(got.state, AnswerState::IpaOnly);
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
    };
    assert_eq!(
        look_up("perro", &lang("es"), &lang("de"), &open).state,
        AnswerState::NoPack,
    );
    let offered = Open::<&[u8]> {
        source: None,
        target: None,
        ipa_only: true,
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
    };
    let got = look_up("ornitorrinco", &lang("es"), &lang("de"), &open);
    assert_eq!(got.state, AnswerState::IpaOnly);
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
    };
    let got = look_up("perro", &lang("es"), &lang("en"), &open);
    assert!(got.readings.is_empty(), "nothing to choose between");
    assert_eq!(got.state, AnswerState::Entry);
}
