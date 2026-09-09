//! A pack written and then read back, and the join two packs make.
//!
//! The join is the whole reason for the format, so it is tested on the words that defeated the
//! approaches this one replaced: Spanish perro against German Hund rather than Ruede, silla
//! against Stuhl rather than Sessel, camino against Weg rather than Weise.

use lexpack::{Builder, Entry, Kind, Pack, Sense};

/// No inflected forms, spelled so the compiler knows which kind of nothing it is.
const NO_FORMS: [&str; 0] = [];

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
    let mut pack = Builder::new("es", Kind::Lex, 1_757_000_000);
    pack.add(word("perro", "noun", "ˈpe.ro", &["dog"]), &["perros"])
        .unwrap();
    pack.add(word("silla", "noun", "ˈsi.ʝa", &["chair"]), &["sillas"])
        .unwrap();
    pack.add(
        word("camino", "noun", "kaˈmi.no", &["way, route"]),
        &["caminos"],
    )
    .unwrap();
    pack.finish().unwrap()
}

fn german() -> Vec<u8> {
    let mut pack = Builder::new("de", Kind::Lex, 1_757_000_000);
    pack.add(
        word("Hund", "noun", "hʊnt", &["dog, hound"]),
        &["Hunde", "Hundes"],
    )
    .unwrap();
    pack.add(word("Rüde", "noun", "ˈʁyːdə", &["male dog"]), &["Rüden"])
        .unwrap();
    pack.add(
        word("Stuhl", "noun", "ʃtuːl", &["a chair (to sit on)"]),
        &["Stühle"],
    )
    .unwrap();
    pack.add(word("Sessel", "noun", "ˈzɛsl̩", &["armchair"]), &NO_FORMS)
        .unwrap();
    pack.add(
        word(
            "Weg",
            "noun",
            "veːk",
            &["route, way (to get from one place to another)"],
        ),
        &["Wege"],
    )
    .unwrap();
    pack.add(
        word("Weise", "noun", "ˈvaɪ̯zə", &["way, manner"]),
        &["Weisen"],
    )
    .unwrap();
    pack.finish().unwrap()
}

#[test]
fn a_word_comes_back_as_it_went_in() {
    let bytes = spanish();
    let pack = Pack::open(&bytes).unwrap();
    assert_eq!(pack.lang(), "es");
    assert_eq!(pack.kind(), Kind::Lex);
    assert_eq!(pack.len(), 3);
    let perro = pack.lookup("perro").unwrap();
    assert_eq!(perro.lemma, "perro");
    assert_eq!(perro.pos, "noun");
    assert_eq!(perro.ipa, vec!["ˈpe.ro"]);
    assert_eq!(perro.senses.len(), 1);
    assert_eq!(perro.senses[0].gloss, "dog");
}

#[test]
fn an_inflected_spelling_reaches_its_lemma() {
    let bytes = spanish();
    let pack = Pack::open(&bytes).unwrap();
    assert_eq!(pack.lookup("perros").unwrap().lemma, "perro");
    assert_eq!(pack.lookup("caminos").unwrap().lemma, "camino");
    assert!(
        pack.lookup("perrito").is_none(),
        "a word the pack does not hold is a miss"
    );
}

#[test]
fn the_words_that_defeated_the_pivot_join_correctly() {
    let es_bytes = spanish();
    let de_bytes = german();
    let es = Pack::open(&es_bytes).unwrap();
    let de = Pack::open(&de_bytes).unwrap();

    // What the core will do: take the source sense's English gloss and look it up in the
    // target's gloss index.
    for (spanish_word, expected) in [("perro", "Hund"), ("silla", "Stuhl"), ("camino", "Weg")] {
        let source = es.lookup(spanish_word).unwrap();
        let hits = de.senses_glossed(&source.senses[0].gloss);
        let reached: Vec<String> = hits
            .iter()
            .map(|(entry, _)| de.entry(*entry).unwrap().lemma)
            .collect();
        assert!(
            reached.contains(&expected.to_string()),
            "{spanish_word} should reach {expected}, reached {reached:?}"
        );
    }
}

#[test]
fn the_confident_wrong_answers_are_not_reached() {
    let es_bytes = spanish();
    let de_bytes = german();
    let es = Pack::open(&es_bytes).unwrap();
    let de = Pack::open(&de_bytes).unwrap();
    // Ruede glosses "male dog" and Sessel "armchair", so neither shares a head term with the
    // Spanish sense; Weise glosses "way, manner" and does share one with "way, route", which is
    // what the further terms of the gloss are for and is left to the core to score.
    let perro = es.lookup("perro").unwrap();
    let reached: Vec<String> = de
        .senses_glossed(&perro.senses[0].gloss)
        .iter()
        .map(|(entry, _)| de.entry(*entry).unwrap().lemma)
        .collect();
    assert!(
        !reached.contains(&"Rüde".to_string()),
        "reached {reached:?}"
    );

    let silla = es.lookup("silla").unwrap();
    let reached: Vec<String> = de
        .senses_glossed(&silla.senses[0].gloss)
        .iter()
        .map(|(entry, _)| de.entry(*entry).unwrap().lemma)
        .collect();
    assert!(
        !reached.contains(&"Sessel".to_string()),
        "reached {reached:?}"
    );
}

#[test]
fn a_gloss_term_that_reaches_two_words_says_so() {
    // "way" alone reaches both Weg and Weise, which is the ambiguity the core refuses to
    // guess through. The pack's job is to report both rather than to pick.
    let de_bytes = german();
    let de = Pack::open(&de_bytes).unwrap();
    let reached: Vec<String> = de
        .senses_glossed("way")
        .iter()
        .map(|(entry, _)| de.entry(*entry).unwrap().lemma)
        .collect();
    assert!(
        reached.contains(&"Weg".to_string()) && reached.contains(&"Weise".to_string()),
        "reached {reached:?}"
    );
}

#[test]
fn a_pack_bigger_than_one_block_still_answers() {
    // The block size is what makes a lookup cheap, so the case that matters is a pack with
    // several of them: every entry has to be findable, not only the ones in the first block.
    let mut pack = Builder::new("xx", Kind::Lex, 0);
    let count = lexpack::ENTRIES_PER_BLOCK * 3 + 7;
    for n in 0..count {
        pack.add(
            word(
                &format!("word{n:04}"),
                "noun",
                "x",
                &[&format!("meaning{n}")],
            ),
            &NO_FORMS,
        )
        .unwrap();
    }
    let bytes = pack.finish().unwrap();
    let read = Pack::open(&bytes).unwrap();
    assert_eq!(read.len(), count);
    for n in [0, 1, 63, 64, 65, 127, 128, count - 1] {
        let entry = read.lookup(&format!("word{n:04}")).unwrap();
        assert_eq!(entry.lemma, format!("word{n:04}"));
        assert_eq!(entry.senses[0].gloss, format!("meaning{n}"));
    }
}

#[test]
fn a_spelling_claimed_twice_is_refused_rather_than_silently_dropped() {
    let mut pack = Builder::new("xx", Kind::Lex, 0);
    pack.add(word("book", "noun", "bʊk", &["a book"]), &NO_FORMS)
        .unwrap();
    assert!(pack
        .add(word("book", "verb", "bʊk", &["to book"]), &NO_FORMS)
        .is_err());
}

#[test]
fn an_empty_pack_opens_and_holds_nothing() {
    let pack = Builder::new("xx", Kind::Lex, 0).finish().unwrap();
    let read = Pack::open(&pack).unwrap();
    assert!(read.is_empty());
    assert!(read.lookup("anything").is_none());
    assert!(read.senses_glossed("anything").is_empty());
}
