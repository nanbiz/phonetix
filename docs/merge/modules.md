# Module table

One row per module. "Kind" is shared (one implementation used by both platforms), generated
(produced by a build step from an authored source), or platform (inherently tied to one
runtime). Dependencies name modules in this table.

| Module | Owns | Runs on | Kind | Depends on |
|---|---|---|---|---|
| `data/` | Authored tables: IPA symbol inventory with names, Wikipedia titles and diagram ids; language table; accents and regional rules; homograph rules; sprinkle curve parameters; design tokens (`tokens.json`) | build time | authored source | none |
| `lexcore` (Rust crate) | Segmentation (Intl-style word breaking per script), language detection, homograph classification, IPA normalisation and the broad/narrow display transform, the sprinkle curve, the resolution cascade, the Answer and Card types, the `lexpack` reader | browser (WASM), Android (native) | shared | `data/` (embedded at build) |
| `lexpack` (Rust crate) | The binary pack format: header, FST key index, hits, IPA table, zstd entry blocks, label table; reader and writer | wherever `lexcore` runs; CI for writing | shared | none |
| `packbuild` (Rust binary) | Streams a kaikki per-language JSONL extract into a `lexpack`; emits `packs.json` manifest rows with size and sha256 | CI | shared (tooling) | `lexpack` |
| `lexcore-wasm` | wasm-bindgen wrapper exporting `annotate`, `lookup`, `complete`, `open_pack`; the `.wasm` artefact checked into the extension | browser | generated from `lexcore` | `lexcore` |
| `lexcore-android` | uniffi bindings and the `.so` per ABI packaged as an AAR by cargo-ndk | Android | generated from `lexcore` | `lexcore` |
| `gen-types` | Generator emitting TypeScript and Kotlin declarations for the `data/` tables and for `tokens.json` (CSS custom properties for the browser, a Kotlin object for Android) | build time | generated | `data/` |
| `engines/espeak` | espeak-ng: the WASM build in the browser, a native `.so` per ABI on Android from the same NDK pipeline as Bergamot; the language-independent data core bundled, per-language dictionaries fetched as `espeak-<lang>` manifest items; implements the `Phonemizer` port | both | platform (two builds of one library) | `packs` for dictionary download |
| `engines/bergamot` | Bergamot: the WASM build in the browser, a native build on Android, the same per-direction models; implements the `Translator` port with alignments | both | platform (two builds of one library) | `packs` for model download |
| `engines/audio` | Playback: pack recordings where they exist, else espeak synthesis; Web Audio in the browser, AudioTrack on Android, the same voice on both | both | platform (two implementations of one port) | `Phonemizer`, `packs` |
| `packs` | Manifest fetch, pack download with resume and checksum, storage (OPFS in the browser, app files dir on Android), the pack-host override, deletion | both | platform (two implementations of one port) | `lexpack` manifest shape |
| `host` | Owns the core instance and the engines; runs the two-pass batch (`annotate`, run engines for misses, `complete`); keeps the Answer cache keyed by text hash and language | browser (background page on Firefox MV2, offscreen document or service worker on Chrome), Android (the service process) | platform glue, thin | `lexcore-*`, `engines/*`, `packs` |
| `ext/content` | DOM scan into text runs, ruby injection and removal, replace mode by text-node swap, hover and selection gestures, mounting the card | browser content script | platform | `ui/card`, messages to `host` |
| `ext/popup` | The settings view: per-site switch, languages on this page, target language, frequency slider, inline-shows chooser, pack status, Advanced disclosure | browser | platform | `packs`, `settings` |
| `ui/card` (Svelte) | The answer surface: card with eleven states, symbol popover, inline ruby styles; consumes tokens as CSS custom properties | browser | platform renderer of a shared model | `gen-types`, Answer/Card types |
| `android/overlay` | AccessibilityService, tree scan into text runs with the two failure-case detections, per-word rect union, scroll tracking, the overlay windows, three painters (ruby gloss, ruby IPA, replace with colour sampling) | Android | platform | `host`, `android/card` |
| `android/card` (Compose) | The answer surface as a bottom sheet, the lens window, the symbol popover; consumes tokens from the generated Kotlin object | Android | platform renderer of a shared model | `gen-types`, Answer/Card types |
| `android/app` | The settings view with the per-app switch and the service banner, the pack manager | Android | platform | `packs`, `settings` |
| `settings` | The setting keys, defaults and scopes (per site or per app, per language, global) as one authored table; watched storage in the browser, DataStore on Android; the per-site map is keyed by hostname in the browser and by package name on Android | both | generated keys, platform storage | `data/` |
| `fixtures/` | Vector files: text runs with expected tokens, spellings with expected Cards, pages with expected inline density; consumed by `cargo test`, and by the two glue contract suites for the scanners | CI | generated from `lexcore` tests | `lexcore` |

What stays platform-specific and why: finding words on screen (DOM ranges versus accessibility
node character rects), drawing (Svelte in a shadow root versus Compose in overlay windows),
storage, and the engines. Everything that decides what a word means, how it is said, whether it
gets an inline annotation, and how a pack is read, is in `lexcore`, and neither platform can
reach the pack bytes any other way.

