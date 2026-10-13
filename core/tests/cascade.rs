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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
    };
    let got = look_up("perros", &lang("es"), &lang("de"), &open);
    assert_eq!(got.state, AnswerState::Form);
    assert_eq!(
        got.lemma.as_deref(),
        Some("perro"),
        "a reader who tapped a form is owed it"
    );
    assert_eq!(got.says, vec!["Hund"]);
    assert!(
        got.ipa.is_empty() && got.symbols.is_empty(),
        "perros is not said the way perro is, so it is left for the voice: {:?}",
        got.ipa
    );
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
        others: None,
        defines: None,
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
fn two_words_reached_equally_well_are_answered_with_one_of_them() {
    // Bank and Sitzbank are both glossed "bench" and the join alone does not separate them.
    // Left unanswered, the page drew the English gloss over a text being read in German, so
    // the tie goes to the ranking a word typed into the panel gets, and the reader is handed
    // one German word - the same one every time.
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
        others: None,
        defines: None,
    };
    let got = look_up("banco", &lang("es"), &lang("de"), &open);
    assert_eq!(got.state, AnswerState::Entry);
    assert_eq!(got.says.len(), 1, "reached {:?}", got.says);
    assert!(
        ["Bank", "Sitzbank"].contains(&got.says[0].as_str()),
        "reached {:?}",
        got.says
    );
    assert_eq!(
        got.says,
        look_up("banco", &lang("es"), &lang("de"), &open).says
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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
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
        others: None,
        defines: None,
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
            others: None,
            defines: None,
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
            others: None,
            defines: None,
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
            others: None,
            defines: None,
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
            others: None,
            defines: None,
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

/// The card names the accent of the transcription it shows, never just the one chosen.
///
/// Scottish English is a few thousand words a dictionary tagged and no rule. Night is not one
/// of them: its readings are labelled British (Southern Standard), General American and
/// Received Pronunciation together, and one is labelled nothing. A Scottish reader is shown the
/// best labelled one, named by the language's default accent, which it also is; never the
/// unlabelled one, which is not the general reading just because nobody named it. A word with
/// no labelled reading at all is the dictionary's own: shifted by a rule where the accent has
/// one, and otherwise named by no accent.
#[test]
fn the_accent_named_is_the_one_the_reading_is_in() {
    let entry = |lemma: &str, ipa: &[&str], tags: &[&str]| Entry {
        lemma: lemma.into(),
        pos: String::new(),
        ipa: ipa.iter().map(|it| it.to_string()).collect(),
        tags: tags.iter().map(|it| it.to_string()).collect(),
        senses: Vec::new(),
        forms: Vec::new(),
    };
    let mut base = Builder::new("en", Kind::Ipa, 0);
    base.add::<&str>(
        entry(
            "night",
            &["ˈnɑjt", "naɪ̯t", "ˈnɐɪ̯t"],
            &["accent:0:en-gb:1", "accent:1:en-gb:0", "accent:1:en-us:0"],
        ),
        &[],
    )
    .expect("the pack takes it");
    base.add::<&str>(
        entry(
            "water",
            &["ˈwɔːtɐ", "ˈwɔː.tə"],
            &["accent:0:?", "accent:1:en-gb:0"],
        ),
        &[],
    )
    .expect("the pack takes it");
    let base = Pack::open(base.finish().expect("written")).expect("opens");
    let mut scottish = Builder::new("en-gb-scotland", Kind::Ipa, 0);
    scottish
        .add::<&str>(entry("water", &["ˈwɔ.təɾ"], &[]), &[])
        .expect("the pack takes it");
    let scottish = Pack::open(scottish.finish().expect("written")).expect("opens");

    let read = |word: &str, accent: &str, pack: Option<&Pack<Vec<u8>>>| {
        let answer = look_up(
            word,
            &Lang("en".into()),
            &Lang("en".into()),
            &Open {
                source: Some(&base),
                target: Some(&base),
                accent,
                accent_pack: pack,
                ..Default::default()
            },
        );
        (answer.ipa[0].clone(), answer.said_in)
    };
    let some = |it: &str| Some(it.to_string());
    // Its own pack holds water.
    assert_eq!(
        read("water", "en-gb-scotland", Some(&scottish)),
        ("ˈwɔ.təɾ".into(), some("en-gb-scotland"))
    );
    // Night it does not: the reading labelled both General American and Received
    // Pronunciation, named American.
    assert_eq!(
        read("night", "en-gb-scotland", Some(&scottish)),
        ("naɪ̯t".into(), some("en-us"))
    );
    // A British reader gets it by its best British label rather than the weaker one, and an
    // American reader by the American one.
    assert_eq!(read("night", "en-gb", None), ("naɪ̯t".into(), some("en-gb")));
    assert_eq!(read("night", "en-us", None), ("naɪ̯t".into(), some("en-us")));
    // Water's lead reading carries only a note: American shifts it by its rule, while
    // Australian has none and is shown the British reading, named British.
    assert_eq!(
        read("water", "en-us", None),
        ("ˈwɔːtɚ".into(), some("en-us"))
    );
    assert_eq!(
        read("water", "en-au", None),
        ("ˈwɔː.tə".into(), some("en-gb"))
    );
}

/// A spelling filed as another word's alternative form is that word in the kind the note is.
///
/// "popup" is a noun in the dictionary, "alternative form of pop-up", and pop-up is an
/// adjective first: the card on "popup" leads with pop-up the noun.
#[test]
fn an_alternative_form_is_the_word_in_its_own_kind() {
    let entry = |lemma: &str, pos: &str, gloss: &str, marks: &[&str]| Entry {
        lemma: lemma.into(),
        pos: pos.into(),
        ipa: Vec::new(),
        tags: Vec::new(),
        senses: vec![lexpack::Sense {
            gloss: gloss.into(),
            marks: marks.iter().map(|it| it.to_string()).collect(),
            ..Default::default()
        }],
        forms: Vec::new(),
    };
    let mut en = Builder::new("en", Kind::Ipa, 0);
    en.add::<&str>(
        entry("popup", "noun", "Alternative form of pop-up.", &["alt-of"]),
        &[],
    )
    .expect("the pack takes it");
    en.add::<&str>(
        entry(
            "pop-up",
            "adj",
            "Coming into view suddenly from a concealed position.",
            &[],
        ),
        &["popup"],
    )
    .expect("the pack takes it");
    en.add::<&str>(
        entry("pop-up", "noun", "A pop-up advertisement.", &[]),
        &["popup"],
    )
    .expect("the pack takes it");
    let en = Pack::open(en.finish().expect("written")).expect("opens");
    let answer = look_up(
        "popup",
        &Lang("en".into()),
        &Lang("en".into()),
        &Open {
            source: Some(&en),
            target: Some(&en),
            ..Default::default()
        },
    );
    assert_eq!(answer.pos.as_deref(), Some("noun"));
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
            others: None,
            defines: None,
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
    // "benchmark" nor "bank" in "bankrupt", and matching half a word would be a signal that
    // decides by accident. An inflection is not half a word: that is below.
    let unrelated = read(Some("They published a benchmark of the bankrupt system."));
    assert_eq!(unrelated.state, AnswerState::Homograph);
}

/// What a reading means, looked for the way a translated sentence writes it.
///
/// A reader of English is handed the dictionary's own glosses, and a sentence holds none of
/// them as they stand: French "est" is "third-person singular present indicative of être",
/// and the sentence says "is". The form means what the word it points at means, and that
/// word's "to be" is written "is" in the sentence. "court" is "short" and a form of "courir",
/// "to run", which a sentence writes "runs". And "estre", an old spelling of "être", also
/// comes back as "is": two readings reaching the sentence through the same word agree, which
/// is not the sentence failing to choose.
#[test]
fn a_form_is_found_in_the_sentence_as_what_its_word_means() {
    let mut source = Builder::new("fr", Kind::Lex, 0);
    for entry in [
        word("est", "adj", "ɛst", &["east"]),
        word("est", "noun", "ɛst", &["east"]),
        word(
            "est",
            "verb",
            "ɛ",
            &["third-person singular present indicative of être"],
        ),
        word("estre", "verb", "ɛtʁ", &["archaic spelling of être"]),
        word("être", "verb", "ɛtʁ", &["to be"]),
        word("court", "adj", "kuʁ", &["short"]),
        word(
            "court",
            "verb",
            "kuʁ",
            &["third-person singular present indicative of courir"],
        ),
        word("courir", "verb", "ku.ʁiʁ", &["to run", "to hurry; to rush"]),
    ] {
        let forms: &[&str] = if entry.lemma == "estre" {
            &["est"]
        } else {
            &[]
        };
        source.add(entry, forms).unwrap();
    }
    let fr = source.finish().unwrap();
    let fr = Pack::open(&fr).unwrap();
    let read = |spelling: &str, said: &str| {
        let open = Open {
            source: Some(&fr),
            said: Some(said),
            ..Open::default()
        };
        look_up(spelling, &lang("fr"), &lang("en"), &open)
    };

    let is = read("est", "The house is big.");
    assert_ne!(is.state, AnswerState::Homograph, "{is:?}");
    assert_eq!(is.pos.as_deref(), Some("verb"));
    let east = read("est", "He lives in the east of the city.");
    assert_ne!(east.pos.as_deref(), Some("verb"), "{east:?}");

    let runs = read("court", "The cat runs in the park.");
    assert_eq!(runs.pos.as_deref(), Some("verb"), "{runs:?}");
    let short = read("court", "The path is short.");
    assert_eq!(short.pos.as_deref(), Some("adj"), "{short:?}");
    assert_ne!(short.state, AnswerState::Homograph);
}

/// One word with several senses, drawn as the one its translated line is about.
///
/// "banco" is decided - it is the noun - but the noun is a bank and a bench, and the
/// dictionary lists the bank first. Drawn from the first sense alone, a line about a bench
/// says "bank". The page asks for the line; with it in hand the bench is drawn, and a line
/// that holds neither leaves the dictionary's order alone.
#[test]
fn a_word_is_drawn_in_the_sense_its_line_is_about() {
    use lexcore::annotate::{annotate, complete};
    use lexcore::answer::{AnnotateOptions, EngineResult, InlineMode, Need, TextRun};
    let mut source = Builder::new("es", Kind::Lex, 0);
    source
        .add(
            word(
                "banco",
                "noun",
                "ˈbaŋ.ko",
                &["bank (financial institution)", "bench", "pew"],
            ),
            &[] as &[&str],
        )
        .unwrap();
    let es = source.finish().unwrap();
    let es = Pack::open(&es).unwrap();
    let open = Open {
        source: Some(&es),
        ..Open::default()
    };
    let options = AnnotateOptions {
        mode: InlineMode::Meaning,
        density: 1,
        narrow: false,
        hide_stress: false,
        accent: None,
        seen: Vec::new(),
        counts: Default::default(),
        seed: 0,
        accents: Default::default(),
    };
    let drawn = |said: &str| {
        let runs = [TextRun {
            id: 1,
            text: "banco".to_string(),
            lang_hint: None,
        }];
        let (mut tokens, misses) = annotate(&runs, &lang("es"), &lang("en"), &open, &options);
        assert_eq!(tokens[0].gloss.as_deref(), Some("bank"));
        assert!(
            misses.iter().any(|miss| miss.need == Need::Sense),
            "a word whose senses draw different words asks for its line"
        );
        let results = [EngineResult {
            token_index: 0,
            gloss: None,
            ipa: None,
            sentence: Some(said.to_string()),
            engine: "test".to_string(),
        }];
        complete(&mut tokens, &results, &options, &lang("en"), &open);
        (tokens[0].gloss.clone(), tokens[0].provenance.clone())
    };
    let (bench, from) = drawn("I sat on the park bench.");
    assert_eq!(bench.as_deref(), Some("bench"));
    assert!(matches!(from, Some(Provenance::Dictionary { .. })));
    assert_eq!(
        drawn("The bank closed my account.").0.as_deref(),
        Some("bank")
    );
    assert_eq!(drawn("It was raining.").0.as_deref(), Some("bank"));
}

/// The word for something, out of the dictionaries alone.
///
/// "dog" typed by a reader of English reaches the Spanish words glossed "dog", and the one a
/// Spanish speaker says comes first: the plain word before the poetic one, a word whose first
/// sense it is before one where it is a later sense, and the commoner of two by how often
/// each is met. "Hund" typed by a reader of German goes through German first.
#[test]
fn the_dictionaries_give_the_word_for_something() {
    use lexcore::resolve::word_for;
    let mut source = Builder::new("es", Kind::Lex, 0);
    let mut perro = word(
        "perro",
        "noun",
        "ˈpe.ro",
        &["dog (the species (Canis familiaris))"],
    );
    perro.tags.push("count:90000".to_string());
    let mut can = word("can", "noun", "kan", &["dog"]);
    can.senses[0].marks.push("poetic".to_string());
    let mut caja = word("caja", "noun", "ˈka.xa", &["box", "bank"]);
    caja.tags.push("count:500000".to_string());
    let mut banco = word("banco", "noun", "ˈbaŋ.ko", &["bank", "bench"]);
    banco.tags.push("count:40000".to_string());
    for entry in [can, perro, caja, banco] {
        source.add(entry, &[] as &[&str]).unwrap();
    }
    let es = source.finish().unwrap();
    let es = Pack::open(&es).unwrap();
    let mut german = Builder::new("de", Kind::Lex, 0);
    german
        .add(
            word("Hund", "noun", "hʊnt", &["dog, hound"]),
            &[] as &[&str],
        )
        .unwrap();
    let de = german.finish().unwrap();
    let de = Pack::open(&de).unwrap();

    assert_eq!(word_for("dog", &lang("en"), &es, None)[0], "perro");
    assert_eq!(word_for("bank", &lang("en"), &es, None)[0], "banco");
    assert_eq!(word_for("Hund", &lang("de"), &es, Some(&de))[0], "perro");
    assert!(word_for("Hund", &lang("de"), &es, None).is_empty());
}

/// A dictionary as it ships, turned into a pack where the product runs.
///
/// The maps a reader gets are `{word: how it is said}`, gzipped, and the cascade reads packs.
/// Doing that conversion on the device is what lets every language travel with the product
/// rather than be fetched: this is the check that what comes out is a pack the reader reads,
/// with the words that went in.
#[test]
fn a_shipped_dictionary_becomes_a_pack_that_answers() {
    use std::io::Write;
    let json = r#"{"perro":"ˈpe.ro","camino":"ka.ˈmi.no","silla":"ˈsi.ʝa","":"ignored"}"#;
    let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    gz.write_all(json.as_bytes())
        .expect("the fixture compresses");
    let gzipped = gz.finish().expect("the fixture compresses");

    let bytes = lexcore::packing::ipa_pack("es", &gzipped, 0).expect("the pack is built");
    let pack = lexpack::Pack::open(bytes).expect("the pack opens");
    assert_eq!(pack.lang(), "es");
    assert_eq!(pack.lookup("perro")[0].ipa, vec!["ˈpe.ro".to_string()]);
    assert_eq!(pack.lookup("silla")[0].ipa, vec!["ˈsi.ʝa".to_string()]);
    assert!(
        pack.lookup("nada").is_empty(),
        "a word it does not hold is absent"
    );
}

/// Both: what a word means, and how to say *that*.
///
/// The pronunciation a reader is shown in this mode is of the word they are being handed, not
/// of the word on the page - a reader shown "bench" wants to know how to say "bench" - which
/// means looking it up in the language they read into.
#[test]
fn both_says_how_the_translation_is_said() {
    use lexcore::annotate::annotate;
    use lexcore::answer::{AnnotateOptions, InlineMode, TextRun};

    let source = Pack::open(spanish()).expect("the Spanish pack opens");
    // The language read into, as the product carries it: a word and how it is said.
    let mut into = Builder::new("en", Kind::Ipa, 0);
    let no_forms: [&str; 0] = [];
    into.add(word("dog", "", "dɒɡ", &[]), &no_forms).unwrap();
    into.add(word("way", "", "weɪ", &[]), &no_forms).unwrap();
    let target = Pack::open(into.finish().expect("the English pack is written"))
        .expect("the English pack opens");

    let open = Open {
        source: Some(&source),
        target: Some(&target),
        ..Open::default()
    };
    let (tokens, _) = annotate(
        &[TextRun {
            id: 1,
            text: "El perro corre por el camino".to_string(),
            lang_hint: None,
        }],
        &lang("es"),
        &lang("en"),
        &open,
        &AnnotateOptions {
            mode: InlineMode::Both,
            density: 1,
            narrow: false,
            hide_stress: false,
            accent: None,
            seen: Vec::new(),
            counts: Default::default(),
            seed: 0,
            accents: Default::default(),
        },
    );
    let perro = tokens
        .iter()
        .find(|token| token.spelling == "perro")
        .expect("the word is a token");
    assert_eq!(perro.gloss.as_deref(), Some("dog"));
    assert_eq!(
        perro.gloss_ipa.as_deref(),
        Some("dɒɡ"),
        "how to say the answer"
    );
    let camino = tokens
        .iter()
        .find(|token| token.spelling == "camino")
        .expect("the word is a token");
    // A gloss is a headword and sometimes a phrase around it; the sound is of the head.
    assert_eq!(camino.gloss.as_deref(), Some("way, route"));
    assert_eq!(camino.gloss_ipa.as_deref(), Some("weɪ"));
}

/// A gloss written with its article is said as the word it is.
///
/// Dictionaries write a verb as "to bank" and a noun as "a road". The entry for how to say it
/// is under "bank" and "road", so a gloss looked up whole found nothing and the card showed
/// the meaning with no way to say it.
#[test]
fn a_gloss_with_its_article_is_said_as_the_word() {
    use lexcore::annotate::annotate;
    use lexcore::answer::{AnnotateOptions, InlineMode, TextRun};

    let mut from = Builder::new("es", Kind::Lex, 0);
    from.add(
        word("calle", "noun", "ˈka.ʝe", &["a street"]),
        &[] as &[&str],
    )
    .unwrap();
    let source = Pack::open(from.finish().unwrap()).expect("the Spanish pack opens");
    let mut into = Builder::new("en", Kind::Ipa, 0);
    let no_forms: [&str; 0] = [];
    into.add(word("street", "", "stɹiːt", &[]), &no_forms)
        .unwrap();
    let target = Pack::open(into.finish().unwrap()).expect("the English pack opens");

    let open = Open {
        source: Some(&source),
        target: Some(&target),
        ..Open::default()
    };
    let (tokens, _) = annotate(
        &[TextRun {
            id: 1,
            text: "la calle".to_string(),
            lang_hint: None,
        }],
        &lang("es"),
        &lang("en"),
        &open,
        &AnnotateOptions {
            mode: InlineMode::Both,
            density: 1,
            narrow: false,
            hide_stress: false,
            accent: None,
            seen: Vec::new(),
            counts: Default::default(),
            seed: 0,
            accents: Default::default(),
        },
    );
    let calle = tokens
        .iter()
        .find(|token| token.spelling == "calle")
        .expect("the word is a token");
    assert_eq!(calle.gloss.as_deref(), Some("a street"));
    assert_eq!(calle.gloss_ipa.as_deref(), Some("stɹiːt"));
}

/// What a word is drawn as over the page, from dictionaries as Wiktionary writes them.
///
/// The small packs the rest of these tests build hold one tidy sense per word, and every one of
/// them passed while a real dictionary drew the Spanish article as "masculine…", a dog as "dog
/// (the species Canis familiaris…" and "camino" as "to walk": the entry that is the word lost to
/// one that only reaches it as an inflection, and a definition written for a card was put over
/// the page as it stood.
#[test]
fn a_real_dictionarys_words_are_drawn_as_the_words_they_mean() {
    use lexcore::annotate::annotate;
    use lexcore::answer::{AnnotateOptions, InlineMode, TextRun};

    let mut from = Builder::new("es", Kind::Lex, 0);
    // In the order the dump lists them, which is the order that went wrong: "caminar" comes
    // before "camino", and "camino" is one of its forms.
    from.add(
        word("caminar", "verb", "kamiˈnaɾ", &["to walk"]),
        &["camino"],
    )
    .unwrap();
    from.add(
        word("camino", "noun", "kaˈmino", &["way, route", "road"]),
        &[] as &[&str],
    )
    .unwrap();
    from.add(
        word(
            "el",
            "article",
            "el",
            &["masculine singular definite article; the"],
        ),
        &[] as &[&str],
    )
    .unwrap();
    from.add(
        word(
            "perro",
            "noun",
            "ˈpero",
            &["dog (the species Canis familiaris, domesticated for thousands of years)"],
        ),
        &[] as &[&str],
    )
    .unwrap();
    from.add(
        word(
            "banco",
            "noun",
            "ˈbanko",
            &["bank (financial institution)", "bench"],
        ),
        &[] as &[&str],
    )
    .unwrap();
    from.add(
        word(
            "del",
            "contraction",
            "del",
            &["of the, from the (+ a masculine noun in singular)."],
        ),
        &[] as &[&str],
    )
    .unwrap();
    // "es" is the plural of the letter "e" and a form of "ser"; "y" is a letter and "and".
    from.add(
        word(
            "e",
            "noun",
            "e",
            &["The name of the Latin script letter E/e."],
        ),
        &["es"],
    )
    .unwrap();
    from.add(
        word(
            "ser",
            "verb",
            "ˈseɾ",
            &["to be (essentially or identified as)"],
        ),
        &["es"],
    )
    .unwrap();
    from.add(
        word(
            "y",
            "character",
            "ʝ",
            &["The twenty-sixth letter of the Spanish alphabet, called ye or i griega."],
        ),
        &[] as &[&str],
    )
    .unwrap();
    from.add(word("y", "conj", "i", &["and"]), &[] as &[&str])
        .unwrap();
    // The article reaches "los" as a form; the pronoun is "los" and opens with a note.
    from.add(
        word(
            "el",
            "article",
            "el",
            &["masculine singular definite article; the"],
        ),
        &["los"],
    )
    .unwrap();
    let mut pronoun = word("los", "pron", "los", &["accusative of ellos; them"]);
    pronoun.senses[0].marks = vec!["accusative".to_string(), "form-of".to_string()];
    from.add(pronoun, &[] as &[&str]).unwrap();
    let source = Pack::open(from.finish().unwrap()).expect("the pack opens");
    let open = Open {
        source: Some(&source),
        ..Open::default()
    };
    let (tokens, _) = annotate(
        &[TextRun {
            id: 1,
            text: "el perro en el camino del banco es y los".to_string(),
            lang_hint: None,
        }],
        &lang("es"),
        &lang("en"),
        &open,
        &AnnotateOptions {
            mode: InlineMode::Meaning,
            density: 1,
            narrow: false,
            hide_stress: false,
            accent: None,
            seen: Vec::new(),
            counts: Default::default(),
            seed: 0,
            accents: Default::default(),
        },
    );
    let drawn = |spelling: &str| {
        tokens
            .iter()
            .find(|token| token.spelling == spelling)
            .and_then(|token| token.gloss.clone())
            .unwrap_or_default()
    };
    assert_eq!(
        drawn("el"),
        "the",
        "the word, not the grammar note about it"
    );
    assert_eq!(
        drawn("perro"),
        "dog",
        "the word, not the parenthesis explaining it"
    );
    assert_eq!(drawn("banco"), "bank");
    assert_eq!(drawn("camino"), "way, route");
    assert_eq!(
        drawn("del"),
        "of the, from the",
        "and no stray point where a parenthesis was"
    );
    assert_eq!(
        drawn("es"),
        "to be",
        "the verb, not the letter it is also the plural of"
    );
    assert_eq!(drawn("y"), "and", "the conjunction, not the letter");
    assert_eq!(
        drawn("los"),
        "the",
        "the article, which the pronoun's note does not outrank"
    );

    // And with nothing before it to decide between them, which is where a word met as the
    // form of another had been winning: the entry that is the word comes first.
    let (alone, _) = annotate(
        &[TextRun {
            id: 1,
            text: "camino".to_string(),
            lang_hint: None,
        }],
        &lang("es"),
        &lang("en"),
        &open,
        &AnnotateOptions {
            mode: InlineMode::Meaning,
            density: 1,
            narrow: false,
            hide_stress: false,
            accent: None,
            seen: Vec::new(),
            counts: Default::default(),
            seed: 0,
            accents: Default::default(),
        },
    );
    assert_eq!(
        alone[0].gloss.as_deref(),
        Some("way, route"),
        "the entry that is the word, before one it is a form of"
    );
}

/// A note about grammar that ends with the meaning, and a sentence that starts with a capital.
#[test]
fn the_meaning_after_a_grammar_note_and_a_word_that_starts_a_sentence() {
    use lexcore::annotate::annotate;
    use lexcore::answer::{AnnotateOptions, InlineMode, TextRun};

    let mut german = Builder::new("de", Kind::Lex, 0);
    german
        .add(
            word(
                "der",
                "article",
                "deːr",
                &["nominative masculine singular definite article, the"],
            ),
            &[] as &[&str],
        )
        .unwrap();
    german
        .add(
            word(
                "dem",
                "article",
                "deːm",
                &["dative masculine/neuter singular of der: the"],
            ),
            &[] as &[&str],
        )
        .unwrap();
    let mut french = Builder::new("fr", Kind::Lex, 0);
    french
        .add(
            word("Le", "name", "lə", &["a surname from Vietnamese"]),
            &[] as &[&str],
        )
        .unwrap();
    french
        .add(
            word("le", "article", "lə", &["the (definite article)"]),
            &[] as &[&str],
        )
        .unwrap();

    let drawn = |pack: &Pack<Vec<u8>>, lang_code: &str, text: &str, spelling: &str| {
        let open = Open {
            source: Some(pack),
            ..Open::default()
        };
        let (tokens, _) = annotate(
            &[TextRun {
                id: 1,
                text: text.to_string(),
                lang_hint: None,
            }],
            &lang(lang_code),
            &lang("en"),
            &open,
            &AnnotateOptions {
                mode: InlineMode::Meaning,
                density: 1,
                narrow: false,
                hide_stress: false,
                accent: None,
                seen: Vec::new(),
                counts: Default::default(),
                seed: 0,
                accents: Default::default(),
            },
        );
        tokens
            .iter()
            .find(|token| token.spelling == spelling)
            .and_then(|token| token.gloss.clone())
            .unwrap_or_default()
    };
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let french = Pack::open(french.finish().unwrap()).unwrap();
    assert_eq!(
        drawn(&german, "de", "Der Hund", "Der"),
        "the",
        "after the comma"
    );
    assert_eq!(
        drawn(&german, "de", "mit dem Hund", "dem"),
        "the",
        "after the colon"
    );
    assert_eq!(
        drawn(&french, "fr", "Le chien", "Le"),
        "the",
        "the article that starts the sentence, not the surname spelled the same"
    );
}

/// The words that hold a sentence together win over what shares their spelling.
#[test]
fn a_function_word_filed_as_a_form_still_wins() {
    use lexcore::annotate::annotate;
    use lexcore::answer::{AnnotateOptions, InlineMode, TextRun};

    fn pointer(lemma: &str, pos: &str, gloss: &str) -> Entry {
        let mut entry = word(lemma, pos, "", &[gloss]);
        entry.senses[0].marks = vec!["form-of".to_string()];
        entry
    }
    let no_forms: [&str; 0] = [];

    let mut portuguese = Builder::new("pt", Kind::Lex, 0);
    portuguese
        .add(
            word("o", "article", "u", &["the (definite article)"]),
            &no_forms,
        )
        .unwrap();
    portuguese
        .add(pointer("as", "article", "feminine plural of o"), &no_forms)
        .unwrap();
    portuguese
        .add(
            word("as", "pron", "as", &["them (as a direct object)"]),
            &no_forms,
        )
        .unwrap();
    portuguese
        .add(word("ser", "verb", "ˈseɾ", &["to be"]), &["é"])
        .unwrap();
    portuguese
        .add(
            word("é", "intj", "ɛ", &["Indicates agreement; yes"]),
            &no_forms,
        )
        .unwrap();
    let mut french = Builder::new("fr", Kind::Lex, 0);
    french
        .add(pointer("la", "article", "feminine of le: the"), &no_forms)
        .unwrap();
    french
        .add(
            word("la", "pron", "la", &["her, it (direct object)"]),
            &no_forms,
        )
        .unwrap();
    french
        .add(word("la", "noun", "la", &["la, the note 'A'"]), &no_forms)
        .unwrap();

    let drawn = |pack: &Pack<Vec<u8>>, code: &str, text: &str, spelling: &str| {
        let open = Open {
            source: Some(pack),
            ..Open::default()
        };
        let (tokens, _) = annotate(
            &[TextRun {
                id: 1,
                text: text.to_string(),
                lang_hint: None,
            }],
            &lang(code),
            &lang("en"),
            &open,
            &AnnotateOptions {
                mode: InlineMode::Meaning,
                density: 1,
                narrow: false,
                hide_stress: false,
                accent: None,
                seen: Vec::new(),
                counts: Default::default(),
                seed: 0,
                accents: Default::default(),
            },
        );
        tokens
            .iter()
            .find(|token| token.spelling == spelling)
            .and_then(|token| token.gloss.clone())
            .unwrap_or_default()
    };
    let portuguese = Pack::open(portuguese.finish().unwrap()).unwrap();
    let french = Pack::open(french.finish().unwrap()).unwrap();
    assert_eq!(
        drawn(&portuguese, "pt", "As crianças", "As"),
        "the",
        "the article, said through the word it is a form of, not the pronoun"
    );
    assert_eq!(
        drawn(&portuguese, "pt", "a casa é grande", "é"),
        "to be",
        "the verb, not the interjection written the same"
    );
    assert_eq!(drawn(&french, "fr", "de la banque", "la"), "the");
}

/// A word of its own is not read as a form of a verb because the verb, counted with all its
/// forms, and the verb's answer in the reader's language are both met more often: "camino" is
/// the way, though "caminar" counts "camino" among its forms and German says "gehen" more
/// than "Weg".
#[test]
fn a_word_of_its_own_beats_a_verb_that_counts_it_among_its_forms() {
    let counted = |mut entry: Entry, count: u64| {
        entry.tags = vec![format!("count:{count}")];
        entry
    };
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    spanish
        .add(
            counted(word("caminar", "verb", "kamiˈnaɾ", &["to walk"]), 200_439),
            &["camino", "camina"],
        )
        .unwrap();
    spanish
        .add(
            counted(word("camino", "noun", "kaˈmino", &["way, route"]), 147_472),
            &["caminos"],
        )
        .unwrap();
    spanish
        .add(
            counted(
                word(
                    "camino",
                    "verb",
                    "kaˈmino",
                    &["first-person singular present indicative of caminar"],
                ),
                140_092,
            ),
            &[] as &[&str],
        )
        .unwrap();
    let mut german = Builder::new("de", Kind::Lex, 0);
    german
        .add(
            counted(word("gehen", "verb", "ˈɡeːən", &["to walk"]), 900_000),
            &[] as &[&str],
        )
        .unwrap();
    german
        .add(
            counted(word("Weg", "noun", "veːk", &["way, route"]), 90_000),
            &[] as &[&str],
        )
        .unwrap();
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&spanish),
        target: Some(&german),
        ..Open::default()
    };
    let got = look_up("camino", &lang("es"), &lang("de"), &open);
    assert_eq!(got.says.first().map(String::as_str), Some("Weg"), "{got:?}");
}

