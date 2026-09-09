//! The core's sprinkle against what the two platforms answered before it had it.
//!
//! The rule was TypeScript with a Kotlin port asserting itself against generated vectors.
//! It is the core's now, and these are the answers those two agreed on: the same word,
//! occurrence and density have to be picked the same way, or one setting quietly means two
//! different pages.

use std::fs;

use lexcore::sprinkle::{density_for_pos, picks, pos_for_density, DENSITY_MAX, DENSITY_MIN};

fn vectors() -> serde_json::Value {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/sprinkle.json");
    let text = fs::read_to_string(path).expect("core/tests/fixtures/sprinkle.json is missing");
    serde_json::from_str(&text).expect("the vectors are not JSON")
}

#[test]
fn the_ends_of_the_bar_are_the_ones_both_platforms_hold() {
    let data = vectors();
    assert_eq!(data["densityMin"].as_u64().unwrap() as u32, DENSITY_MIN);
    assert_eq!(data["densityMax"].as_u64().unwrap() as u32, DENSITY_MAX);
}

#[test]
fn every_word_is_picked_the_way_it_was() {
    let data = vectors();
    let cases = data["picks"].as_array().expect("no pick cases");
    assert!(cases.len() > 100, "only {} cases", cases.len());
    let mut wrong = Vec::new();
    for case in cases {
        let word = case["word"].as_str().unwrap();
        let occurrence = case["occurrence"].as_u64().unwrap() as u32;
        let density = case["density"].as_u64().unwrap() as u32;
        let want = case["picked"].as_bool().unwrap();
        if picks(word, occurrence, density) != want {
            wrong.push(format!("{word}#{occurrence} at 1 in {density}"));
        }
    }
    assert!(
        wrong.is_empty(),
        "{} of {} disagree: {:?}",
        wrong.len(),
        cases.len(),
        &wrong[..wrong.len().min(8)]
    );
}

#[test]
fn the_bar_maps_to_the_same_densities() {
    let data = vectors();
    for point in data["curve"].as_array().expect("no curve") {
        let position = point["pos"].as_f64().unwrap();
        let want = point["density"].as_u64().unwrap() as u32;
        assert_eq!(density_for_pos(position), want, "at {position}");
    }
}

#[test]
fn a_density_maps_back_to_the_same_place_on_the_bar() {
    let data = vectors();
    for point in data["inverse"].as_array().expect("no inverse") {
        let density = point["density"].as_u64().unwrap() as u32;
        let want = point["pos"].as_f64().unwrap();
        let got = pos_for_density(density, 100);
        assert!(
            (got - want).abs() < 1e-9,
            "1 in {density} came back at {got}, not {want}"
        );
    }
}