# Interface sketches

Types and signatures only. Rust is the source; the TypeScript and Kotlin forms are what the
bindings generate.

## Core types (Rust, `lexcore`)

```rust
pub struct Lang(pub String);                 // BCP 47 primary subtag, e.g. "de"

pub enum AnswerState {
    Entry, Form, Homograph, Mono, Guess, Phrase, IpaOnly, None, NoPack, UnknownLang, Loading,
}

pub enum Provenance { Dictionary { pack: String }, Guess { engine: String }, Espeak }

pub struct Token {
    pub run_id: u32,
    pub start: u32,                          // UTF-16 offsets, because both hosts index text that way
    pub end: u32,
    pub spelling: String,
    pub lang: Lang,
    pub state: AnswerState,
    pub gloss: Option<String>,               // headline gloss, already cut for inline use
    pub ipa: Option<String>,                 // display form after the broad/narrow transform
    pub inline: bool,                        // sprinkle decision for this token
    pub provenance: Option<Provenance>,
}

pub struct TextRun { pub id: u32, pub text: String, pub lang_hint: Option<Lang> }

pub struct AnnotateRequest {
    pub batch_id: u64,
    pub runs: Vec<TextRun>,
    pub target: Lang,
    pub options: AnnotateOptions,
}

pub struct AnnotateOptions {
    pub mode: InlineMode,                    // Off, Gloss, GlossIpa, Ipa, Replace
    pub density: f32,                        // 0.0 to 1.0 on the sprinkle curve
    pub narrow: bool,                        // broad // or narrow [] IPA
    pub accent: Option<String>,
    pub seen: Vec<String>,                   // spellings the reader opened a card for
}

pub struct Miss { pub token_index: u32, pub need: Need }
pub enum Need { Gloss, Ipa, Both }

pub struct AnnotateResponse {
    pub batch_id: u64,
    pub tokens: Vec<Token>,
    pub misses: Vec<Miss>,                   // what the host's engines should try to fill
    pub detected: Vec<(u32, Lang, f32)>,     // run id, language, confidence
}

pub struct EngineResult { pub token_index: u32, pub gloss: Option<String>, pub ipa: Option<String>, pub engine: String }

pub struct Sense { pub gloss: String, pub marks: Vec<String>, pub example: Option<Example> }
pub struct Example { pub text: String, pub translation: Option<String> }
pub struct Reading { pub entry_id: u32, pub pos: String, pub ipa: String, pub headline: String }

pub struct Card {
    pub state: AnswerState,
    pub spelling: String,
    pub lemma: Option<String>,
    pub form_label: Option<String>,
    pub pos: Option<String>,
    pub ipa: Vec<IpaSymbol>,                 // symbol by symbol, for the popover
    pub headline: Option<String>,
    pub provenance: Option<Provenance>,
    pub senses: Vec<Sense>,
    pub readings: Vec<Reading>,              // non-empty only in Homograph
    pub source: Lang,
    pub target: Lang,
    pub pack: Option<PackId>,
    pub missing_pack: Option<PackOffer>,     // NoPack and IpaOnly
}

pub struct IpaSymbol { pub text: String, pub name: String, pub wikipedia: Option<String>, pub diagram: Option<String> }
pub struct PackOffer { pub id: PackId, pub size_bytes: u64 }
pub struct PackId { pub kind: PackKind, pub src: Lang, pub edition: Option<Lang>, pub version: u32 }
pub enum PackKind { Ipa, Lex }
```

## Core surface (Rust, exported through both bindings)

```rust
pub struct Core { /* opened packs, classifier state, answer cache */ }

impl Core {
    pub fn new(target: Lang) -> Core;
    pub fn open_pack(&mut self, id: PackId, source: PackSource) -> Result<(), PackError>;
    pub fn close_pack(&mut self, id: PackId);
    pub fn packs(&self) -> Vec<PackId>;

    pub fn annotate(&mut self, req: AnnotateRequest) -> AnnotateResponse;      // once per batch of new runs
    pub fn complete(&mut self, batch_id: u64, results: Vec<EngineResult>) -> Vec<Token>; // once per batch, after engines
    pub fn lookup(&self, spelling: &str, context: LookupContext) -> Card;      // once per hover, tap or lens pass
    pub fn choose_reading(&self, spelling: &str, entry_id: u32, context: LookupContext) -> Card;
    pub fn phrase(&self, text: &str, context: LookupContext) -> Card;         // state Phrase; gloss filled by complete
    pub fn detect(&self, text: &str) -> (Lang, f32);
}

pub struct LookupContext { pub sentence: String, pub lang: Option<Lang>, pub narrow: bool, pub accent: Option<String> }

pub enum PackSource {
    Mmap(std::path::PathBuf),                // Android
    Bytes(Vec<u8>),                          // WASM, whole pack in memory
}
```

## Ports the host implements (outside the core)

