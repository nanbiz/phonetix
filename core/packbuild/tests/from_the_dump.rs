//! Lines of the dump in, a language pair out.
//!
//! The pack's own tests build entries by hand, which proves the format. This proves the thing
//! the product rests on: two languages extracted separately, neither knowing the other exists,
//! and a reader of one reading the other.

use lexpack::{Builder, Kind, Pack};
use packbuild::{read_line, Skipped};

const SPANISH: &str = r#"
{"word":"perro","pos":"noun","lang_code":"es","senses":[{"glosses":["dog"]}],"sounds":[{"ipa":"/ˈpe.ro/"}],"forms":[{"form":"perros","tags":["plural"]}]}
{"word":"silla","pos":"noun","lang_code":"es","senses":[{"glosses":["chair"]}],"sounds":[{"ipa":"/ˈsi.ʝa/"}],"forms":[{"form":"sillas"}]}
{"word":"camino","pos":"noun","lang_code":"es","senses":[{"glosses":["way, route"]}],"sounds":[{"ipa":"/kaˈmi.no/"}]}
"#;

const GERMAN: &str = r#"
{"word":"Hund","pos":"noun","lang_code":"de","senses":[{"glosses":["dog, hound"]}],"sounds":[{"ipa":"/hʊnt/"}],"forms":[{"form":"Hunde"}]}
{"word":"Rüde","pos":"noun","lang_code":"de","senses":[{"glosses":["male dog"]}]}
{"word":"Stuhl","pos":"noun","lang_code":"de","senses":[{"glosses":["a chair (to sit on)"]}]}
{"word":"Sessel","pos":"noun","lang_code":"de","senses":[{"glosses":["armchair"]}]}
{"word":"Weg","pos":"noun","lang_code":"de","senses":[{"glosses":["route, way (to get from one place to another)"]}]}
{"word":"Weise","pos":"noun","lang_code":"de","senses":[{"glosses":["way, manner"]}]}
"#;

fn build(lang: &str, lines: &str) -> Vec<u8> {
    let mut pack = Builder::new(lang, Kind::Lex, 0);
    let mut skipped = Skipped::default();
    for line in lines.lines() {
        if let Some(read) = read_line(line, lang, &mut skipped) {
            pack.add(read.entry, &read.forms).unwrap();
        }
    }
    assert_eq!(
        skipped,
        Skipped::default(),
        "nothing here should be skipped"
    );
    pack.finish().unwrap()
}

/// What the core will do with a word: its sense's English gloss, looked up in the other pack,
/// best match first.
fn reached<D: AsRef<[u8]>>(source: &Pack<D>, target: &Pack<D>, word: &str) -> Vec<String> {
    let entry = source
        .lookup_one(word)
        .expect("the source pack should hold this word");
    target
        .senses_matching(&entry.senses[0].gloss)
        .iter()
        .map(|((which, _), _)| target.entry(*which).unwrap().lemma)
        .collect()
}

#[test]
fn two_languages_extracted_apart_make_a_pair() {
    let es_bytes = build("es", SPANISH);
    let de_bytes = build("de", GERMAN);
    let es = Pack::open(&es_bytes).unwrap();
    let de = Pack::open(&de_bytes).unwrap();

    for (word, wanted, refused) in [
        ("perro", "Hund", "Rüde"),
        ("silla", "Stuhl", "Sessel"),
        ("camino", "Weg", "Weise"),
    ] {
        let got = reached(&es, &de, word);
        // Best first: a gloss of several terms reaches the word that shares most of them, and
        // the near miss it used to be confused with comes below it or not at all.
        assert_eq!(
            got.first().map(String::as_str),
            Some(wanted),
            "{word} reached {got:?}"
        );
        assert_ne!(got.first().map(String::as_str), Some(refused));
    }
}

/// A reader of Spanish reading German, which is the same two packs the other way round and
/// needs nothing built for it.
#[test]
fn the_pair_works_in_both_directions() {
    let es_bytes = build("es", SPANISH);
    let de_bytes = build("de", GERMAN);
    let es = Pack::open(&es_bytes).unwrap();
    let de = Pack::open(&de_bytes).unwrap();
    assert!(reached(&de, &es, "Hund").contains(&"perro".to_string()));
    assert!(reached(&de, &es, "Stuhl").contains(&"silla".to_string()));
}

#[test]
fn an_inflected_spelling_from_the_dump_reaches_its_lemma() {
    let bytes = build("es", SPANISH);
    let pack = Pack::open(&bytes).unwrap();
    assert_eq!(pack.lookup_one("perros").unwrap().lemma, "perro");
    assert_eq!(pack.lookup_one("sillas").unwrap().lemma, "silla");
    assert!(
        pack.lookup("Hunde").is_empty(),
        "a German form is not in the Spanish pack"
    );
}

#[test]
fn the_transcription_survives_the_whole_way() {
    let bytes = build("es", SPANISH);
    let pack = Pack::open(&bytes).unwrap();
    // The delimiters belong to the notation and are not part of what is shown.
    assert_eq!(pack.lookup_one("perro").unwrap().ipa, vec!["ˈpe.ro"]);
}
