//! A pack of how words are said, built and read back.
//!
//! The app used to carry a compressed map of word to transcription, which was a second format
//! only one platform could read. This is that table as a pack: the same reader, the same
//! lookup, and nothing platform-specific about where a pronunciation comes from.

use std::io::Write;

use lexpack::{Builder, Entry, Kind, Pack};

fn a_pack() -> Vec<u8> {
    let mut pack = Builder::new("en", Kind::Ipa, 0);
    for (word, ipa) in [
        ("paragraph", "ˈpæɹəɡɹæf"),
        ("pronunciation", "pɹəˌnʌnsiˈeɪʃən"),
        ("dictionary", "ˈdɪkʃənɛɹi"),
    ] {
        pack.add::<&str>(
            Entry {
                lemma: word.to_string(),
                pos: String::new(),
                ipa: vec![ipa.to_string()],
                tags: Vec::new(),
                senses: Vec::new(),
                forms: Vec::new(),
            },
            &[],
        )
        .expect("the pack takes a word");
    }
    pack.finish().expect("the pack is written")
}

#[test]
fn a_word_comes_back_with_how_it_is_said() {
    let bytes = a_pack();
    let pack = Pack::open(bytes).expect("the pack opens");
    let found = pack.lookup_one("dictionary").expect("the word is in it");
    assert_eq!(found.ipa, ["ˈdɪkʃənɛɹi"]);
    assert!(
        found.senses.is_empty(),
        "a pronunciation pack holds no senses"
    );
}

#[test]
fn a_word_it_does_not_hold_is_absent_rather_than_wrong() {
    let pack = Pack::open(a_pack()).expect("the pack opens");
    assert!(pack.lookup_one("murciélago").is_none());
}

#[test]
fn the_pack_says_what_it_is() {
    let pack = Pack::open(a_pack()).expect("the pack opens");
    assert_eq!(pack.lang(), "en");
}

#[test]
fn it_survives_a_round_trip_through_a_file() {
    let path = std::env::temp_dir().join("phonetix-ipa-roundtrip.pack");
    let bytes = a_pack();
    std::fs::File::create(&path)
        .and_then(|mut f| f.write_all(&bytes))
        .expect("the file is written");
    let read = std::fs::read(&path).expect("the file is read");
    let pack = Pack::open(read).expect("the pack opens from disk");
    assert_eq!(
        pack.lookup_one("paragraph").expect("the word is in it").ipa,
        ["ˈpæɹəɡɹæf"]
    );
    let _ = std::fs::remove_file(path);
}
