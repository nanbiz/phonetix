//! What the core hands back about a word, and the shape of a request for it.
//!
//! One value per word, produced once and used by the inline layer, the card, the lens and the
//! audio, so the four cannot disagree about what a word means or how it is said. That is the
//! rule the whole design rests on and the reason none of these carry a second opinion.

/// A language, as the primary subtag of a BCP 47 tag: "de", "es", "en".
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Lang(pub String);

/// How far a word got through the cascade, which decides what the inline layer draws and what
/// the card says. First hit wins; a lower tier fills only what a higher one missed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AnswerState {
    /// The spelling is a lemma in the source pack and its gloss joins the target pack.
    Entry,
    /// The spelling is an inflected form; the lemma it belongs to answered.
    Form,
    /// Several readings and nothing decided between them, so the reader chooses.
    Homograph,
    /// Source and target are the same language: definitions rather than translations.
    Mono,
    /// No dictionary answer, so the engine's, and the interface says as much.
    Guess,
    /// Several words the reader selected, translated together.
    Phrase,
    /// An IPA pack but no lex pack, so a pronunciation and an offer to fetch the rest.
    IpaOnly,
    /// Nothing found and no engine to ask.
    None,
    /// No pack for this language at all.
    NoPack,
    /// The detector could not say what language the run is in.
    UnknownLang,
    /// A pack is opening or a model loading.
    Loading,
}

/// Where an answer came from, which the card shows and never hides.
///
/// A machine translation is a guess and is labelled one, because a wrong word wearing a
/// dictionary's authority is worse than an obvious guess.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Provenance {
    /// A pack, named so the card can say which and what version.
    Dictionary { pack: String },
    /// A translation engine, named.
    Guess { engine: String },
    /// A pronunciation synthesised rather than looked up.
    Synthesised,
}

/// A run of text as a host found it: a text node in a page, a line of an accessibility tree.
///
/// Offsets into it are UTF-16, because both hosts index strings that way and the core is the
/// only thing here that does not.
#[derive(Clone, Debug)]
pub struct TextRun {
    pub id: u32,
    pub text: String,
    pub lang_hint: Option<Lang>,
}

/// One word of a run, and everything needed to draw it without asking again.
#[derive(Clone, Debug)]
pub struct Token {
    pub run_id: u32,
    pub start: u32,
    pub end: u32,
    pub spelling: String,
    pub lang: Lang,
    pub state: AnswerState,
    /// The headline gloss, already cut to what an inline annotation can carry.
    pub gloss: Option<String>,
    /// The transcription after the broad or narrow display transform.
    pub ipa: Option<String>,
    /// Whether the sprinkle chose this word for an inline annotation.
    pub inline: bool,
    pub provenance: Option<Provenance>,
}

/// What the inline layer draws, which is a reader's setting rather than a platform's.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InlineMode {
    Off,
    Gloss,
    GlossIpa,
    Ipa,
    /// The word repainted as its translation, in the page's own colours.
    Replace,
}

/// What the reader asked the inline layer to show, which is a setting rather than a platform's
/// habit: the same choice means the same thing in a browser and on a phone.
#[derive(Clone, Debug, PartialEq)]
pub struct AnnotateOptions {
    pub mode: InlineMode,
    /// One word in every N, from the reader's frequency bar.
    pub density: u32,
    /// Broad transcriptions or narrow ones.
    pub narrow: bool,
    pub accent: Option<String>,
    /// Spellings the reader has opened a card for, which stay annotated afterwards.
    pub seen: Vec<String>,
}

/// What a word the core could not answer needs from the host's engines.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Need {
    Gloss,
    Ipa,
    Both,
}

/// A word the core could not answer, and what would answer it.
#[derive(Clone, Debug)]
pub struct Miss {
    pub token_index: u32,
    pub need: Need,
}

/// What an engine came back with, handed to the core rather than drawn by the host, so that one
/// answer still drives everything.
#[derive(Clone, Debug)]
pub struct EngineResult {
    pub token_index: u32,
    pub gloss: Option<String>,
    pub ipa: Option<String>,
    pub engine: String,
}
