//! The browser's way into the core.
//!
//! A thin wrapper and nothing else: no logic lives here, because anything that decides
//! something would then exist only for one platform, which is the drift this arrangement is
//! meant to make impossible.

use wasm_bindgen::prelude::*;

/// How many terms two glosses share, which is what decides whether a word in one language
/// answers a word in another.
#[wasm_bindgen]
pub fn overlap(source: &str, target: &str) -> usize {
    lexcore::gloss::overlap(source, target)
}

/// The candidate that shares most, or nothing when the best does not clearly beat the rest.
///
/// Returns -1 for no answer rather than throwing, since an ambiguous join is an ordinary
/// outcome the host handles by falling to its engine, not an error.
#[wasm_bindgen]
pub fn best_of(source: &str, candidates: Vec<String>, margin: usize) -> i32 {
    let refs: Vec<&str> = candidates.iter().map(|s| s.as_str()).collect();
    match lexcore::gloss::best_of(source, &refs, margin) {
        Some(i) => i as i32,
        None => -1,
    }
}
