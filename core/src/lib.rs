//! The reading core.
//!
//! One implementation of what a word means, how it is said and which words are worth
//! annotating, compiled twice: to WebAssembly for the browser extension and to a native
//! library for the Android overlay. Finding words on a screen and drawing on them stays with
//! each platform, because a DOM and an accessibility tree have nothing in common; deciding
//! what to draw is here, where neither platform can reach the pack bytes any other way.

pub mod accent;
pub mod annotate;
pub mod answer;
pub mod detect;
pub mod gloss;
pub mod json;
pub mod languages;
pub mod neighbours;
pub mod resolve;
pub mod segment;
pub mod sprinkle;
pub mod symbols;
pub mod wiktionary;