/// An entry that says only which form of another word it is reads as that word, and as the
/// one it names rather than any other word listing the same spelling among its forms.
#[test]
fn a_form_note_reads_as_the_word_it_names() {
    let mut german = Builder::new("de", Kind::Lex, 0);
    german
        .add(word("mein", "det", "maɪ̯n", &["my"]), &["meine"])
        .unwrap();
    let mut meine = word(
        "meine",
        "det",
        "ˈmaɪ̯nə",
        &["nominative/accusative plural of mein"],
    );
    meine.senses[0].marks = vec!["form-of".to_string()];
    german.add(meine, &[] as &[&str]).unwrap();
    // Listed first, as an inflection table can list another word's forms beside its own.
    german
        .add(word("sein", "det", "zaɪ̯n", &["his"]), &["meine"])
        .unwrap();
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&german),
        ..Open::default()
    };
    let got = look_up("meine", &lang("de"), &lang("en"), &open);
    assert_eq!(got.says.first().map(String::as_str), Some("my"), "{got:?}");
}

/// A line in another language than the page's is read in the accent the reader reads that
/// language in: a Portuguese quotation on an English page, for a reader who reads Portuguese
/// the way Portugal does, says "de" as Portugal does and not as the dictionary leads with.
#[test]
fn a_line_in_another_language_is_read_in_that_language_s_accent() {
    use lexcore::annotate::annotate;
    use lexcore::answer::{AnnotateOptions, InlineMode, TextRun};

    let no_forms: [&str; 0] = [];
    let mut english = Builder::new("en", Kind::Lex, 0);
    english
        .add(word("milk", "noun", "mɪlk", &["milk"]), &no_forms)
        .unwrap();
    let mut portuguese = Builder::new("pt", Kind::Lex, 0);
    portuguese
        .add(word("de", "prep", "d͡ʒi", &["of"]), &no_forms)
        .unwrap();
    let mut portugal = Builder::new("pt-pt", Kind::Lex, 0);
    portugal
        .add(word("de", "prep", "dɨ", &[]), &no_forms)
        .unwrap();
    let mut held = std::collections::HashMap::new();
    held.insert(
        "en".to_string(),
        Pack::open(english.finish().unwrap()).unwrap(),
    );
    held.insert(
        "pt".to_string(),
        Pack::open(portuguese.finish().unwrap()).unwrap(),
    );
    held.insert(
        "pt-pt".to_string(),
        Pack::open(portugal.finish().unwrap()).unwrap(),
    );
    let open = Open {
        source: held.get("en"),
        others: Some(&held),
        defines: None,
        ..Open::default()
    };
    let mut accents = std::collections::HashMap::new();
    accents.insert("pt".to_string(), "pt-pt".to_string());
    let (tokens, _) = annotate(
        &[TextRun {
            id: 1,
            text: "copo de leite".to_string(),
            lang_hint: Some(lang("pt")),
        }],
        &lang("en"),
        &lang("de"),
        &open,
        &AnnotateOptions {
            mode: InlineMode::Sound,
            density: 1,
            narrow: false,
            hide_stress: false,
            accent: Some("en-us".to_string()),
            seen: Vec::new(),
            counts: Default::default(),
            seed: 0,
            accents,
        },
    );
    let de = tokens.iter().find(|token| token.spelling == "de").unwrap();
    assert_eq!(de.ipa.as_deref(), Some("dɨ"), "{tokens:?}");
}