```rust
pub trait Translator { fn translate(&self, src: Lang, dst: Lang, texts: &[String]) -> Vec<Option<String>>; fn ready(&self, src: Lang, dst: Lang) -> bool; }
pub trait Phonemizer { fn ipa(&self, lang: Lang, words: &[String]) -> Vec<Option<String>>; fn audio(&self, lang: Lang, text: &str) -> Option<Vec<i16>>; }
pub trait PackStore { fn manifest(&self) -> Vec<PackId>; fn fetch(&self, id: PackId) -> Progress; fn path(&self, id: PackId) -> Option<PackSource>; fn delete(&self, id: PackId); }
```

## Browser bindings (TypeScript, generated by wasm-bindgen; host side)

```ts
export type Lang = string;
export interface Token { runId: number; start: number; end: number; spelling: string; lang: Lang;
  state: AnswerState; gloss?: string; ipa?: string; inline: boolean; provenance?: Provenance }
export interface Card { /* mirrors the Rust struct */ }

export class Core {
  constructor(target: Lang);
  openPack(id: PackId, bytes: Uint8Array): void;
  annotate(req: AnnotateRequest): AnnotateResponse;
  complete(batchId: bigint, results: EngineResult[]): Token[];
  lookup(spelling: string, ctx: LookupContext): Card;
  chooseReading(spelling: string, entryId: number, ctx: LookupContext): Card;
  phrase(text: string, ctx: LookupContext): Card;
}

// Messages between content script and host; the same shape as the core calls, one per batch.
type HostMessage =
  | { type: "annotate"; req: AnnotateRequest }
  | { type: "lookup"; spelling: string; ctx: LookupContext }
  | { type: "phrase"; text: string; ctx: LookupContext }
  | { type: "chooseReading"; spelling: string; entryId: number; ctx: LookupContext }
  | { type: "audio"; lang: Lang; text: string };
```

## Android bindings (Kotlin, generated by uniffi; service side)

```kotlin
class Core(target: String) {
    fun openPack(id: PackId, path: String)
    fun annotate(req: AnnotateRequest): AnnotateResponse
    fun complete(batchId: ULong, results: List<EngineResult>): List<Token>
    fun lookup(spelling: String, ctx: LookupContext): Card
    fun chooseReading(spelling: String, entryId: UInt, ctx: LookupContext): Card
    fun phrase(text: String, ctx: LookupContext): Card
}

// Overlay side; nothing here crosses into Rust per node or per frame.
class RunCache {                                    // text hash + lang -> tokens
    fun missing(runs: List<ScannedRun>): List<TextRun>
    fun tokensFor(run: ScannedRun): List<Token>?
}
class Placement {                                   // Phonetix geometry, plus the two failure cases
    fun scan(root: AccessibilityNodeInfo): List<ScannedRun>       // text, node id, per-word rects or Unlocatable
    fun follow(scroll: ScrollEvent)
}
interface Painter { fun paint(run: ScannedRun, tokens: List<Token>, style: PageStyle) }
class RubyGlossPainter : Painter
class RubyIpaPainter : Painter
class ReplacePainter : Painter                      // samples background and ink, sizes from line height
```

## Design tokens (`data/tokens.json`, generated to CSS and Kotlin)

```json
{ "palettes": { "paper": { "light": { "surface": "#ffffff", "ink": "#1c1b19", "accent": "#2f6f9f",
                                       "ipaConsonant": "#2f4f70", "ipaVowel": "#8a4b12", "ipaOther": "#767169",
                                       "rubyHue": 210, "...": "..." },
                            "dark": { "...": "..." } },
                "ink": { "light": {}, "dark": {} },
                "classroom": { "light": {}, "dark": {} } },
  "semantic": { "color.surface": "surface", "color.ink": "ink", "color.ipa.consonant": "ipaConsonant", "...": "..." },
  "themeRules": { "ink": { "borderWidth": 2, "weightBold": 700 }, "classroom": { "fontIpa": 19 } },
  "contrast": { "floor": 4.5, "floorInk": 7, "faintFloor": 3, "kindDistance": 60 },
  "size":  { "cardWidth": 360, "popoverWidth": 300,
             "tierFullMinWidth": 320, "tierFullMinHeight": 400,
             "tierCompactMinWidth": 240, "tierCompactMinHeight": 240,
             "fontHeadline": 22, "fontLemma": 17, "fontIpa": 16, "fontBody": 15, "fontSense": 14,
             "fontSmall": 12, "fontLabel": 11, "rubyScale": 0.62, "rubyIpaScale": 0.55,
             "rubyRoom1": 0.95, "rubyRoom2": 1.6, "audio": 28, "lens": 56, "diagram": [96, 72] },
  "motion": { "dwellMs": 150 },
  "space": { "1": 4, "2": 8, "3": 12, "4": 16, "5": 24, "6": 32 },
  "radius": { "card": 14, "chip": 999, "symbol": 6, "button": 8, "bar": 3 } }
```

The build step that guards this file by diff also runs the contrast rules of DR-10 over every
palette and the ruby rule over every palette's surface and page background; a failing palette
fails the build.
