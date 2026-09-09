//! The core's detector against eld's own answers.
//!
//! The point of porting eld rather than inventing something is that both platforms agree
//! about what language a line is in. These are the answers eld itself gave for these texts,
//! and the port has to give the same ones: the language, whether it is worth acting on, and
//! the score to four places.

use std::fs;

use lexcore::detect::Model;

fn model() -> Model {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/eld.bin");
    let bytes = fs::read(path).expect("tests/fixtures/eld.bin is missing");
    Model::open(&bytes).expect("the model opens")
}

fn cases() -> serde_json::Value {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/detect.json");
    let text = fs::read_to_string(path).expect("tests/fixtures/detect.json is missing");
    serde_json::from_str(&text).expect("the cases are not JSON")
}

#[test]
fn the_model_holds_the_languages_it_held() {
    let model = model();
    assert!(model.languages().len() >= 50, "{}", model.languages().len());
    assert!(model.languages().iter().any(|code| code == "de"));
    assert!(model.languages().iter().any(|code| code == "en"));
}

#[test]
fn every_text_is_read_the_way_eld_read_it() {
    let model = model();
    let cases = cases();
    let cases = cases.as_array().expect("no cases");
    assert!(cases.len() > 20, "only {} cases", cases.len());
    let mut wrong = Vec::new();
    for case in cases {
        let text = case["text"].as_str().unwrap();
        let want = case["language"].as_str().unwrap();
        let reliable = case["reliable"].as_bool().unwrap();
        let score = case["score"].as_f64().unwrap() as f32;
        let got = model.detect(text);
        let language = got.language.clone().unwrap_or_default();
        let best = got
            .scores
            .iter()
            .find(|(code, _)| *code == language)
            .map(|(_, score)| *score)
            .unwrap_or(0.0);
        if language != want || got.reliable != reliable || (best - score).abs() > 0.0005 {
            wrong.push(format!(
                "{text:?}: {language} {} {:.4}, expected {want} {reliable} {score:.4}",
                got.reliable, best
            ));
        }
    }
    assert!(
        wrong.is_empty(),
        "{} of {} differ: {:#?}",
        wrong.len(),
        cases.len(),
        &wrong[..wrong.len().min(6)]
    );
}

#[test]
fn a_screen_with_too_little_on_it_is_not_judged() {
    let model = model();
    let read = lexcore::detect::read_screen(Some(&model), "Save Cancel Open");
    assert!(!read.enough, "three labels are not a screen");
    assert_eq!(read.language, None);
}

#[test]
fn a_screen_with_enough_on_it_says_what_it_is() {
    let model = model();
    let read = lexcore::detect::read_screen(
        Some(&model),
        "Der Lesesaal ist bis zweiundzwanzig Uhr geoeffnet und die Buecher bleiben hier",
    );
    assert!(read.enough);
    assert_eq!(read.language.as_deref(), Some("de"));
}

#[test]
fn a_screen_read_without_a_model_says_only_how_much_it_had() {
    let read = lexcore::detect::read_screen(
        None,
        "The dictionary answers immediately and the page carries on reading",
    );
    assert!(read.enough);
    assert_eq!(read.language, None);
}