/// A language read in its own accent, which is what it is read in until the reader picks
/// another, keeps each reading's own sound: its dictionary is no accent pack laid over itself.
#[test]
fn a_language_in_its_own_accent_keeps_every_reading() {
    let no_forms: [&str; 0] = [];
    let mut german = Builder::new("de", Kind::Lex, 0);
    german
        .add(word("modern", "adj", "moˈdɛʁn", &["modern"]), &no_forms)
        .unwrap();
    german
        .add(word("modern", "verb", "ˈmoːdɐn", &["to rot"]), &no_forms)
        .unwrap();
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let read = |accent: &str, accent_pack| {
        let open = Open {
            source: Some(&german),
            target: None,
            ipa_only: false,
            accent,
            accent_pack,
            said: None,
            classifier: None,
            others: None,
            defines: None,
        };
        look_up("modern", &lang("de"), &lang("en"), &open)
    };
    let plain = read("", None);
    let own = read("de", Some(&german));
    assert_eq!(own.ipa, plain.ipa);
    let readings = |answer: &lexcore::resolve::Answer| {
        answer
            .readings
            .iter()
            .map(|it| it.ipa.clone())
            .collect::<Vec<_>>()
    };
    assert_eq!(readings(&own), readings(&plain));
}

/// "I" opening a sentence is the pronoun, not the small-letter "i" the start of a sentence would
/// otherwise reach: a capital is the page's only where the small spelling is the commoner word.
#[test]
fn a_word_commoner_with_its_capital_keeps_it_at_the_start_of_a_sentence() {
    use lexcore::annotate::annotate;
    use lexcore::answer::{AnnotateOptions, InlineMode, TextRun};

    let no_forms: [&str; 0] = [];
    let counted = |mut entry: Entry, count: u64| {
        entry.tags.push(format!("count:{count}"));
        entry
    };
    let mut english = Builder::new("en", Kind::Lex, 0);
    english
        .add(
            counted(word("I", "pron", "aɪ", &["myself"]), 38_000_000),
            &no_forms,
        )
        .unwrap();
    english
        .add(
            counted(word("i", "prep", "ə", &["in"]), 34_000_000),
            &no_forms,
        )
        .unwrap();
    english
        .add(
            counted(word("A", "character", "eɪ", &["the letter"]), 16_000_000),
            &no_forms,
        )
        .unwrap();
    english
        .add(
            counted(word("a", "article", "ə", &["one"]), 23_000_000),
            &no_forms,
        )
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&english),
        ..Open::default()
    };
    let (tokens, _) = annotate(
        &[TextRun {
            id: 1,
            text: "I think. A dog. Then I left.".to_string(),
            lang_hint: None,
        }],
        &lang("en"),
        &lang("de"),
        &open,
        &AnnotateOptions {
            mode: InlineMode::Sound,
            density: 1,
            narrow: false,
            hide_stress: false,
            accent: None,
            seen: Vec::new(),
            counts: Default::default(),
            seed: 0,
            accents: Default::default(),
        },
    );
    let said: Vec<(String, String)> = tokens
        .iter()
        .filter(|token| token.spelling == "I" || token.spelling == "A")
        .map(|token| {
            (
                token.spelling.clone(),
                token.ipa.clone().unwrap_or_default(),
            )
        })
        .collect();
    assert_eq!(
        said,
        [
            ("I".to_string(), "aɪ".to_string()),
            ("A".to_string(), "ə".to_string()),
            ("I".to_string(), "aɪ".to_string()),
        ],
        "{tokens:?}"
    );
}

