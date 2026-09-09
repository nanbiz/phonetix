//! The core's symbols against the vectors both platforms already agreed on.
//!
//! Splitting a transcription, naming a symbol and deciding how much detail to show were
//! written twice, once in TypeScript and once in Kotlin, with these vectors keeping the two
//! honest. The logic is the core's now, and this is what says the move changed nothing.

use std::fs;

use lexcore::symbols::{describe, display, tokenize};

fn vectors() -> serde_json::Value {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../shared/ipa-symbols.json");
    let text = fs::read_to_string(path).expect("shared/ipa-symbols.json is missing");
    serde_json::from_str(&text).expect("the vectors are not JSON")
}

#[test]
fn a_transcription_splits_into_the_same_symbols() {
    let data = vectors();
    let cases = data["cases"].as_array().expect("no cases");
    assert!(!cases.is_empty());
    for case in cases {
        let ipa = case["ipa"].as_str().unwrap();
        let want: Vec<&str> = case["tokens"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| t.as_str().unwrap())
            .collect();
        assert_eq!(tokenize(ipa), want, "splitting {ipa}");
    }
}

#[test]
fn every_symbol_is_called_what_it_was_called() {
    let data = vectors();
    let mut wrong = Vec::new();
    for case in data["cases"].as_array().unwrap() {
        let tokens = case["tokens"].as_array().unwrap();
        let names = case["names"].as_array().unwrap();
        for (token, name) in tokens.iter().zip(names) {
            let token = token.as_str().unwrap();
            let want = name.as_str().unwrap();
            let got = describe(token).map(|s| s.name).unwrap_or_default();
            if got != want {
                wrong.push(format!("{token}: {got:?} not {want:?}"));
            }
        }
    }
    assert!(wrong.is_empty(), "{wrong:?}");
}

#[test]
fn the_table_holds_every_symbol_it_held() {
    let data = vectors();
    let symbols = data["symbols"].as_object().expect("no symbols");
    let mut missing = Vec::new();
    for (token, row) in symbols {
        match describe(token) {
            Some(found) if found.name == row["name"].as_str().unwrap_or_default() => {}
            Some(found) => missing.push(format!("{token}: {} not {}", found.name, row["name"])),
            None => missing.push(format!("{token}: gone")),
        }
    }
    assert!(
        missing.is_empty(),
        "{} rows differ: {:?}",
        missing.len(),
        &missing[..missing.len().min(8)]
    );
}

#[test]
fn a_transcription_is_shown_the_way_it_was_shown() {
    let data = vectors();
    let mut wrong = Vec::new();
    for case in data["display"].as_array().expect("no display cases") {
        let ipa = case["ipa"].as_str().unwrap();
        for (field, narrow, hide) in [
            ("broad", false, true),
            ("narrow", true, true),
            ("withStress", false, false),
        ] {
            let want = case[field].as_str().unwrap();
            let got = display(ipa, narrow, hide);
            if got != want {
                wrong.push(format!("{ipa} {field}: {got:?} not {want:?}"));
            }
        }
    }
    assert!(
        wrong.is_empty(),
        "{} of them differ: {:?}",
        wrong.len(),
        &wrong[..wrong.len().min(8)]
    );
}
