//! The browser's way into the core.
//!
//! A thin wrapper and nothing else: no logic lives here, because anything that decides
//! something would then exist only for one platform, which is the drift this arrangement is
//! meant to make impossible. What crosses the boundary is a pack's bytes, once, and a word at
//! a time after that.

use std::collections::HashMap;

use lexcore::annotate::{annotate, complete};
use lexcore::answer::{AnnotateOptions, EngineResult, InlineMode, Lang, TextRun, Token};
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
    /// The batches the host is still drawing, kept so that what its engines answer joins the
    /// same tokens rather than a second set the host stitched together itself.
    batches: HashMap<u64, Vec<Token>>,
    next_batch: u64,
}

#[wasm_bindgen]
impl Core {
    #[wasm_bindgen(constructor)]
    pub fn new() -> Core {
        Core {
            packs: HashMap::new(),
            batches: HashMap::new(),
            next_batch: 1,
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

    /// Annotate a batch of runs: one token per word, and the misses the host's engines should
    /// try to fill.
    ///
    /// The runs arrive as three parallel arrays rather than as JSON, because the host has them
    /// as arrays already and serialising a page's text to parse it straight back is a copy of
    /// every word for nothing. An empty language hint means the batch's own source language.
    #[wasm_bindgen]
    pub fn annotate(
        &mut self,
        run_ids: Vec<u32>,
        texts: Vec<String>,
        hints: Vec<String>,
        source: &str,
        target: &str,
        mode: &str,
        density: u32,
        seen: Vec<String>,
    ) -> String {
        let runs: Vec<TextRun> = run_ids
            .iter()
            .zip(texts.iter())
            .enumerate()
            .map(|(at, (id, text))| TextRun {
                id: *id,
                text: text.clone(),
                lang_hint: hints
                    .get(at)
                    .filter(|hint| !hint.is_empty())
                    .map(|hint| Lang(hint.clone())),
            })
            .collect();
        let open = Open {
            source: self.packs.get(source),
            target: self.packs.get(target),
            ipa_only: false,
        };
        let options = AnnotateOptions {
            mode: match mode {
                "gloss" => InlineMode::Gloss,
                "gloss+ipa" => InlineMode::GlossIpa,
                "ipa" => InlineMode::Ipa,
                "replace" => InlineMode::Replace,
                _ => InlineMode::Off,
            },
            density,
            narrow: false,
            accent: None,
            seen,
        };
        let (tokens, misses) = annotate(
            &runs,
            &Lang(source.into()),
            &Lang(target.into()),
            &open,
            &options,
        );
        let id = self.next_batch;
        self.next_batch += 1;
        let written = lexcore::json::batch(id, &tokens, &misses);
        self.batches.insert(id, tokens);
        written
    }

    /// Fill in what the host's engines answered, and hand back the batch it belongs to.
    ///
    /// A batch the core no longer holds comes back empty rather than throwing: the reader has
    /// moved on, and a page that scrolled away is not an error.
    #[wasm_bindgen]
    pub fn complete(
        &mut self,
        batch: u64,
        indices: Vec<u32>,
        glosses: Vec<String>,
        ipas: Vec<String>,
        engine: &str,
    ) -> String {
        let Some(mut tokens) = self.batches.remove(&batch) else {
            return lexcore::json::batch(batch, &[], &[]);
        };
        let results: Vec<EngineResult> = indices
            .iter()
            .enumerate()
            .map(|(at, index)| EngineResult {
                token_index: *index,
                gloss: glosses.get(at).filter(|text| !text.is_empty()).cloned(),
                ipa: ipas.get(at).filter(|text| !text.is_empty()).cloned(),
                engine: engine.to_string(),
            })
            .collect();
        complete(&mut tokens, &results);
        let written = lexcore::json::batch(batch, &tokens, &[]);
        self.batches.insert(batch, tokens);
        written
    }

    /// Give up a batch the host has finished drawing.
    #[wasm_bindgen(js_name = dropBatch)]
    pub fn drop_batch(&mut self, batch: u64) {
        self.batches.remove(&batch);
    }
}

impl Default for Core {
    fn default() -> Core {
        Core::new()
    }
}