/// A capital at the start of a sentence says nothing; in the middle of a German one it does.
#[test]
fn a_capital_counts_only_where_it_is_not_the_start_of_a_sentence() {
    use lexcore::annotate::annotate;
    use lexcore::answer::{AnnotateOptions, InlineMode, TextRun};

    let no_forms: [&str; 0] = [];
    let mut german = Builder::new("de", Kind::Lex, 0);
    german
        .add(word("Er", "noun", "eːɐ̯", &["a male"]), &no_forms)
        .unwrap();
    german
        .add(word("er", "pron", "eːɐ̯", &["he"]), &no_forms)
        .unwrap();
    // The noun commoner than the adverb, as it is in running text: in German that still says
    // nothing about which one opens a sentence.
    let mut morning = word("Morgen", "noun", "ˈmɔʁɡn̩", &["morning"]);
    morning.tags.push("count:900".to_string());
    let mut tomorrow = word("morgen", "adv", "ˈmɔʁɡn̩", &["tomorrow"]);
    tomorrow.tags.push("count:500".to_string());
    german.add(morning, &no_forms).unwrap();
    german.add(tomorrow, &no_forms).unwrap();
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&german),
        ..Open::default()
    };
    let (tokens, _) = annotate(
        &[TextRun {
            id: 1,
            text: "Er kommt. Am Morgen kommt er. Morgen kommt er.".to_string(),
            lang_hint: None,
        }],
        &lang("de"),
        &lang("en"),
        &open,
        &AnnotateOptions {
            mode: InlineMode::Meaning,
            density: 1,
            narrow: false,
            hide_stress: false,
            accent: None,
            seen: Vec::new(),
            counts: Default::default(),
            seed: 0,
            accents: Default::default(),
        },
    );
    let drawn: Vec<(String, String)> = tokens
        .iter()
        .filter(|token| token.inline)
        .map(|token| {
            (
                token.spelling.clone(),
                token.gloss.clone().unwrap_or_default(),
            )
        })
        .collect();
    // A pronoun is never handed out as a translation, every word or not: alone it agrees with
    // nothing around it.
    assert!(
        !drawn.iter().any(|(spelling, _)| spelling == "Er"),
        "{drawn:?}"
    );
    assert!(
        drawn.contains(&("Morgen".to_string(), "morning".to_string())),
        "{drawn:?}"
    );
    assert!(
        drawn.contains(&("Morgen".to_string(), "tomorrow".to_string())),
        "{drawn:?}"
    );
}

#[test]
fn a_word_reached_only_through_a_dialect_spelling_is_not_a_reading() {
    // English "of" as the dump files it: the preposition, and an eye-dialect spelling of
    // "have and 've" that brings "'ve" up under "of" as well.
    let mut en = Builder::new("en", Kind::Lex, 0);
    en.add(
        word(
            "of",
            "prep",
            "ɒv",
            &["Since, from (a given time, earlier state etc.)."],
        ),
        &[] as &[&str],
    )
    .unwrap();
    let mut spelled = word("of", "verb", "", &[]);
    spelled.senses = vec![Sense {
        gloss: "Eye dialect spelling of have and 've, chiefly in depictions of colloquial speech."
            .to_string(),
        marks: vec!["alt-of".to_string(), "pronunciation-spelling".to_string()],
        example: None,
    }];
    en.add(spelled, &[] as &[&str]).unwrap();
    en.add(
        word(
            "'ve",
            "verb",
            "v",
            &["Have (in its sense marking the perfect or retrospective aspect)."],
        ),
        &["of"],
    )
    .unwrap();
    let en = Pack::open(en.finish().unwrap()).unwrap();
    let mut de = Builder::new("de", Kind::Lex, 0);
    de.add(
        word("haben", "verb", "ˈhaːbn̩", &["to have"]),
        &[] as &[&str],
    )
    .unwrap();
    de.add(word("von", "prep", "fɔn", &["from; of"]), &[] as &[&str])
        .unwrap();
    let de = Pack::open(de.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&en),
        target: Some(&de),
        ..Open::default()
    };

    let answer = read_in_context("of", None, &lang("en"), &lang("de"), &open);

    assert_eq!(answer.pos.as_deref(), Some("prep"));
    assert!(
        !answer.says.contains(&"haben".to_string()),
        "{:?}",
        answer.says
    );
    assert!(answer
        .readings
        .iter()
        .all(|r| r.pos.as_deref() != Some("verb")));
}

/// A word met as itself is not read as a word it is only a form of, because the reader's
/// language says the other word's answer more often.
///
/// German "Katze" is also listed as the feminine of "Kater", and English says "tomcat" far more
/// than "house cat". Which German word was met is a question about German: "Katze" is met more
/// often than "Kater", so a page saying "Katze" is about a cat. Spanish "notas" is the other
/// way round: a rare word of its own, and the plural of "nota", which is met far more.
#[test]
fn a_word_the_spelling_is_only_a_form_of_wins_only_by_being_commoner_itself() {
    let mut german = Builder::new("de", Kind::Lex, 0);
    let mut katze = word("Katze", "noun", "ˈkatsə", &["house cat"]);
    katze.tags.push("count:12051".to_string());
    let mut kater = word("Kater", "noun", "ˈkaːtɐ", &["tomcat"]);
    kater.tags.push("count:7892".to_string());
    german.add(katze, &[] as &[&str]).unwrap();
    german.add(kater, &["Katze"]).unwrap();
    let de = german.finish().unwrap();

    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let mut notas = word("notas", "adj", "ˈno.tas", &["show-off"]);
    notas.tags.push("count:900".to_string());
    let mut nota = word("nota", "noun", "ˈno.ta", &["note"]);
    nota.tags.push("count:30000".to_string());
    spanish.add(notas, &[] as &[&str]).unwrap();
    spanish.add(nota, &["notas"]).unwrap();
    let es = spanish.finish().unwrap();

    let mut english = Builder::new("en", Kind::Lex, 0);
    for (lemma, count) in [
        ("tomcat", 5000),
        ("house cat", 1),
        ("note", 80000),
        ("show-off", 10),
    ] {
        let mut entry = word(lemma, "noun", "x", &[lemma]);
        entry.tags.push(format!("count:{count}"));
        english.add(entry, &[] as &[&str]).unwrap();
    }
    let en = english.finish().unwrap();
    let (de, es, en) = (
        Pack::open(&de).unwrap(),
        Pack::open(&es).unwrap(),
        Pack::open(&en).unwrap(),
    );

    let reading = |pack: &Pack<&Vec<u8>>, spelling: &str, source: &str| {
        let open = Open {
            source: Some(pack),
            target: Some(&en),
            ..Open::default()
        };
        read_in_context(spelling, None, &lang(source), &lang("en"), &open)
    };
    let cat = reading(&de, "Katze", "de");
    assert_eq!(
        cat.lemma, None,
        "Katze is the cat, not a form of Kater: {:?}",
        cat.says
    );
    assert_eq!(cat.says.first().map(String::as_str), Some("house cat"));
    let notes = reading(&es, "notas", "es");
    assert_eq!(
        notes.lemma.as_deref(),
        Some("nota"),
        "notas are notes: {:?}",
        notes.says
    );
}

#[test]
fn a_typed_word_is_in_the_language_whose_dictionary_holds_it() {
    use lexcore::resolve::typed_in_mine;
    let (de, es) = (german(), spanish());
    let (de, es) = (Pack::open(&de).unwrap(), Pack::open(&es).unwrap());
    let learning = lang("es");
    // A German reader learning Spanish.
    assert!(typed_in_mine("Hund", &learning, Some(&de), Some(&es), None));
    assert!(typed_in_mine("hund", &learning, Some(&de), Some(&es), None));
    assert!(!typed_in_mine(
        "perro",
        &learning,
        Some(&de),
        Some(&es),
        None
    ));
    // Nothing holds it and nothing detects it: the reader's own, which is the way a word for
    // something is asked for.
    assert!(typed_in_mine(
        "Zwiebelkuchen",
        &learning,
        Some(&de),
        Some(&es),
        None
    ));
}

#[test]
fn a_word_is_answered_into_english_from_its_own_dictionary_alone() {
    use lexcore::resolve::meanings;
    let es = spanish();
    let es = Pack::open(&es).unwrap();
    let found = meanings("perro", &lang("es"), &lang("en"), None, Some(&es));
    assert_eq!(
        found.first().map(|m| m.word.as_str()),
        Some("dog"),
        "{found:?}"
    );
}

/// A reader who asked for narrow transcriptions is shown the dictionary's narrow one, and one
/// who did not is shown the broad one: the two are different transcriptions, not one with its
/// detail stripped, and the page drew the broad one under both settings.
#[test]
fn narrow_shows_the_narrow_transcription_the_dictionary_gives() {
    use lexcore::annotate::annotate;
    use lexcore::answer::{AnnotateOptions, InlineMode, TextRun};

    let mut pack = Builder::new("de", Kind::Lex, 0);
    let mut carpet = word("Teppich", "noun", "ˈtɛpɪç", &["carpet"]);
    carpet.ipa.push("ˈtʰɛ.pʰɪç".to_string());
    carpet.tags.push("narrow:1".to_string());
    pack.add(carpet, &[] as &[&str]).unwrap();
    let pack = pack.finish().unwrap();
    let pack = Pack::open(&pack).unwrap();
    let open = Open {
        source: Some(&pack),
        ..Open::default()
    };
    let drawn = |narrow: bool| {
        let (tokens, _) = annotate(
            &[TextRun {
                id: 1,
                text: "Der Teppich".to_string(),
                lang_hint: None,
            }],
            &lang("de"),
            &lang("en"),
            &open,
            &AnnotateOptions {
                mode: InlineMode::Sound,
                density: 1,
                narrow,
                hide_stress: false,
                accent: None,
                seen: Vec::new(),
                counts: Default::default(),
                seed: 0,
                accents: Default::default(),
            },
        );
        tokens
            .into_iter()
            .find(|token| token.spelling == "Teppich")
            .and_then(|token| token.ipa)
    };
    assert_eq!(drawn(false).as_deref(), Some("ˈtɛpɪç"));
    // Without its syllable dots, which the page leaves off either way.
    assert_eq!(drawn(true).as_deref(), Some("ˈtʰɛpʰɪç"));
}

/// A word heard rather than typed is put right against the dictionary of the language it was
/// said in: one letter off becomes the word, two words run together come apart, and what the
/// dictionary holds or nothing is near stays as heard.
#[test]
fn a_word_heard_a_letter_wrong_becomes_the_word_the_dictionary_holds() {
    let es = Pack::open(spanish()).unwrap();
    let de = Pack::open(german()).unwrap();
    assert_eq!(lexcore::resolve::heard("Camimo.", &es), "Camino.");
    assert_eq!(lexcore::resolve::heard("el perros", &es), "el perros");
    assert_eq!(lexcore::resolve::heard("Hundeweg", &de), "Hunde Weg");
    assert_eq!(lexcore::resolve::heard("Sitzbamk", &de), "Sitzbank");
    // Short, and nothing near: as heard.
    assert_eq!(lexcore::resolve::heard("Bnk", &de), "Bnk");
    assert_eq!(lexcore::resolve::heard("Xylofon", &de), "Xylofon");
}

