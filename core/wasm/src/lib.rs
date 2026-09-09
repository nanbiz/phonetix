//! The browser's way into the core.
//!
//! A thin wrapper and nothing else: no logic lives here, because anything that decides
//! something would then exist only for one platform, which is the drift this arrangement is
//! meant to make impossible. What crosses the boundary is a pack's bytes, once, and a word at
//! a time after that.

use std::collections::HashMap;

use lexcore::answer::Lang;
use lexcore::resolve::{look_up, Open};
use lexpack::Pack;
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

/// The core, holding whatever packs the host has given it.
///
/// The bytes are taken rather than borrowed: a browser's buffer belongs to the garbage
/// collector and there is nothing to borrow it from for as long as a pack is open.
#[wasm_bindgen]
pub struct Core {
    packs: HashMap<String, Pack<Vec<u8>>>,
}

#[wasm_bindgen]
impl Core {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Core {
        Core {
            packs: HashMap::new(),
        }
    }

    /// Take a pack's bytes. Returns the language it turned out to be for, or throws when the
    /// bytes are not a pack this reader knows.
    #[wasm_bindgen(js_name = openPack)]
    pub fn open_pack(&mut self, bytes: Vec<u8>) -> Result<String, JsError> {
        let pack = Pack::open(bytes).map_err(|e| JsError::new(&format!("{e:?}")))?;
        let lang = pack.lang().to_string();
        self.packs.insert(lang.clone(), pack);
        Ok(lang)
    }

    #[wasm_bindgen(js_name = closePack)]
    pub fn close_pack(&mut self, lang: &str) {
        self.packs.remove(lang);
    }

    /// Which languages the core can answer for.
    #[wasm_bindgen]
    pub fn languages(&self) -> Vec<String> {
        let mut out: Vec<String> = self.packs.keys().cloned().collect();
        out.sort();
        out
    }

    /// One word, as JSON, because an Answer is a tree and the boundary carries text.
    #[wasm_bindgen(js_name = lookUp)]
    pub fn look_up(&self, spelling: &str, source: &str, target: &str) -> String {
        let open = Open {
            source: self.packs.get(source),
            target: self.packs.get(target),
            ipa_only: false,
        };
        lexcore::json::of(&look_up(
            spelling,
            &Lang(source.into()),
            &Lang(target.into()),
            &open,
        ))
    }
}

impl Default for Core {
    fn default() -> Core {
        Core::new()
    }
}