/// An English word read into Spanish is what the English Wiktionary's table says it is, ahead
/// of the join on glosses: "file" joined that way reached "fila", a row, whose gloss happens
/// to be "file"; the table says "archivo". And the line, where the host translated it, picks
/// which of the table's words it uses.
/// Of the readings a translated line reaches through the same word, the one whose definitions
/// fit that word's own leads, with the sense that fits: every noun "bank" has "orilla" in its
/// table, and "orilla" is a river's edge, so the line about a river is the bank of a river.
#[test]
fn the_reading_that_fits_the_lines_word_leads() {
    let mut english = Builder::new("en", Kind::Lex, 0).without_joins();
    english
        .add::<&str>(
            word(
                "bank",
                "noun",
                "bæŋk",
                &[
                    "An institution where one can place and borrow money.",
                    "A branch office of such an institution.",
                ],
            ),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(
            word(
                "bank",
                "noun",
                "bæŋk",
                &[
                    "An elevation under the sea.",
                    "An edge of river, lake, or other watercourse.",
                ],
            ),
            &[],
        )
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let banco = spanish
        .add::<&str>(
            word(
                "banco",
                "noun",
                "ˈbaŋ.ko",
                &["bank (financial institution)"],
            ),
            &[],
        )
        .unwrap();
    let orilla = spanish
        .add::<&str>(
            word("orilla", "noun", "oˈɾi.ʎa", &["shore, edge of a river"]),
            &[],
        )
        .unwrap();
    spanish.translated("bank", "noun", &[(banco, 0), (orilla, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&english),
        target: Some(&spanish),
        said: Some("Se sentó en la orilla del río."),
        ..Open::default()
    };
    let answer = read_in_context("bank", Some("the"), &lang("en"), &lang("es"), &open);
    assert_eq!(answer.says, ["orilla"]);
    assert_eq!(
        answer.glosses[0],
        "An edge of river, lake, or other watercourse."
    );
}

/// A word of a language with no table into the reader's is read through English: German
/// "mit" is "with", and Spanish holds the English Wiktionary's table for "with", "con", where
/// joined on glosses it was "también"; "können" is "can" among its terms, and the table's
/// first word, "poder", not "enlatar", which only gives "can" back more plainly; "du" is "you",
/// and of that table's words the one whose own dictionary starts with "you", "tú".
#[test]
fn a_word_with_no_table_is_read_through_english() {
    let mut german = Builder::new("de", Kind::Lex, 0);
    german
        .add::<&str>(
            word("mit", "prep", "mɪt", &["with (in the company of)"]),
            &[],
        )
        .unwrap();
    german
        .add::<&str>(word("mit", "adv", "mɪt", &["also, too"]), &[])
        .unwrap();
    german
        .add::<&str>(
            word(
                "können",
                "verb",
                "ˈkœnən",
                &["to be able (to do something), to have the possibility of, can."],
            ),
            &[],
        )
        .unwrap();
    german
        .add::<&str>(
            word("du", "pron", "duː", &["you (singular familiar), thou"]),
            &[],
        )
        .unwrap();
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let con = spanish
        .add::<&str>(word("con", "prep", "kon", &["with"]), &[])
        .unwrap();
    let tambien = spanish
        .add::<&str>(word("también", "adv", "tamˈbjen", &["also, too"]), &[])
        .unwrap();
    let poder = spanish
        .add::<&str>(
            word("poder", "verb", "poˈdeɾ", &["to be able to, can"]),
            &[],
        )
        .unwrap();
    let enlatar = spanish
        .add::<&str>(word("enlatar", "verb", "en.laˈtaɾ", &["to can"]), &[])
        .unwrap();
    let lo = spanish
        .add::<&str>(word("lo", "pron", "lo", &["him, it, you (formal)"]), &[])
        .unwrap();
    let tu = spanish
        .add::<&str>(word("tú", "pron", "tu", &["you (informal)"]), &[])
        .unwrap();
    spanish.translated("with", "prep", &[(con, 0)]);
    spanish.translated("also", "adv", &[(tambien, 0)]);
    spanish.translated("can", "verb", &[(poder, 0), (enlatar, 0)]);
    spanish.translated("you", "pron", &[(lo, 0), (tu, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&german),
        target: Some(&spanish),
        ..Open::default()
    };
    let says = |word: &str| read_in_context(word, None, &lang("de"), &lang("es"), &open).says;
    assert_eq!(says("mit").first().map(String::as_str), Some("con"));
    assert_eq!(says("können").first().map(String::as_str), Some("poder"));
    assert_eq!(says("du").first().map(String::as_str), Some("tú"));
}

/// Through English, a term with no table still answers where the reader's dictionary defines
/// a word as exactly that, first: French "déclarer" is "to declare", which has no table, and is
/// "declarar", not a later term's table word. A meaning set after a sentence about the word is
/// a term too: Italian "a" is "Indicates the indirect object. to". And a letter's name is no
/// reading of a word: French "a" is "has", never the letter, whose Spanish "a" is the
/// commonest preposition.
#[test]
fn terms_without_tables_sentences_and_letters() {
    let mut french = Builder::new("fr", Kind::Lex, 0);
    french
        .add::<&str>(
            word(
                "déclarer",
                "verb",
                "de.kla.ʁe",
                &["to declare", "to come out"],
            ),
            &[],
        )
        .unwrap();
    let mut avoir = word("avoir", "verb", "a.vwaʁ", &["to have"]);
    avoir.forms.push(lexpack::Form {
        spelling: "a".to_string(),
        label: "third-person singular present indicative".to_string(),
    });
    french.add::<&str>(avoir, &[]).unwrap();
    french
        .add::<&str>(
            word(
                "a",
                "noun",
                "a",
                &["a, the name of the Latin-script letter A"],
            ),
            &[],
        )
        .unwrap();
    let french = Pack::open(french.finish().unwrap()).unwrap();
    let mut italian = Builder::new("it", Kind::Lex, 0);
    italian
        .add::<&str>(
            word("a", "prep", "a", &["Indicates the indirect object. to"]),
            &[],
        )
        .unwrap();
    let italian = Pack::open(italian.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    spanish
        .add::<&str>(
            word("declarar", "verb", "de.klaˈɾaɾ", &["to declare; to state"]),
            &[],
        )
        .unwrap();
    let salir = spanish
        .add::<&str>(word("salir", "verb", "saˈliɾ", &["to leave"]), &[])
        .unwrap();
    let mut tener = word("tener", "verb", "teˈneɾ", &["to have"]);
    tener.forms.push(lexpack::Form {
        spelling: "tiene".to_string(),
        label: "indicative present singular third-person".to_string(),
    });
    let tener = spanish.add::<&str>(tener, &[]).unwrap();
    let a = spanish
        .add::<&str>(word("a", "prep", "a", &["to, at"]), &[])
        .unwrap();
    spanish.translated("come out", "verb", &[(salir, 0)]);
    spanish.translated("have", "verb", &[(tener, 0)]);
    spanish.translated("to", "prep", &[(a, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let from = |pack| Open {
        source: Some(pack),
        target: Some(&spanish),
        ..Open::default()
    };
    let french_open = from(&french);
    let italian_open = from(&italian);
    let says = |word: &str, lang_: &str, open: &Open<Vec<u8>>| {
        read_in_context(word, None, &lang(lang_), &lang("es"), open).says
    };
    assert_eq!(
        says("déclarer", "fr", &french_open)
            .first()
            .map(String::as_str),
        Some("declarar")
    );
    assert_eq!(
        says("a", "fr", &french_open).first().map(String::as_str),
        Some("tiene")
    );
    assert_eq!(
        says("a", "it", &italian_open).first().map(String::as_str),
        Some("a")
    );
}

/// A form of a language other than English is carried into the reader's where it has the same
/// form: German "ist" is "es", "Hunde" is "perros", not "ser" and "perro".
#[test]
fn a_form_is_carried_from_any_language() {
    let form = |spelling: &str, label: &str| lexpack::Form {
        spelling: spelling.to_string(),
        label: label.to_string(),
    };
    let mut german = Builder::new("de", Kind::Lex, 0);
    let mut sein = word("sein", "verb", "zaɪ̯n", &["to be"]);
    sein.forms
        .push(form("ist", "present singular third-person"));
    german.add::<&str>(sein, &[]).unwrap();
    let mut hund = word("Hund", "noun", "hʊnt", &["dog"]);
    hund.forms.push(form("Hunde", "nominative plural"));
    german.add::<&str>(hund, &[]).unwrap();
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let mut ser = word("ser", "verb", "seɾ", &["to be"]);
    ser.forms
        .push(form("es", "indicative present singular third-person"));
    ser.forms
        .push(form("sea", "present singular subjunctive third-person"));
    let ser = spanish.add::<&str>(ser, &[]).unwrap();
    let mut perro = word("perro", "noun", "ˈpero", &["dog"]);
    perro.forms.push(form("perros", "plural"));
    let perro = spanish.add::<&str>(perro, &[]).unwrap();
    spanish.translated("be", "verb", &[(ser, 0)]);
    spanish.translated("dog", "noun", &[(perro, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&german),
        target: Some(&spanish),
        ..Open::default()
    };
    let says = |word: &str| read_in_context(word, None, &lang("de"), &lang("es"), &open).says;
    assert_eq!(says("ist").first().map(String::as_str), Some("es"));
    assert_eq!(says("Hunde").first().map(String::as_str), Some("perros"));
}

/// A gloss matched in full is as good an answer whatever its length, and the earlier sense
/// decides: Italian "correre" is "to run" before "to hurry, to rush", and is "correr", where
/// counting shared terms alone made it "apurar". A word all of whose own sense is matched is
/// matched in full: "casi", "almost", answers German "fast", "almost; nearly", before the
/// later "hardly".
#[test]
fn a_gloss_matched_in_full_and_first_answers() {
    let mut italian = Builder::new("it", Kind::Lex, 0);
    italian
        .add::<&str>(
            word(
                "correre",
                "verb",
                "ˈkor.re.re",
                &[
                    "to run [auxiliary essere or avere] (see above)",
                    "to hurry, to rush",
                ],
            ),
            &[],
        )
        .unwrap();
    italian
        .add::<&str>(
            word("fast", "adv", "fast", &["almost; nearly", "hardly"]),
            &[],
        )
        .unwrap();
    let italian = Pack::open(italian.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    spanish
        .add::<&str>(word("correr", "verb", "koˈreɾ", &["to run, to jog"]), &[])
        .unwrap();
    spanish
        .add::<&str>(
            word("apurar", "verb", "apuˈɾaɾ", &["to hurry, to rush"]),
            &[],
        )
        .unwrap();
    spanish
        .add::<&str>(word("casi", "adv", "ˈkasi", &["almost"]), &[])
        .unwrap();
    spanish
        .add::<&str>(
            word("difícilmente", "adv", "difiθilˈmente", &["hardly"]),
            &[],
        )
        .unwrap();
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&italian),
        target: Some(&spanish),
        ..Open::default()
    };
    let says = |word: &str| read_in_context(word, None, &lang("it"), &lang("es"), &open).says;
    assert_eq!(says("correre").first().map(String::as_str), Some("correr"));
    assert_eq!(says("fast").first().map(String::as_str), Some("casi"));
}

/// Read in its own language, the sentence as written says which sense a word is: "the bank of
/// the river" is its edge, and a sentence sharing nothing with any sense leaves the
/// dictionary's order.
#[test]
fn a_words_own_sentence_says_which_sense() {
    let mut english = Builder::new("en", Kind::Lex, 0);
    english
        .add::<&str>(
            word(
                "bank",
                "noun",
                "bæŋk",
                &["An institution where one can place and borrow money."],
            ),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(
            word(
                "bank",
                "noun",
                "bæŋk",
                &[
                    "An elevation under the sea.",
                    "An edge of river, lake, or other watercourse.",
                ],
            ),
            &[],
        )
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let read = |said: &'static str| {
        let open = Open {
            source: Some(&english),
            target: Some(&english),
            said: Some(said),
            ..Open::default()
        };
        read_in_context("bank", Some("the"), &lang("en"), &lang("en"), &open).glosses[0].clone()
    };
    assert_eq!(
        read("We sat on the bank of the river."),
        "An edge of river, lake, or other watercourse."
    );
    assert_eq!(
        read("I went to the bank today."),
        "An institution where one can place and borrow money."
    );
}

/// The modal "can" and "to can" food share a table, and the word for the modal is the one
/// whose own dictionary says what the modal says: "poder", "to be able to", not "enlatar",
/// "to can". A verb English makes a tense with has no word where none gives it back: "would"
/// is nothing, never "por favor". And a card leads with no word a definition only mentions:
/// "will" "with an implication of volition" was "volición".
#[test]
fn a_modal_is_its_own_sense_and_a_tense_marker_no_word() {
    let mut english = Builder::new("en", Kind::Lex, 0).without_joins();
    english
        .add::<&str>(
            word("can", "verb", "kæn", &["To know how to.", "To be able to."]),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(word("can", "verb", "kæn", &["To seal in a can."]), &[])
        .unwrap();
    english
        .add::<&str>(
            word(
                "would",
                "verb",
                "wʊd",
                &["Used to form the future in the past."],
            ),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(
            word(
                "will",
                "verb",
                "wɪl",
                &["Used to express the future tense, sometimes with an implication of volition."],
            ),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(
            word("volition", "noun", "vəˈlɪʃən", &["A conscious choice."]),
            &[],
        )
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let poder = spanish
        .add::<&str>(
            word("poder", "verb", "poˈdeɾ", &["to be able to, can"]),
            &[],
        )
        .unwrap();
    let enlatar = spanish
        .add::<&str>(
            word(
                "enlatar",
                "verb",
                "en.laˈtaɾ",
                &["to can (to seal in a can)"],
            ),
            &[],
        )
        .unwrap();
    spanish
        .add::<&str>(
            word("por favor", "adv", "poɾ faˈβoɾ", &["please; would you"]),
            &[],
        )
        .unwrap();
    let volicion = spanish
        .add::<&str>(word("volición", "noun", "bo.liˈθjon", &["volition"]), &[])
        .unwrap();
    spanish.translated("can", "verb", &[(poder, 0), (enlatar, 0)]);
    spanish.translated("volition", "noun", &[(volicion, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&english),
        target: Some(&spanish),
        ..Open::default()
    };
    let read = |word: &str| read_in_context(word, None, &lang("en"), &lang("es"), &open);
    assert_eq!(read("can").says.first().map(String::as_str), Some("poder"));
    assert!(read("would").says.is_empty(), "{:?}", read("would").says);
    assert_ne!(
        lexcore::annotate::lead(&read("will"), &open).as_deref(),
        Some("volición")
    );
}

/// A reading is found in the line by its answer's forms as the kind of word the reading is:
/// the archaic noun "were", "a man", answers "Er", and looked for as every form of the pronoun
/// "er" it was found in any line with "es" in it.
#[test]
fn a_nouns_answer_is_not_found_by_a_pronouns_forms() {
    let form = |spelling: &str| lexpack::Form {
        spelling: spelling.to_string(),
        label: String::new(),
    };
    let mut english = Builder::new("en", Kind::Lex, 0).without_joins();
    english
        .add::<&str>(
            word(
                "were",
                "verb",
                "wɜː",
                &["Second-person singular simple past of be."],
            ),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(word("were", "noun", "wɪə", &["A man; a male."]), &[])
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut german = Builder::new("de", Kind::Lex, 0);
    let mut sein = word("sein", "verb", "zaɪ̯n", &["to be"]);
    sein.forms.extend([form("waren"), form("war")]);
    let sein = german.add::<&str>(sein, &[]).unwrap();
    let mut er = word("er", "pron", "eːɐ̯", &["he"]);
    er.forms.extend([form("es"), form("sie")]);
    let er = german.add::<&str>(er, &[]).unwrap();
    german.translated("were", "verb", &[(sein, 0)]);
    german.translated("were", "noun", &[(er, 0)]);
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&english),
        target: Some(&german),
        said: Some("Es gab weltweit Proteste."),
        ..Open::default()
    };
    let says = read_in_context("were", Some("There"), &lang("en"), &lang("de"), &open).says;
    assert_eq!(says.first().map(String::as_str), Some("sein"), "{says:?}");
}

/// A table can list a piece of a word, and a reader is never handed one: "to" marking an
/// infinitive is listed in Spanish as the ending "-ar", and its card said "-ar".
#[test]
fn an_ending_is_no_answer() {
    let mut english = Builder::new("en", Kind::Lex, 0).without_joins();
    english
        .add::<&str>(
            word(
                "to",
                "particle",
                "tuː",
                &["Marks the following verb as an infinitive."],
            ),
            &[],
        )
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let ending = spanish
        .add::<&str>(
            word("-ar", "suffix", "aɾ", &["forms infinitives of verbs"]),
            &[],
        )
        .unwrap();
    let que = spanish
        .add::<&str>(word("que", "conj", "ke", &["that"]), &[])
        .unwrap();
    spanish.translated("to", "particle", &[(ending, 0), (que, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&english),
        target: Some(&spanish),
        ..Open::default()
    };
    let says = read_in_context("to", None, &lang("en"), &lang("es"), &open).says;
    assert!(!says.iter().any(|word| word.starts_with('-')), "{says:?}");
}

/// An English capital is a sentence's: "It" starting one is the pronoun, not the noun "it" of
/// the game of tag. And a form's note that goes on to say what it means is that meaning:
/// "people" is "plural of person: a body of persons", ahead of the verb "to people".
#[test]
fn an_english_capital_is_a_sentences_and_a_note_with_a_meaning_is_one() {
    let mut english = Builder::new("en", Kind::Lex, 0).without_joins();
    english
        .add::<&str>(word("it", "pron", "ɪt", &["The thing spoken of."]), &[])
        .unwrap();
    english
        .add::<&str>(
            word(
                "it",
                "noun",
                "ɪt",
                &["The player who chases the others in tag."],
            ),
            &[],
        )
        .unwrap();
    let mut people = word(
        "people",
        "noun",
        "ˈpiːpəl",
        &["plural of person: a body of persons considered generally"],
    );
    people.senses[0].marks = vec!["form-of".to_string(), "plural".to_string()];
    english.add::<&str>(people, &[]).unwrap();
    // After the noun, as the dictionary lists them.
    english
        .add::<&str>(word("people", "verb", "ˈpiːpəl", &["To populate."]), &[])
        .unwrap();
    // As the dictionary has it: a noun spelled with the capital, the force Groddeck named.
    english
        .add::<&str>(
            word(
                "It",
                "noun",
                "ɪt",
                &["A biological force that inhabits living beings."],
            ),
            &[],
        )
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let ello = spanish
        .add::<&str>(word("ello", "pron", "ˈe.ʎo", &["it"]), &[])
        .unwrap();
    let cogida = spanish
        .add::<&str>(word("cogida", "noun", "koˈxi.da", &["it (in tag)"]), &[])
        .unwrap();
    let poblar = spanish
        .add::<&str>(word("poblar", "verb", "poˈβlaɾ", &["to populate"]), &[])
        .unwrap();
    let gente = spanish
        .add::<&str>(word("gente", "noun", "ˈxen.te", &["people"]), &[])
        .unwrap();
    spanish.translated("it", "pron", &[(ello, 0)]);
    spanish.translated("it", "noun", &[(cogida, 0)]);
    spanish.translated("people", "verb", &[(poblar, 0)]);
    spanish.translated("people", "noun", &[(gente, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&english),
        target: Some(&spanish),
        ..Open::default()
    };
    let says = |word: &str| read_in_context(word, None, &lang("en"), &lang("es"), &open).says;
    assert_eq!(says("It")[0], "ello");
    assert_eq!(says("people")[0], "gente");
}

/// A reading the line says by a word none of the readings' own words reach: the engine wrote
/// "fundamental", whose dictionary calls it "essential", and only the adjective "core" is
/// "essential" too.
#[test]
fn the_lines_word_says_which_reading_by_what_it_means() {
    let mut english = Builder::new("en", Kind::Lex, 0).without_joins();
    english
        .add::<&str>(
            word(
                "core",
                "noun",
                "kɔɹ",
                &["The central part of a fruit, holding the seeds."],
            ),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(
            word("core", "adj", "kɔɹ", &["Forming the most essential part."]),
            &[],
        )
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let corazon = spanish
        .add::<&str>(word("corazón", "noun", "ko.ɾaˈθon", &["heart, core"]), &[])
        .unwrap();
    let troncal = spanish
        .add::<&str>(
            word("troncal", "adj", "tɾonˈkal", &["trunk (main line)"]),
            &[],
        )
        .unwrap();
    let mut fundamental = word(
        "fundamental",
        "adj",
        "fun.da.menˈtal",
        &["essential, basic"],
    );
    fundamental.forms.push(lexpack::Form {
        spelling: "fundamentales".to_string(),
        label: "plural".to_string(),
    });
    spanish.add::<&str>(fundamental, &[]).unwrap();
    spanish.translated("core", "noun", &[(corazon, 0)]);
    spanish.translated("core", "adj", &[(troncal, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = |said: &'static str| Open {
        source: Some(&english),
        target: Some(&spanish),
        said: Some(said),
        ..Open::default()
    };
    let read =
        |said| read_in_context("core", Some("a"), &lang("en"), &lang("es"), &open(said)).says;
    assert_eq!(read("Una prueba fundamental.")[0], "fundamental");
    // A line holding a reading's own word is that reading's, as it was.
    assert_eq!(read("El corazón de la manzana.")[0], "corazón");
}

/// An English word in a form another language has too is its word put into that form, from
/// that word's own table: "houses" is "casas", "published" is "publicado", never the bare
/// "casa" or "publicar". A dialect's label is no form a reader met: "went" is filed as a
/// dialect's past participle too, and is not "ido".
#[test]
fn an_english_form_is_carried_into_the_other_language() {
    let form = |spelling: &str, label: &str| lexpack::Form {
        spelling: spelling.to_string(),
        label: label.to_string(),
    };
    let mut english = Builder::new("en", Kind::Lex, 0).without_joins();
    let mut house = word("house", "noun", "haʊs", &["A dwelling."]);
    house.forms.push(form("houses", "plural"));
    english.add::<&str>(house, &[]).unwrap();
    let mut publish = word("publish", "verb", "ˈpʌblɪʃ", &["To issue for sale."]);
    publish.forms.push(form("published", "participle past"));
    english.add::<&str>(publish, &[]).unwrap();
    let mut go = word("go", "verb", "ɡoʊ", &["To move."]);
    go.forms.push(form("went", "past"));
    go.forms.push(form("went", "dialectal participle past"));
    english.add::<&str>(go, &[]).unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let mut casa = word("casa", "noun", "ˈka.sa", &["house"]);
    casa.forms.push(form("casas", "plural"));
    casa.forms.push(form("casitas", "diminutive plural"));
    let casa = spanish.add::<&str>(casa, &[]).unwrap();
    let mut publicar = word("publicar", "verb", "pu.βliˈkaɾ", &["to publish"]);
    publicar
        .forms
        .push(form("publicado", "past masculine singular participle"));
    publicar
        .forms
        .push(form("publicadas", "past feminine plural participle"));
    let publicar = spanish.add::<&str>(publicar, &[]).unwrap();
    let mut ir = word("ir", "verb", "iɾ", &["to go"]);
    ir.forms
        .push(form("ido", "past masculine singular participle"));
    let ir = spanish.add::<&str>(ir, &[]).unwrap();
    // A table lists the little house first; the word whose own dictionary says just "house"
    // is the house.
    let casinha = spanish
        .add::<&str>(
            word("casita", "noun", "kaˈsi.ta", &["cottage, little house"]),
            &[],
        )
        .unwrap();
    spanish.translated("house", "noun", &[(casinha, 0), (casa, 0)]);
    spanish.translated("publish", "verb", &[(publicar, 0)]);
    spanish.translated("go", "verb", &[(ir, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&english),
        target: Some(&spanish),
        ..Open::default()
    };
    let says = |word: &str| read_in_context(word, None, &lang("en"), &lang("es"), &open).says;
    assert_eq!(says("house"), ["casa"]);
    assert_eq!(says("houses"), ["casas"]);
    assert_eq!(says("published"), ["publicado"]);
    assert_eq!(says("went"), ["ir"]);
}

#[test]
fn a_plural_with_a_slang_noun_of_its_own_is_still_the_plural() {
    let form = |spelling: &str, label: &str| lexpack::Form {
        spelling: spelling.to_string(),
        label: label.to_string(),
    };
    let marked = |mut entry: Entry, marks: &[&str]| {
        for sense in entry.senses.iter_mut() {
            sense.marks = marks.iter().map(|it| it.to_string()).collect();
        }
        entry
    };
    let mut english = Builder::new("en", Kind::Lex, 0).without_joins();
    let mut dog = word("dog", "noun", "dɒɡ", &["A domesticated canine."]);
    dog.forms.push(form("dogs", "plural"));
    english.add::<&str>(dog, &[]).unwrap();
    english
        .add::<&str>(
            marked(
                word("dogs", "noun", "dɒɡz", &["plural of dog"]),
                &["form-of", "plural"],
            ),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(
            {
                // As the dictionary marks it: the first sense slang, the racing not.
                let mut dogs = marked(
                    word("dogs", "noun", "dɒɡz", &["Feet.", "Greyhound racing."]),
                    &["plural-only"],
                );
                dogs.senses[0].marks.push("slang".to_string());
                dogs
            },
            &[],
        )
        .unwrap();
    let mut glass = word("glass", "noun", "ɡlɑːs", &["A transparent solid."]);
    glass.forms.push(form("glasses", "plural"));
    english.add::<&str>(glass, &[]).unwrap();
    english
        .add::<&str>(
            marked(
                word("glasses", "noun", "ˈɡlɑːsɪz", &["plural of glass"]),
                &["form-of", "plural"],
            ),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(
            marked(
                word("glasses", "noun", "ˈɡlɑːsɪz", &["Spectacles."]),
                &["plural-only"],
            ),
            &[],
        )
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let mut perro = word("perro", "noun", "ˈpe.ro", &["dog"]);
    perro.forms.push(form("perros", "plural"));
    let perro = spanish.add::<&str>(perro, &[]).unwrap();
    spanish
        .add::<&str>(word("perrería", "noun", "pe.reˈɾi.a", &["dogs"]), &[])
        .unwrap();
    let mut cristal = word("cristal", "noun", "kɾisˈtal", &["glass"]);
    cristal.forms.push(form("cristales", "plural"));
    let cristal = spanish.add::<&str>(cristal, &[]).unwrap();
    let anteojos = spanish
        .add::<&str>(
            word("anteojos", "noun", "an.teˈo.xos", &["glasses, spectacles"]),
            &[],
        )
        .unwrap();
    spanish.translated("dog", "noun", &[(perro, 0)]);
    spanish.translated("glass", "noun", &[(cristal, 0)]);
    spanish.translated("glasses", "noun", &[(anteojos, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&english),
        target: Some(&spanish),
        ..Open::default()
    };
    let says = |word: &str| read_in_context(word, None, &lang("en"), &lang("es"), &open).says;
    // "dogs" the slang for feet is not what "dogs" means first; "glasses", spectacles, is.
    assert_eq!(says("dogs"), ["perros"]);
    assert_eq!(says("glasses"), ["anteojos"]);
    // And the slang noun has no Spanish word: Spanish glosses "perrería" as "dogs", which is
    // what the plural of dog is, not feet.
    let dogs = read_in_context("dogs", None, &lang("en"), &lang("es"), &open);
    let slang = dogs
        .readings
        .iter()
        .find(|reading| {
            reading
                .glosses
                .first()
                .is_some_and(|gloss| gloss == "Feet.")
        })
        .expect("the slang reading is offered");
    assert!(slang.says.is_empty(), "{:?}", slang.says);
}

#[test]
fn an_english_word_is_what_its_translation_table_says() {
    let mut english = Builder::new("en", Kind::Lex, 0).without_joins();
    english
        .add(
            word(
                "file",
                "noun",
                "faɪl",
                &["A collection of papers collated and archived together."],
            ),
            &["files"],
        )
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let fila = spanish
        .add(
            word("fila", "noun", "ˈfi.la", &["row, line, file"]),
            &[] as &[&str],
        )
        .unwrap();
    let archivo = spanish
        .add(
            word("archivo", "noun", "aɾˈt͡ʃi.βo", &["archive, file"]),
            &["archivos"],
        )
        .unwrap();
    let mut computer = word("fichero", "noun", "fiˈt͡ʃe.ɾo", &["computer file"]);
    computer.forms.push(lexpack::Form {
        spelling: "ficheros".to_string(),
        label: "plural".to_string(),
    });
    let fichero = spanish.add::<&str>(computer, &[]).unwrap();
    let _ = fila;
    spanish.translated("file", "noun", &[(archivo, 0), (fichero, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&english),
        target: Some(&spanish),
        ..Open::default()
    };
    let alone = read_in_context("file", None, &lang("en"), &lang("es"), &open);
    assert_eq!(alone.says, ["archivo"]);
    let with_line = Open {
        said: Some("Guarda los ficheros en la carpeta."),
        ..open
    };
    let in_line = read_in_context("files", None, &lang("en"), &lang("es"), &with_line);
    assert_eq!(in_line.says, ["ficheros"]);
}

/// A word read in its own language is defined in it, where the language's own Wiktionary is
/// held beside its pack: a reader of Spanish is told what "perros" is in Spanish, not handed
/// its English gloss, which is a translation they did not ask for.
#[test]
fn a_word_read_in_its_own_language_is_defined_in_it() {
    let spanish = Pack::open(spanish()).unwrap();
    let mut own = Builder::new("def-es", Kind::Lex, 0).without_joins();
    own.add::<&str>(
        word(
            "perro",
            "noun",
            "",
            &["Mamífero doméstico de la familia de los cánidos."],
        ),
        &[],
    )
    .unwrap();
    let own = Pack::open(own.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&spanish),
        target: Some(&spanish),
        defines: Some(&own),
        ..Open::default()
    };
    let read = read_in_context("perros", None, &lang("es"), &lang("es"), &open);
    assert_eq!(
        read.says,
        ["Mamífero doméstico de la familia de los cánidos."]
    );
    // Read into English it is still the English.
    let english = read_in_context("perros", None, &lang("es"), &lang("en"), &open);
    assert_ne!(english.says, read.says);
}

/// A contraction read into Spanish is the words it contracts as Spanish writes them where they
/// meet: French "au", "à" and "le", is "al", never "a el".
#[test]
fn a_contraction_is_written_as_the_reader_writes_its_parts() {
    let mut french = Builder::new("fr", Kind::Lex, 0);
    french
        .add::<&str>(
            word("au", "contraction", "o", &["Contraction of à + le"]),
            &[],
        )
        .unwrap();
    french
        .add::<&str>(word("à", "prep", "a", &["to"]), &[])
        .unwrap();
    french
        .add::<&str>(word("le", "article", "lə", &["the"]), &[])
        .unwrap();
    let french = Pack::open(french.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let a = spanish
        .add::<&str>(word("a", "prep", "a", &["to, at"]), &[])
        .unwrap();
    let el = spanish
        .add::<&str>(word("el", "article", "el", &["the"]), &[])
        .unwrap();
    spanish.translated("to", "prep", &[(a, 0)]);
    spanish.translated("the", "article", &[(el, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&french),
        target: Some(&spanish),
        ..Open::default()
    };
    let au = read_in_context("au", None, &lang("fr"), &lang("es"), &open);
    assert_eq!(lexcore::annotate::lead(&au, &open).as_deref(), Some("al"));
}

/// Italian words of grammar read into Spanish as Spanish writes them. A contraction leads over
/// a word of grammar filed under the same spelling, which is a use of it: "al" is "a" and
/// "il", "al", where "al" the preposition, "with (an ingredient)", is "al pomodoro". A
/// contraction's parts may be joined by spaces alone: "della" is "di la", and its article
/// keeps its gender, "de la". A word of grammar is a word where its table has one: "di" is
/// "from" too, whose table lists the phrase "de parte de".
#[test]
fn italian_words_of_grammar_read_as_spanish_writes_them() {
    let mut italian = Builder::new("it", Kind::Lex, 0);
    italian
        .add::<&str>(
            word(
                "al",
                "contraction",
                "al",
                &["contraction of a + il, literally “to the, at the”"],
            ),
            &[],
        )
        .unwrap();
    // "alla" is also the feminine of the preposition "al", met through "con" far more than
    // the contraction's parts are.
    let mut with = word("al", "prep", "al", &["with (an ingredient)"]);
    with.forms.push(lexpack::Form {
        spelling: "alla".to_string(),
        label: "feminine".to_string(),
    });
    with.tags.push("count:1476453".to_string());
    italian.add(with, &["alla"]).unwrap();
    let mut contracted = word(
        "alla",
        "contraction",
        "ˈal.la",
        &["contraction of a + la; to the, at the"],
    );
    contracted.tags.push("count:469810".to_string());
    italian.add::<&str>(contracted, &[]).unwrap();
    let mut styled = word("alla", "prep", "ˈal.la", &["-style, à la"]);
    styled.tags.push("count:469810".to_string());
    italian.add::<&str>(styled, &[]).unwrap();
    italian
        .add::<&str>(word("a", "prep", "a", &["to"]), &[])
        .unwrap();
    italian
        .add::<&str>(word("il", "article", "il", &["the"]), &[])
        .unwrap();
    let mut la = word("la", "article", "la", &["the"]);
    la.senses[0].marks = vec!["feminine".to_string(), "singular".to_string()];
    italian.add::<&str>(la, &[]).unwrap();
    italian
        .add::<&str>(
            word(
                "di",
                "prep",
                "di",
                &[
                    "used to indicate possession, after the thing owned and before the owner; of; ’s",
                    "from",
                ],
            ),
            &[],
        )
        .unwrap();
    italian
        .add::<&str>(
            word(
                "della",
                "contraction",
                "ˈdel.la",
                &["contraction of di la; of the, from the"],
            ),
            &[],
        )
        .unwrap();
    let italian = Pack::open(italian.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let a = spanish
        .add::<&str>(word("a", "prep", "a", &["to, at"]), &[])
        .unwrap();
    let mut con = word("con", "prep", "kon", &["with"]);
    con.tags.push("count:1000000".to_string());
    let con = spanish.add::<&str>(con, &[]).unwrap();
    let mut el = word(
        "el",
        "article",
        "el",
        &["masculine singular definite article; the"],
    );
    el.forms.push(lexpack::Form {
        spelling: "los".to_string(),
        label: "plural".to_string(),
    });
    el.forms.push(lexpack::Form {
        spelling: "la".to_string(),
        label: "feminine".to_string(),
    });
    let el = spanish.add::<&str>(el, &[]).unwrap();
    let de = spanish
        .add::<&str>(
            word("de", "prep", "de", &["of; 's; used after the thing owned"]),
            &[],
        )
        .unwrap();
    let on_behalf = spanish
        .add::<&str>(
            word(
                "de parte de",
                "prep",
                "de ˈpaɾte de",
                &["from, on behalf of"],
            ),
            &[],
        )
        .unwrap();
    let del = spanish
        .add::<&str>(word("del", "contraction", "del", &["of the"]), &[])
        .unwrap();
    spanish.translated("of the", "contraction", &[(del, 0)]);
    spanish.translated("to", "prep", &[(a, 0)]);
    spanish.translated("with", "prep", &[(con, 0)]);
    spanish.translated("the", "article", &[(el, 0)]);
    spanish.translated("from", "prep", &[(de, 0), (on_behalf, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&italian),
        target: Some(&spanish),
        ..Open::default()
    };
    let lead = |word: &str| {
        let answer = read_in_context(word, None, &lang("it"), &lang("es"), &open);
        lexcore::annotate::lead(&answer, &open)
    };
    assert_eq!(lead("al").as_deref(), Some("al"));
    assert_eq!(lead("alla").as_deref(), Some("a la"));
    assert_eq!(lead("la").as_deref(), Some("la"));
    assert_eq!(lead("di").as_deref(), Some("de"));
    assert_eq!(lead("della").as_deref(), Some("de la"));
    // And no word of its own on its card: "of the" is "del", which is not "della".
    let della = read_in_context("della", None, &lang("it"), &lang("es"), &open);
    assert!(della.says.is_empty(), "{:?}", della.says);
    // On a line with another form of the article in it first, still its own.
    let on_line = Open {
        said: Some("Según los responsables de la investigación."),
        ..open
    };
    let la = read_in_context("la", None, &lang("it"), &lang("es"), &on_line);
    assert_eq!(
        lexcore::annotate::lead(&la, &on_line).as_deref(),
        Some("la")
    );
}

/// Among words of grammar under one spelling, a contraction that names its parts first: French
/// "des" is "de" and "les" on most lines, and as the plural article filed first it was "unos".
/// Not a "contraction" naming nothing: English "a" is also "going to", and is still "un".
#[test]
fn a_contraction_naming_its_parts_leads_its_spelling() {
    let mut french = Builder::new("fr", Kind::Lex, 0);
    let mut plural = word(
        "des",
        "article",
        "de",
        &["plural of un (“some”, the plural indefinite article)"],
    );
    plural.senses[0].marks = vec!["form-of".to_string(), "plural".to_string()];
    french.add::<&str>(plural, &[]).unwrap();
    french
        .add::<&str>(
            word(
                "des",
                "contraction",
                "de",
                &["contraction of de + les, literally “of the, from the, some”"],
            ),
            &[],
        )
        .unwrap();
    french
        .add::<&str>(word("un", "article", "œ̃", &["an, a"]), &[])
        .unwrap();
    french
        .add::<&str>(word("de", "prep", "də", &["of"]), &[])
        .unwrap();
    let mut les = word("les", "article", "le", &["the"]);
    les.senses[0].marks = vec!["plural".to_string()];
    french.add::<&str>(les, &[]).unwrap();
    let french = Pack::open(french.finish().unwrap()).unwrap();
    let mut english = Builder::new("en", Kind::Lex, 0);
    english
        .add::<&str>(
            word(
                "a",
                "contraction",
                "ə",
                &["Used to express a future action; going to."],
            ),
            &[],
        )
        .unwrap();
    english
        .add::<&str>(word("a", "article", "ə", &["one"]), &[])
        .unwrap();
    let english = Pack::open(english.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let mut un = word("un", "article", "un", &["a, an"]);
    un.forms.push(lexpack::Form {
        spelling: "unos".to_string(),
        label: "plural".to_string(),
    });
    let un = spanish.add::<&str>(un, &[]).unwrap();
    let de = spanish
        .add::<&str>(word("de", "prep", "de", &["of"]), &[])
        .unwrap();
    let mut el = word("el", "article", "el", &["the"]);
    el.forms.push(lexpack::Form {
        spelling: "los".to_string(),
        label: "plural".to_string(),
    });
    let el = spanish.add::<&str>(el, &[]).unwrap();
    spanish.translated("a", "article", &[(un, 0)]);
    spanish.translated("an", "article", &[(un, 0)]);
    spanish.translated("one", "article", &[(un, 0)]);
    spanish.translated("of", "prep", &[(de, 0)]);
    spanish.translated("the", "article", &[(el, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let lead = |word: &str, from: &str, pack: &Pack<Vec<u8>>| {
        let open = Open {
            source: Some(pack),
            target: Some(&spanish),
            ..Open::default()
        };
        let answer = read_in_context(word, None, &lang(from), &lang("es"), &open);
        lexcore::annotate::lead(&answer, &open)
    };
    assert_eq!(lead("des", "fr", &french).as_deref(), Some("de los"));
    assert_eq!(lead("a", "en", &english).as_deref(), Some("un"));
}

/// Through English, the term whose table has a word in the translated line: German "wurde"
/// on a line saying "fue" is the passive, which "get" reaches as "ser", not "to become",
/// "volverse", the first term with a table.
#[test]
fn the_line_picks_the_term_read_through() {
    let mut german = Builder::new("de", Kind::Lex, 0);
    let mut werden = word("werden", "verb", "ˈveːɐ̯dn̩", &["to become; to get"]);
    werden.forms.push(lexpack::Form {
        spelling: "wurde".to_string(),
        label: "preterite indicative third-person singular".to_string(),
    });
    german.add(werden, &["wurde"]).unwrap();
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let volverse = spanish
        .add::<&str>(word("volverse", "verb", "bolˈbeɾse", &["to become"]), &[])
        .unwrap();
    let mut ser = word("ser", "verb", "seɾ", &["to be"]);
    ser.forms.push(lexpack::Form {
        spelling: "fue".to_string(),
        label: "preterite indicative third-person singular".to_string(),
    });
    let ser = spanish.add(ser, &["fue"]).unwrap();
    spanish.translated("become", "verb", &[(volverse, 0)]);
    spanish.translated("get", "verb", &[(ser, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&german),
        target: Some(&spanish),
        said: Some("El hombre fue encontrado."),
        ..Open::default()
    };
    let answer = read_in_context("wurde", None, &lang("de"), &lang("es"), &open);
    assert_eq!(
        lexcore::annotate::lead(&answer, &open).as_deref(),
        Some("fue")
    );
}

/// A note about grammar means the first short meaning after it: German "sich" is "Reflexive
/// pronoun of the third person singular or plural: herself, himself, itself, ...", and by its
/// last part it meant nothing.
#[test]
fn a_note_means_its_first_short_meaning() {
    let mut german = Builder::new("de", Kind::Lex, 0);
    german
        .add::<&str>(
            word(
                "sich",
                "pron",
                "zɪç",
                &[
                    "Reflexive pronoun of the third person singular or plural: herself, himself, \
                   itself, oneself, themselves (in both dative and accusative) of the third \
                   person singular or plural",
                ],
            ),
            &[],
        )
        .unwrap();
    let german = Pack::open(german.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let se = spanish
        .add::<&str>(
            word(
                "se",
                "pron",
                "se",
                &["reflexive pronoun of the third person"],
            ),
            &[],
        )
        .unwrap();
    spanish.translated("herself", "pron", &[(se, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&german),
        target: Some(&spanish),
        ..Open::default()
    };
    let answer = read_in_context("sich", None, &lang("de"), &lang("es"), &open);
    assert_eq!(answer.says, vec!["se"]);
}

/// Italian "con" is "with": not the obsolete apocope of "come", "how", which the dump lists
/// among come's forms, and by "with" though its gloss goes on to "together", which a word of
/// grammar's tie word was dropped beside. French "pas" is "not" where a definition says so in a
/// sentence, "typically translating into English as not", and "no" on a line saying it.
#[test]
fn words_of_grammar_by_what_their_glosses_say() {
    let mut italian = Builder::new("it", Kind::Lex, 0);
    let mut come = word("come", "adv", "ˈko.me", &["how", "as, like"]);
    come.tags.push("count:2749856".to_string());
    come.forms.push(lexpack::Form {
        spelling: "con".to_string(),
        label: "alternative apocopic obsolete".to_string(),
    });
    italian.add(come, &["con"]).unwrap();
    let mut con = word("con", "prep", "kon", &["with, together"]);
    con.tags.push("count:1440540".to_string());
    italian.add::<&str>(con, &[]).unwrap();
    let italian = Pack::open(italian.finish().unwrap()).unwrap();
    let mut french = Builder::new("fr", Kind::Lex, 0);
    french
        .add::<&str>(word("pas", "noun", "pa", &["step, pace, footstep"]), &[])
        .unwrap();
    french
        .add::<&str>(
            word(
                "pas",
                "adv",
                "pa",
                &[
                    "The most common adverb of negation in French, typically translating into \
                   English as not, don't, doesn't, etc.",
                ],
            ),
            &[],
        )
        .unwrap();
    let french = Pack::open(french.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let con = spanish
        .add::<&str>(word("con", "prep", "kon", &["with"]), &[])
        .unwrap();
    let together = spanish
        .add::<&str>(word("junto", "adv", "ˈxun.to", &["together"]), &[])
        .unwrap();
    let mut apenas = word("apenas", "adv", "aˈpe.nas", &["hardly; as soon as"]);
    apenas.tags.push("count:900000".to_string());
    let apenas = spanish.add::<&str>(apenas, &[]).unwrap();
    let no = spanish
        .add::<&str>(word("no", "adv", "no", &["not"]), &[])
        .unwrap();
    let paso = spanish
        .add::<&str>(word("paso", "noun", "ˈpa.so", &["step"]), &[])
        .unwrap();
    spanish.translated("with", "prep", &[(con, 0)]);
    spanish.translated("together", "prep", &[(together, 0)]);
    spanish.translated("how", "adv", &[(apenas, 0)]);
    spanish.translated("not", "adv", &[(no, 0)]);
    spanish.translated("step", "noun", &[(paso, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = |pack| Open {
        source: Some(pack),
        target: Some(&spanish),
        said: Some("No es fácil."),
        ..Open::default()
    };
    let (italian_open, french_open) = (open(&italian), open(&french));
    let con = read_in_context("con", None, &lang("it"), &lang("es"), &italian_open);
    assert_eq!(
        lexcore::annotate::lead(&con, &italian_open).as_deref(),
        Some("con")
    );
    assert!(
        con.readings
            .iter()
            .all(|reading| reading.pos.as_deref() == Some("prep")),
        "come's obsolete apocope is no reading of con: {:?}",
        con.readings
    );
    let pas = read_in_context("pas", None, &lang("fr"), &lang("es"), &french_open);
    assert_eq!(
        lexcore::annotate::lead(&pas, &french_open).as_deref(),
        Some("no")
    );
}

/// A word of grammar that says nothing of its own is the reading of its own kind that does:
/// Italian "gli" the article is filed as a form of "i", which is "los", and the pronoun "gli",
/// "le", led every "gli" on a page.
#[test]
fn a_silent_word_of_grammar_is_its_own_kind() {
    let mut italian = Builder::new("it", Kind::Lex, 0);
    let mut article = word(
        "gli",
        "article",
        "ʎi",
        &[
            "form of the article i (“the”) used before a vowel, impure s, gn, pn, ps, sc, x, y, \
           and z; the of the article i",
        ],
    );
    article.senses[0].marks = vec![
        "form-of".to_string(),
        "masculine".to_string(),
        "plural".to_string(),
    ];
    italian.add::<&str>(article, &[]).unwrap();
    italian
        .add::<&str>(word("gli", "pron", "ʎi", &["him, to him; it; to it"]), &[])
        .unwrap();
    let mut i = word("i", "article", "i", &["the"]);
    i.senses[0].marks = vec!["masculine".to_string(), "plural".to_string()];
    i.forms.push(lexpack::Form {
        spelling: "gli".to_string(),
        label: "masculine plural".to_string(),
    });
    italian.add(i, &["gli"]).unwrap();
    let italian = Pack::open(italian.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let le = spanish
        .add::<&str>(word("le", "pron", "le", &["to him"]), &[])
        .unwrap();
    let mut el = word("el", "article", "el", &["the"]);
    el.forms.push(lexpack::Form {
        spelling: "los".to_string(),
        label: "plural".to_string(),
    });
    let el = spanish.add::<&str>(el, &[]).unwrap();
    spanish.translated("him", "pron", &[(le, 0)]);
    spanish.translated("the", "article", &[(el, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&italian),
        target: Some(&spanish),
        ..Open::default()
    };
    let gli = read_in_context("gli", None, &lang("it"), &lang("es"), &open);
    assert_eq!(lexcore::annotate::lead(&gli, &open).as_deref(), Some("los"));
}

/// An entry that only says which form of another word it is reads as that word in that form:
/// Italian "stato" is filed as "past participle of essere", which essere's own forms do not
/// list, and it answered nothing beside the noun, the state; on a line saying "sido" it is
/// "sido". And a numeral keeps its gender: "una" is "una", not "uno".
#[test]
fn a_pointer_is_its_word_in_its_form() {
    let mut italian = Builder::new("it", Kind::Lex, 0);
    italian
        .add::<&str>(word("stato", "noun", "ˈsta.to", &["state"]), &[])
        .unwrap();
    let mut pointer = word("stato", "verb", "ˈsta.to", &["past participle of essere"]);
    pointer.senses[0].marks = vec![
        "form-of".to_string(),
        "participle".to_string(),
        "past".to_string(),
    ];
    italian.add::<&str>(pointer, &[]).unwrap();
    italian
        .add::<&str>(word("essere", "verb", "ˈɛs.se.re", &["to be"]), &[])
        .unwrap();
    let mut uno = word("uno", "num", "ˈu.no", &["one"]);
    uno.forms.push(lexpack::Form {
        spelling: "una".to_string(),
        label: "feminine".to_string(),
    });
    italian.add(uno, &["una"]).unwrap();
    let italian = Pack::open(italian.finish().unwrap()).unwrap();
    let mut spanish = Builder::new("es", Kind::Lex, 0);
    let estado = spanish
        .add::<&str>(word("estado", "noun", "esˈta.do", &["state"]), &[])
        .unwrap();
    let mut ser = word("ser", "verb", "seɾ", &["to be"]);
    ser.forms.push(lexpack::Form {
        spelling: "sido".to_string(),
        label: "participle past".to_string(),
    });
    let ser = spanish.add(ser, &["sido"]).unwrap();
    let mut one = word("uno", "num", "ˈu.no", &["one"]);
    one.forms.push(lexpack::Form {
        spelling: "una".to_string(),
        label: "feminine".to_string(),
    });
    let one = spanish.add(one, &["una"]).unwrap();
    spanish.translated("state", "noun", &[(estado, 0)]);
    spanish.translated("be", "verb", &[(ser, 0)]);
    spanish.translated("one", "num", &[(one, 0)]);
    let spanish = Pack::open(spanish.finish().unwrap()).unwrap();
    let open = Open {
        source: Some(&italian),
        target: Some(&spanish),
        said: Some("Ha sido una sorpresa."),
        ..Open::default()
    };
    let lead = |word: &str| {
        let answer = read_in_context(word, None, &lang("it"), &lang("es"), &open);
        lexcore::annotate::lead(&answer, &open)
    };
    assert_eq!(lead("stato").as_deref(), Some("sido"));
    let alone = Open { said: None, ..open };
    let una = read_in_context("una", None, &lang("it"), &lang("es"), &alone);
    assert_eq!(
        lexcore::annotate::lead(&una, &alone).as_deref(),
        Some("una")
    );
}
