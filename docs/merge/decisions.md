# Decision records

Five records, one per question in the brief. Each states the context, the options with their
cost, the decision, and what follows from it. Facts are from the brief (measured 2026-09-08);
two are from a web check made today and are marked as such.

---

## DR-1: The product model

### Context

The product is translation-first. The reader picks a target language (their own) and reads a
page or an app screen in a source language, which may equal the target. IPA is a feature of the
answer, always present in the card and optionally painted inline. Phonetix's cascade gives one
answer that drives page, tooltip and audio; Taplex's answer is a dictionary entry (lemma, form,
senses), with machine translation as a labelled guess.

### The thing the reader manipulates

A **reading session**: one page or app screen, one source language (detected per text block,
overridable), one target language, one annotation mode. Everything on screen derives from the
session and from a single value object per word run, the **Answer**, which is produced once by
the core and reused by the inline layer, the card, the lens and audio so they never disagree.

Answer states, in cascade order (first hit wins; lower tiers fill what higher tiers missed):

| State | What produced it | Inline | Card |
|---|---|---|---|
| `entry` | Lex pack, spelling is a lemma | headline gloss (and IPA if on) | full entry |
| `form` | Lex pack `forms` table, spelling is an inflection | headline gloss of the lemma | full entry, plus "form of" line |
| `via-en` | Lex pack entry exists but has no translation into the target (a `partial` or `machine` pair, DR-2) | machine guess into the target, marked | guess headline marked, English gloss from the pack beneath it as the anchor, senses in English |
| `homograph` | Lex pack has several entries and the three signals below disagree or none is confident | gloss of the most frequent reading, marked | reading chooser, then the entry |
| `mono` | source == target, monolingual pack | IPA only (no gloss) | definitions instead of translations |
| `guess` | No pack entry; MT engine answered | gloss, marked as guess | one line, "machine guess", IPA from espeak |
| `phrase` | Reader selected several words; MT answered | nothing | phrase translation, guess, no IPA row |
| `ipa-only` | No lex pack for the pair, IPA pack present | IPA if on | IPA, prompt to fetch the lex pack |
| `none` | Entry missing, no MT available (offline, no model) | nothing | "nothing found", IPA if espeak had it |
| `no-pack` | No pack for (source, target) at all | nothing | fetch offer with size |
| `unknown-lang` | Detector confidence below threshold for the block | nothing | language picker |
| `loading` | Pack opening, MT model loading, or scan in flight | nothing | skeleton |

### How translation and IPA compose inline

Inline is a **ruby annotation** above the word, at 0.62 of the body size, in a muted colour, one
short line: the headline gloss, cut at 18 characters. IPA, when switched on, is a second ruby
line under the gloss, smaller again. A page annotated on every word is unreadable, so the
inline layer is **sprinkled**: Phonetix's frequency-based sprinkle curve selects which words get
a ruby, with the density slider from 0 (nothing inline; card on hover or tap only) to 1
(everything). Words in the target language never get a gloss, only IPA if on. Words the reader
has already opened a card for are always annotated ("seen words stay").

Android's in-place replacement mode (from Taplex) is a third inline form, **replace**: the word
is repainted as its gloss in the page's own sampled colours. It is one-line-per-word by
construction, so it carries no IPA; IPA stays in the card. In the browser, replace is the same
mode implemented by swapping text nodes and restoring on toggle.

So the inline modes are: `off`, `gloss`, `gloss+ipa`, `ipa`, `replace`. The card is reached by
hover (desktop), tap (Android and touch browsers), or by dragging the lens (Android).

### What was rejected

- Showing translation and IPA side by side on one ruby line: too wide, breaks line boxes.
- Annotating every word by default: readable only for very short pages; density stays a slider.
- Treating MT output as a first-class translation: it is a guess and the interface says so, in
  its own colour and with the word "guess", on both surfaces.

### Consequences

- The Answer type is the contract between core and both UIs; both surfaces render the same
  twelve states and nothing else.
- Homographs are resolved by three signals chained strongest first, all in the core, and the
  chooser is the fallback when they fail: (1) **the translation's own alignment**: whenever
  the sentence is being translated, for a gloss or for replace mode, the engine has already
  resolved the word in context and DR-7's alignment says which target span the source word
  became; "modern" aligned to "modern" is the adjective, aligned to "rot" is the verb; this is
  free, language-pair wide, and outranks any classifier; (2) **the trained context
  classifier**, which exists for about ten languages; (3) **neighbour part-of-speech
  ranking**: the parts of speech of the adjacent words looked up in the same pack, language
  independent, so it covers the languages the classifier does not. A signal is confident
  when its margin over the runner-up clears a per-signal threshold in `data/`; the first
  confident signal decides, later signals only confirm or contradict it. The chooser appears
  only when the confident signals contradict each other or none is confident. In the
  monolingual case there is no translation to read, so signals 2 and 3 carry it alone, with
  the classifier deciding where it exists and neighbour ranking elsewhere; the chooser rate is
  therefore higher there.
- When a signal decided but was not certain (confident, but a second reading kept a
  non-trivial share), the card is not a chooser: it shows the decided reading as usual and
  adds one quiet "Other reading" row under the grammar line with the other reading's gloss,
  IPA and part of speech, which opens that entry on tap (`STATE-CARD-READING-DECIDED`). This
  is the common case for a real homograph; the chooser is the rare one.
- Expected chooser rate, to be measured as the "chooser rate" metric per language:
  homographs are a small share of tokens to begin with; with a translation running, the
  alignment resolves nearly all of them, so the chooser should appear in well under one in a
  hundred homograph lookups and a negligible share of all card openings; in monolingual
  reading without a classifier, where only neighbour ranking is left, a few percent of
  homograph lookups is the honest expectation. If measurement shows more, the thresholds are
  wrong, not the design.
- Selecting several words is a distinct gesture on both platforms (drag select in the browser,
  long-press then drag on Android) and always yields `phrase`.

---

## DR-2: The pack format and the language-pair problem

### Context

Phonetix ships 54 `{word: ipa}` gzipped JSON files (35 MB), built offline. Taplex builds one
SQLite pack per language pair on the phone from about a gigabyte of kaikki JSONL, over
minutes, in a foreground service; nothing is bundled. A Phonetix dictionary is a strict subset of
a Taplex pack. Browsers cannot open SQLite natively and cannot build a pack from a dump.

The first draft of this record keyed a pack by (source language, Wiktionary edition) and
assumed the editions would fill the matrix. Measured against kaikki's index pages on
2026-09-08 (`docs/merge/coverage.md`), that does not hold: twenty-one editions exist, there is
no Swedish and no Arabic edition, and of the 112 cells that are not an edition's own language
only 36 hold more than thirty thousand senses and 14 more than a hundred thousand, over half
of those in the French and Chinese editions, whose sense counts run two to four times the
English edition's because they generate a page per inflected form. The German, Spanish and
Korean editions are single-language packs in practice: the German edition holds 9,448 Spanish
senses against the English edition's 875,726. Keyed by edition, most pairs would have no
dictionary tier, and machine translation would carry the product, which DR-1 forbids by making
machine output a labelled guess and the dictionary the answer.

The English edition is the rich one for every source language, and a kaikki entry in it
carries a `translations` field: the entry's translations into other languages. A Spanish word
glossed into German can come from the English edition's Spanish entry and its German
translation rather than from the German edition's thin Spanish section. **How dense that
field is per source and target is unmeasured**: kaikki publishes no coverage of translations,
IPA or definitions, so it has to be sampled from the dumps themselves (below).

### Options

| Option | Cost |
|---|---|
| Key by (source, edition), as first drafted | Bounded matrix, then mostly empty; most pairs are machine translation throughout while the interface implies a dictionary |
| Key by source language, one extraction from the English edition per source, with the `translations` field carrying every target | One rich pack per source serves every target; the pack is larger (all translations of every entry); density per target is unmeasured and will vary; senses are written in English unless an edition pack exists |
| Machine-translate the English glosses at build time into every target | Fabricates a dictionary out of guesses and hides the provenance DR-1 insists on |
| Keep SQLite in the browser, or gzipped JSON, or on-phone builds | As in the first draft: two readers, no random access, or a gigabyte on the phone |

### Decision

**A pack is keyed by source language and built from the English edition.** `lex-<src>` holds,
per entry: lemma, part of speech, tags (gender among them), IPA and sounds, senses with English
glosses, marks and examples, forms, and the entry's `translations` grouped by target
language. The target language is a **view over the pack**, not a key: the card's headline for
target T is the entry's translation into T when the entry has one; the English gloss stays
in the card as the anchor either way. `ipa-<src>` is the same format with only the IPA table,
as before, and is unaffected by the measurement.

Edition packs survive only for what they are actually good for: **definitions written in the
reader's own language**. `def-<src>-<edition>` is built from a non-English edition only for
the cells the measurement shows dense (a build threshold of one hundred thousand senses, which
today admits fourteen cells, French and Chinese first), and is optional on top of `lex-<src>`.
Swedish and Arabic readers, and any target without an edition, are served by the translations
view like everyone else.

Because density is unmeasured, every pair is **classed at build time from a sample**, and the
class is what the product tells the reader. The build takes the top twenty thousand lemmas of
each source by frequency rank and counts, per target, the share carrying at least one
translation. Classes, with thresholds to be tuned once the first sample exists:

| Class | Sample result | What the reader gets |
|---|---|---|
| `dictionary` | at least 60 percent of the sampled lemmas have a translation into T | translation-first as DR-1 describes; misses go to the guess tier |
| `partial` | 20 to 60 percent | dictionary where it has one; otherwise the machine guess into T as the headline, marked, with the English gloss beneath it as the anchor (`STATE-CARD-VIA-ENGLISH`) |
| `machine` | under 20 percent, or no lex pack for the source | machine translation throughout, said so in the picker, the pack manager and every card; the IPA pack and the English gloss still show where they exist |

The class is published in `packs.json` per (source, target) beside the pack rows, so the
picker and the pack manager read it rather than infer it, and a reader is never told a pair is
a dictionary when it is a guess. Every pair the engine can translate is offered, because the
reader still has a page to read; what changes is the label and the promise.

Layout (little-endian, sectioned, each section offset and length in the header):

```
header      magic "LXPK", format version, source lang, kind (ipa | lex | def), edition for
            def packs, build date, entry count, section table
keys        an FST (`fst` crate) over every spelling, lemma and inflected form alike,
            NFC + case-folded; value = offset into `hits`
hits        per spelling: list of (entry_id u32, form_label_id u16)
ipa         per spelling: compact IPA string table for the inline layer
entries     u32 offset table, then zstd-compressed blocks of ~64 KB; each block a run of
            CBOR entries {lemma, pos, tags[], ipa[], sounds[], senses[{gloss, marks[],
            examples[]}], translations{lang: [word]}, freq_rank}
labels      string table: form labels, sense marks, language codes
```

Delivery: built offline by a Rust CLI in CI from the kaikki per-language extracts, released as
assets and through the existing pack-host URL; on-phone building is dropped. `packs.json`
lists `{kind, src, edition?, version, size, sha256, url}` rows and a `pairs` table of
`{src, target, class, sampled_share}`. Reader access as in the first draft: memory-mapped on
Android, read into WASM memory in the browser, pure-Rust zstd on both.

### What has to be sampled before the format is frozen

One CI job over the per-language English-edition extracts: for each of the 54 sources, take
the top twenty thousand lemmas by frequency rank, and for each target language count the share
of lemmas with at least one `translations` entry into it, the share with IPA, and the share
with at least one example. That table decides the class thresholds above, the size of a
`lex-<src>` pack with translations included, and whether the `translations` field is dense
enough to be the mechanism at all. If it is not dense for a source, that source's pairs are
`partial` or `machine` and the product says so; the format does not change.

### Consequences

- The builder and the reader are the same crate; a format change is a version bump and a
  rebuilt release, never a phone migration.
- The pair matrix is no longer bounded by editions but by what the sample finds; the
  manifest's `pairs` table is the single source of truth for what a pair is, and the UI never
  offers a pair without its class.
- The card gains `STATE-CARD-VIA-ENGLISH`; the picker and the pack manager show the class;
  the pack manager lists packs per source, not per pair, with `def` packs as optional extras.
- Taplex's foreground build service, its SQLite layer and its unsynchronised handle go away.
- CI needs the pack job, the sample job, and a monthly refresh; sizes and shares are measured
  by those jobs, not estimated here.

## DR-3: Module structure and what is shared

### Context

The extension's library is 2214 lines: about 900 of data tables, 700 of pure logic, 400 of
CSS, 200 of platform glue. Android hand-duplicates about 500 lines of that. Today drift is
held by generated vectors and port tests where they exist and is silent elsewhere. The owner's
concern is that a change on one side must not be possible without the other side being
noticed, in particular when a language model has only one file in front of it. The owner's
prior is a Rust core.

Fixed facts for the boundary: the Android overlay re-reads the accessibility tree many times a
second, and the browser already runs its heavy engine (espeak WASM) outside the content script
(offscreen document on Chrome, background page on Firefox) and messages it.

### Options

| Option | How drift is noticed | Cost |
|---|---|---|
| Duplicate logic, share generated data, port tests | Only where a test covers the pair; silent elsewhere | Every feature twice; two data-layer readers |
| Kotlin Multiplatform, Kotlin/JS for the browser | One implementation | Gradle becomes part of the browser build; core logic in the browser is generated JS; Kotlin/Wasm still Beta; the Android app and the core are the same language, so the line between them is soft and logic leaks into the app module without anything noticing |
| TypeScript core in QuickJS on Android | One implementation | A JS engine as a native dependency on Android; a pack reader running in QuickJS over tens of MB; poor debugging inside QuickJS; only viable with strict batching |
| Rust core, WASM in the browser, uniffi on Android | One implementation | Third toolchain (cargo, wasm-bindgen, cargo-ndk, uniffi); the 700 lines of logic leave TypeScript; WASM is not steppable in Firefox, so the core is debugged as a native binary |

### Decision

**Rust core.** The owner's prior holds, on these grounds, in order of weight:

1. **A language boundary is a boundary an editor cannot drift across by accident.** With KMP,
   logic written in the Android module is still Kotlin and compiles; nothing flags it as on
   the wrong side. With Rust, Kotlin or TypeScript that starts doing segmentation or lookup
   is visibly out of place, and neither platform has access to the pack bytes except through
   the core, so it cannot even try. This is the answer to the owner's actual concern: the
   single implementation is not merely one file, it is the only place the work can be done.
2. **The data layer is the largest shared surface, and Rust owns it end to end.** The same
   crate builds the pack in CI, memory-maps it on Android, and reads it from a byte slice in
   WASM. The `fst` crate reads a transducer in place from either. KMP would need a JVM builder
   plus a Kotlin/JS byte reader; duplication would need three readers.
3. **The browser build stays pnpm plus Vite.** A `cargo build --target wasm32-unknown-unknown`
   step and a checked-in `.wasm` artefact are all the extension needs; no Gradle in the
   browser pipeline, and the target is stable where Kotlin/Wasm is Beta.
4. **Testing collapses to one place.** The port-test machinery becomes `cargo test` over the
   vector files; the platforms keep only thin contract tests of their glue (word-rect
   discovery, message plumbing, rendering), which is what is genuinely platform-specific.

What Rust costs and how it is paid: the third toolchain is declared in Nix like the others;
the logic is rewritten once (700 lines, plus the pack reader); WASM debugging is avoided by
testing the core natively and keeping the TypeScript and Kotlin glue thin enough to debug on
its own.

### The foreign-function boundary

The core exposes three calls, the same on both platforms:

- `annotate(batch)`: once per scan of **new** text. The host sends text runs it has not seen
  (keyed by text hash plus language hint) and gets back tokens with their Answer state,
  gloss and IPA. Geometry never crosses. On Android, the tree re-read loop and the scroll
  tracking touch only the Kotlin cache; the core is called when the set of run texts changes,
  which is a few times per screen, not per frame. In the browser the identical call is a
  message from the content script to the background or offscreen host.
- `lookup(spelling, context)`: once per hover, tap or lens pass; returns the full Card.
- `complete(batch_id, engine_results)`: once per batch, after the host has run the engines
  (MT, espeak) for the misses the core reported, so the core stamps the guesses into the same
  Answer objects and the one-answer rule survives the engines living outside the core.

Payloads are records (uniffi) or a serialised struct (wasm-bindgen); a batch of a few hundred
runs is one call and one serialisation each way. There is no per-word and no per-node call.

Engines stay outside the core behind one port interface: **espeak-ng on both surfaces** (WASM
in the browser, a native library on Android; see the paragraph after next), and
**Bergamot on both surfaces**: the WASM build in the browser and a native arm64 build of
`bergamot-translator` on Android, loading the same model files. This amends the first draft
of this record, which had ML Kit on Android. Two facts changed it (DR-7): ML Kit returns an
opaque string with no tokens and no alignments, so replace mode cannot exist on it, and two
engines give one word two different guesses on the two surfaces. What the native build costs:
an NDK C++ build of Marian, SentencePiece and the ARM integer path in CI, built once per
release and cached; a native library per ABI whose size and phone-side speed are measured by
the build, not estimated here; and the risk that the arm64 build does not come together, in
which case CTranslate2 (documented Android support, `return_attention` for alignments) is the
fallback at the price of converted models and the larger per-pair download. ML Kit is off the
plan. The `Translator` port carries alignments as part of its result on both surfaces.

espeak-ng on Android. The first draft left it out, with `guess` and `none` on Android falling
back to the pack's nearest lemma or nothing, and word audio to the system text-to-speech
voice. Once the Marian build exists the project owns the NDK toolchain, per-ABI packaging and
CI caching that espeak-ng needs, and espeak-ng is a small plain C library that is built for
Android routinely elsewhere, so the marginal cost is one more CMake target. What it costs and
how it is shipped: the library is a native `.so` per ABI, small next to Marian; the data the
browser ships as 23 MB is not one blob but a language-independent core (phoneme tables and
phondata) plus one dictionary file per language, and the per-language files are the bulk.
The APK bundles the core and downloads `espeak-<lang>` as a manifest item beside the IPA and
lex packs of that language, so the APK grows by the library and the core and each language
by its own dictionary, all measured by the build. The data cannot be derived from the packs:
espeak's dictionaries are rule tables, not Wiktionary lookups, which is exactly why it is the
last tier; the IPA pack answers first and espeak only fills what the pack lacks. Audio is
espeak's own synthesis on both surfaces, through Web Audio in the browser and AudioTrack on
Android, so the same voice says the word everywhere; human recordings from the pack still win
where they exist (DR-8). `guess` and `none` in DR-1 therefore hold on both surfaces as
written.

### Consequences

- Modules and their ownership are in `modules.md`. Data tables become authored JSON under
  `data/`, embedded into the core at build time and exported to TypeScript and Kotlin types
  by a generator, so the UI can name IPA symbols without owning the table.
- Design tokens are one JSON file generating the CSS custom properties and a Kotlin object.
- Safari on iOS needs `unsafe-eval` for WASM; it already would for espeak, so the core adds no
  new cost there.
- Android carries espeak-ng natively; no state is degraded on Android relative to the browser.
- The content script never links the core; it is a DOM scanner and a renderer.

---

## DR-4: Which Android overlay survives

### Context

Phonetix: 4900 lines of placement engine, measured, with a suite that photographs the screen;
one language, one display form, no card beyond IPA. Taplex: three windows (entry card, a
draggable lens that explains what it passes without taking touches, and an in-place
replacement painted in sampled page colours at a type size derived from measured line
height); names two failure cases Phonetix does not (no character locations; Chrome returning
the paragraph box for every character); one 1905-line class, an unsynchronised SQLite handle,
main-thread writes in the hot loop, and 19 rendered UI states whose comparison task never runs.

### Options

| Option | Cost |
|---|---|
| Phonetix engine as the base, Taplex features ported onto it | Lens, replace mode and colour sampling have to be re-implemented on Phonetix's geometry layer |
| Taplex as the base, Phonetix tests ported to it | Starts from the god class and the known concurrency bugs; the measured engine is thrown away |
| Merge both files | Two placement loops in one service; the worst of each |

### Decision

**One geometry layer, from Phonetix, with two painters and two answer windows; Taplex
contributes features and the two failure cases, its code is not carried.**

Carried from Phonetix, as the base: the word-rect discovery and per-word union, the
scroll-following placement, the accessibility-overlay window handling, and the screenshot
suite, which becomes the suite for every overlay mode.

Carried from Taplex, as behaviour to re-implement on that base: the two failure-case
detections (a node with no character locations is skipped and reported; a node whose
character rects all equal the node box is treated as unlocatable rather than painted); the
in-place replacement painter with background and ink sampling and the line-height-derived
type size; the lens (the touch equivalent of hover, and the only way to browse a screen
without tapping into the app underneath); the entry card as a bottom sheet.

Thrown away: the 1905-line class, the SQLite layer (replaced by the core reader), the
foreground build service, main-thread file writes, and the 19 never-compared states (replaced
by states the screenshot suite actually asserts on).

### Consequences

- The overlay has three inline painters (ruby gloss, ruby IPA, replace) over one placement
  loop and one cache, plus two windows (card sheet, lens).
- The screenshot suite grows by one case per painter and one per failure case, and it runs
  in CI against the emulator, the same way the Phonetix suite does today.
- The share of text runs carrying an annotation is the measured quality metric, per painter.

---

## DR-5: The answer surface

### Context

One surface on two form factors: a hover tooltip on desktop, a tap card (bottom sheet) on a
phone, in the browser and in the Android app. It carries the translation, the IPA, the part
of speech, the tapped form, senses with marks and examples, and provenance. The IPA is
symbol by symbol, each clickable for the existing symbol popover.

### Options

| Option | Cost |
|---|---|
| Two layouts, one per form factor | Two designs to keep in step; state coverage doubles |
| One layout, scaled | Desktop wants a narrow anchored box, phone wants full width; a scaled box is wrong on one of them |
| One component with a container-responsive layout and one set of tokens | Tokens and layout rules must be expressed once, in CSS custom properties, and mirrored into Kotlin |

### Decision

**One component, container-responsive, one token set.** Below the phone breakpoint it is a
full-width bottom sheet with a grab handle and a 60 percent height cap; above it, an anchored
box of fixed width (`--card-width`, 360 px) with a 3-sense cap and a "more" affordance.

The content and hierarchy of the card are decided in DR-8, which supersedes the first
draft of this record's reading order; this record keeps the form-factor decision and the
token rule.

Empty and error states use the same frame with the headline slot carrying the message and the
pronunciation line kept whenever any tier produced one. The `no-pack` and `unknown-lang`
states replace the body with one sentence and one action.

Every colour, size, radius and spacing is a custom property declared once in `surface.html`;
the Android side gets the same names from `tokens.json`.

### Consequences

- The view model has one card view with eleven states, one inline view with five modes, the
  symbol popover, the lens, and the pack manager; `surface.html` renders all of them in light
  and dark.
- The Svelte component and the Compose sheet are two renderers of the same Answer and the
  same tokens; the screenshot suites on both sides compare against the same state list.

---

## DR-6: The settings surface

### Context

The shipping extension has twelve settings, checked against its storage keys: per-site on
and off (`websites_enabled`, a hostname map, with `extension_enabled` as the default), one
continuous IPA frequency slider that maps onto display mode and sprinkle density through one
curve pinned by vector files, `hideStress` and `narrow` shown together as Full IPA, an accent
map per language, `hoverDelay` with an explicit Instant, `animations`, and `packBaseUrl` under
Advanced. It detects the language of every block and transcribes each in its own language.
The merged product adds a target language, lex packs, and a translation engine, and it has
to show the same surface in a browser popup and on an Android screen. The Android app has
five settings today.

### Options

| Option | Cost |
|---|---|
| One flat list, everything on one screen | Twenty rows; the per-site switch, the only thing a reader touches daily, is lost among engine options |
| Two screens, first screen and Advanced disclosure, grouped by scope | The grouping has to be decided and each setting has to carry its scope |
| Split the inline control into a mode chooser plus a density slider | Two controls for one idea; the curve stops being the single source and the parity vectors lose their subject |
| Keep one frequency slider and add a separate "what inline shows" chooser | A second control, for a new dimension the extension did not have |

### Decision

**Two screens, grouped by scope; one frequency slider on the one curve; "inline shows" as a
separate chooser for a separate dimension.**

Scopes, and where each setting lives:

| Setting | Scope | First screen or Advanced | Notes |
|---|---|---|---|
| On for this site | per site; per app on Android (package name in place of hostname, same map shape) | first | default comes from "new sites start on" |
| Languages on this page | read-only, per run | first | chips of the languages the core detected on this page |
| Your language (target) | global | first | |
| Inline annotations (frequency) | global | first | one slider, the curve in the core; retired and dimmed while Inline shows is Replace (DR-7) |
| Inline shows: gloss, gloss and IPA, IPA, replace | global | first | the settled inline IPA switch is the IPA choices here |
| Packs | global | first, status only | opens the pack manager |
| Stress marks | global | Advanced, Full IPA | `hideStress` inverted: on means ˈ and ˌ are shown; default off, as shipped |
| Narrow detail | global | Advanced, Full IPA | `narrow`: on means [ ] with diacritics; default on, as shipped; the two combine freely |
| Accent | per language | Advanced | one row per language the reader has a pack for |
| Card delay, with Instant | global, browser only | Advanced, Browser | Android taps and the lens dwells; no delay applies |
| Animations | global, browser only | Advanced, Browser | Android motion follows the system setting |
| Translation engine | global | Advanced, Engine and data | Bergamot in the browser, ML Kit on Android, or off |
| New sites start on | global | first, This site group, beside the site switch | `extension_enabled`; the default a site without its own switch inherits |
| Dictionary host | global | Advanced, Engine and data | `packBaseUrl`; serves `packs.json` and the packs it lists |
| Theme (Paper, Ink, Classroom) | global | Advanced, Appearance | DR-10; the mode (system, light, dark) is a second row beside it |
| Accessibility service | Android only | banner above everything while off | |

Multilingual pages: the source language stays a property of a run of text. The core is
called with a `lang_hint` per run and answers with `detected` per run, the inline layer
annotates each block in its own language, and the card foot names the pair for that block.
The settings screen shows the languages found on the page as chips and does not offer a
page-wide override, because a page-wide override would break the English title on the German
page. A wrong detection is corrected from that block's card, through the same picker as the
unknown-language state, and the correction is remembered per site and block text.

One control or two: the frequency slider stays one control. The curve that turns its value
into which words get an inline annotation moves into the core with its parameters in `data/`,
and the vector files that pinned it become `cargo test` fixtures there; neither platform holds
a copy of the curve any more, so parity is by construction and the vectors test the only
implementation. "Inline shows" is a second control because it is a second dimension: gloss
versus IPA versus replace is what an annotated word displays, and the extension had no such
choice because it had only IPA. It never changes how many words are chosen. Zero on the
slider is "card only"; there is no separate Off.

Browser and Android differences: the per-site row reads "this app" on Android and is keyed by
package name; the Browser group (card delay, animations) is absent on Android; the service
banner exists only on Android; everything else is the same rows from the same key table.

### Consequences

- `settings/keys.json` grows from the extension's twelve keys to sixteen and each key carries
  its scope; the generated Kotlin and TypeScript key objects expose the scope so a wrong write
  (a global written per site) fails to compile.
- The Android app goes from five settings to the full table minus the Browser group.
- The settings view has four states, all rendered; the pack manager keeps its own view.

---

## DR-7: Replace mode works on aligned phrases, not words

### Context

The first draft of replace mode swapped single words: "Die library öffnet ... wenn der reading
hall schon voll ist." The article, adjective endings and word order that agree with a German
noun stayed German while the noun became English. Replacement was operating on words while
grammar operates on phrases.

Facts (researched today): Bergamot's `Response.alignments` exposes a per-sentence soft
alignment matrix from its WASM bindings at subword level, at no extra download. ML Kit
exposes an opaque string. Runtime aligners need a corpus or an mBERT-sized model. A
morphology pipeline for one language is about 37 MB and has never shipped in WASM. kaikki
carries gender in an entry's tags, so article repair is cheap but stops at adjective endings
and anaphora. No prior art solves this; popup tools never edit the sentence.

### Options

| Option | Cost |
|---|---|
| Word swap plus article repair from gender tags | Fixes "der", fails "voller Lesesaal", relative pronouns and later pronouns; German only |
| Per-language morphology models | 37 MB per language, no WASM precedent, and a grammar per language to maintain |
| Whole-sentence replacement | Grammatical by construction, but no per-word mapping: the reader loses which word became what |
| Aligned phrase units with a safety test and a graceful downgrade | Needs an engine with alignments; needs a span cap tuned by measurement |

### Decision

**Replace by minimal monotone consistent phrase pairs from the sentence alignment, capped;
downgrade anything else to a ruby gloss.** Language independent, and grammatical wherever it
replaces, because a consistent pair carries its own articles, endings and order.

Mechanism, in the core: the host translates each sentence and returns the target text with
the alignment matrix; the core merges subwords to words and takes a hard alignment above a
threshold; it partitions the sentence into the minimal phrase pairs that are **consistent**
(the source tokens map to one contiguous target run and no token outside the span maps into
that run) and **monotone** (successive units' target runs are in order), which always exists
because the whole sentence is trivially such a pair. Each unit up to the span cap (four source
tokens to start, a parameter in `data/`, tuned by measurement) is replaced in place by its
target text. A unit over the cap means the words inside it have satellites too far away to
swap safely; those words keep their source form and get a ruby gloss instead. "Das hat mir
den Tag gerettet" becomes "That" plus four glossed German words, because "hat ... gerettet"
and "saved" form one five-token unit.

Replaced spans carry a faint underline and open the phrase card with the original text, so the
mapping the reader lost by not seeing the source is one tap away. Anaphora across sentences is
bounded by the engine's sentence window and is accepted as a labelled machine guess; the mode
never claims dictionary provenance.

Density: the frequency slider does not apply to Replace. Sprinkling would replace some units
and not their neighbours, which is the original error in a new coat. Replace swaps every unit
under the cap. The settings screen dims the slider with that reason while Inline shows is
Replace (`STATE-SETTINGS-REPLACE`), and offers Replace only when the engine on the device
exposes alignments.

Android: ML Kit cannot do this, and Replace came from the Android side. That is one of the two
reasons DR-3 now puts Bergamot native on Android instead of ML Kit; the other is that one word
should get one guess on both surfaces.

### Consequences

- The `Translator` port returns alignments; the phrase-unit partition lives in `lexcore`
  with vector fixtures, so both platforms cut sentences identically.
- Replace mode is the one inline mode that needs the whole sentence translated, so its batch
  is a batch of sentences, still one call per batch.
- The span cap is measured: the share of tokens replaced versus downgraded per language is
  the quality metric for this mode.

---

## DR-8: What the card is for, and what is on its face

### Context

The first draft of the card led with the tapped spelling as its largest line. The reader is
looking at that word; the card repeated it and pushed what they wanted down. The card was also
thinner than the tooltip it replaces, which already offers symbol-by-symbol IPA with a
description per symbol, a Wikipedia article per sound, a Wikimedia recording per symbol, a
sagittal diagram, Seeing Speech films, a Wiktionary link for the word, a human recording of
the word where one exists and synthesis otherwise. The merged card has to meet that and add
translation, senses, marks, examples, form-of and provenance.

### What a reader wants when they stop at a word, in order

1. what it means here
2. how it is said
3. what form it is, and which word it belongs to
4. which other senses exist, and which applies
5. how to go deeper

### Decision

The face of the card, top to bottom, on both form factors:

| Element | What it is for | Why it earns the top |
|---|---|---|
| **Headline: the applying sense's gloss**, with the provenance badge | The answer | It is the question the reader asked; the homograph classifier or the first sense picks it |
| **Pronunciation line**: IPA symbol by symbol, each a button, then a play control labelled "recording" or "synthesised" | How it is said, and honest about the source of the audio | Second question; symbols stay individually reachable as before |
| **Grammar line**: the lemma with its article, the part of speech, and for a form the form label | Which word this is and what to memorise | "der Lesesaal" is what a learner commits to memory; the article follows from the gender tag; for a form ("ging") the lemma "gehen" gets the prominence and the tapped form is the small part |
| **Example** of the applying sense, with its translation | Sense in context | One line, only when the pack has one |
| **Other senses**, up to two, with marks, then "n more senses" | Which sense applies, and that others exist | A word with one sense shows none of this; a word with twelve shows two and the count |
| **Foot**: the pair and pack, Wiktionary, all senses | Going deeper and provenance | Always the same place |

What is cut, and why: the spelling as a headline, because the page already shows it under the
pointer or the finger and, on the bottom sheet, the overlay keeps the tapped word highlighted
so the anchor is visual; the lemma still appears in the grammar line in small type, with its
article, so the word is never absent. The numbered full sense list on the face, because it
made a twelve-sense verb into a scroll; it lives behind "n more senses". The "Selection, n
words" meta line, because the selection highlight already says it.

Homograph chooser: each reading is one row of translation, then its IPA on the same line,
with the part of speech at the row end, because the reader chooses by meaning first and the
IPA is what differs between readings.

Symbol popover, behind a symbol: the symbol, its name, a one-sentence description, the
sagittal diagram slot (fetched from Wikimedia Commons, DR-5), the Wikimedia recording, the
Wikipedia article, and Seeing Speech films. Nothing from the old tooltip is dropped; it moves
one tap deeper because it is about a sound, not the word.

Lens card: two lines, the headline gloss with its badge and the pronunciation line, and for a
form the lemma after the gloss. It follows a finger and cannot hold more; the tap card holds
the rest.

Long transcriptions: the pronunciation line is its own line, so a long IPA wraps within it and
every symbol stays reachable; nothing truncates. The guess state shows it.

Type: headline 22 px, pronunciation 16 px in the IPA colour, grammar line 13 px, so the
answer is the most prominent thing on the card and the transcription reads as its support.

### The provenance badge and the Wiktionary mark

The dictionary badge carries the Wiktionary mark, not a drawing of it. The mark is a Wikimedia
Foundation trademark used here nominatively, to say where the data came from; the file used,
its Commons page and its licence are pinned in `data/marks.json` and shipped as an asset, so
the question is settled there. Which mark: not the classic multilingual tile block, which
turns to mud below about twenty pixels, but Wikimedia's own small-size Wiktionary mark, the
one it ships for favicons, at `--mark-size` (16 px). The badge is mark plus the word
"Wiktionary" at the Full and Compact tiers and the word alone at Strip, so the name is always
present. Themes and modes: the mark is never recoloured; it sits on a fixed white plate
(`--color-mark-plate`, the one colour token that is not themed) inside the badge, so on a
dark ground it reads as a small white tile and the DR-10 contrast rules apply to the badge's
text on the badge's background as before. Meaning: every provenance value uses the same badge
shape and type, so the set reads as one row of labels; only the Wiktionary badge has a mark
because only it has one, and "guess" and "synthesised" stay text in the guess colour. Name and
operation: the badge is a link with the accessible name "Source: Wiktionary. Opens the entry
on Wiktionary." and keyboard focus; the foot keeps its Wiktionary action as the primary way
to the entry.

### Consequences

- `Card` gains `article`, `headline_sense`, `audio: Recording | Synth | None`, and the
  symbol table gains a description per symbol.
- Human recordings come from the pack's `sounds` list resolved through the host like the
  diagrams; synthesis is the fallback and is labelled.
- Every card state renders with the same frame; the eleven states are unchanged in number.

---

## DR-9: Density tiers, chosen by space, not by platform

### Context

The first draft had a desktop tooltip, a phone sheet and a three-line lens card: three layouts
for one answer, keyed to where they ran. A browser window dragged narrow drops into a small
layout without becoming "the phone design"; the card should do the same. A card anchored under
a word near the bottom of a screen has little height whatever the width, so height matters
as much as width.

### Decision

**One card, three named tiers, chosen by measuring the space the card was given.** The
measurement is the same question on both sides: how much room does this card have. In the
browser it is a container query on the card's container (`container-type: size`), not a
viewport media query; on Android it is the constraint-measuring composable that receives the
same maximum width and height. Neither is a platform breakpoint. The thresholds are tokens
(`--tier-*`), and because `@container` cannot read custom properties the same numbers are
repeated literally in the query, guarded by the token file's build diff.

| Tier | Space | Present | Collapses | Dropped |
|---|---|---|---|---|
| **Full** | width ≥ 320 px and height ≥ 400 px | headline with badge, pronunciation line with play and its label, grammar line, example, two other senses with the "n more" count, foot with both actions | nothing | nothing |
| **Compact** | width ≥ 240 px and height ≥ 240 px, but not Full | headline, pronunciation line with play and label, grammar line, foot with the pair and one action | example and other senses fold behind one line naming their count ("12 senses, 1 example") | the second foot action |
| **Strip** | anything smaller | headline with badge, pronunciation line with play | nothing; a tap on the strip asks the host for more room and re-measures | grammar line, play label, body, foot |

A rule a person can apply by hand: measure the available width and height; if both Full
minimums are met it is Full; else if both Compact minimums are met it is Compact; else Strip.
Where the card goes is a second, prior decision from the same measurement: it anchors beside
its word when the space on some side of the word holds at least a Compact card, and otherwise
docks full width to the nearest screen edge, which is what a portrait phone almost always
does. The thresholds come from the card's own heights (a Full card with one example and two
other senses is about 380 px; a Compact card about 235 px), so a card at a tier always fits
the space that put it there.

### The motion axis is separate

Space decides the tier. Motion decides what is filled in yet. While a finger drags the lens
the card is refilled from the batch answer (headline, pronunciation, grammar line, which the
`annotate` call already returned) and the expensive rows (example, other senses, recording
lookup) are deferred; a pause of `--motion-dwell` (150 ms) fills the current tier; lifting
commits to the settled word. Under a drag the card pins full width to the screen edge away
from the finger's half and flips edges only when the finger crosses the midline, so it never
sits under the hand and never chases the finger. The lens therefore owns its idle position
and its aiming and nothing else; the answer is the card at whatever tier that half-screen
allows.

### Consequences

- `STATE-CARD-TIER-FULL`, `-COMPACT` and `-STRIP` in the model and on the page show the same
  entry at each tier; every other card state names the tier it is drawn at.
- The tier thresholds, the dwell and the sizes live in `tokens.json` beside the spacings.
- The screenshot suites photograph each tier once; a tier that stops fitting its space fails.

---

## DR-10: Themes as palettes under semantic tokens

### Context

Light and dark are modes, not themes. Every theme provides both, and a reader follows the
system or pins one. Three things make this more than recolouring: replace mode paints in the
page's own sampled colours; ruby annotations sit on backgrounds no theme controls; and the IPA
symbols are coloured by kind, which carries meaning.

### Mechanism

Two layers. A **palette** per theme and mode (`--p-*` in CSS, one object per theme and mode
in the generated Kotlin) names actual colours. The **semantic tokens** the components use
(`--color-surface`, `--color-ink`, `--color-accent`, `--color-guess`, `--color-ipa-consonant`,
...) are aliased onto the palette once, in one block. Components never name a colour, only a
role, so a new theme is a new palette block and nothing else changes; the token file keeps
serving both renderers. A theme may also override a rule, not only a colour: Ink sets a 2 px
border and heavier weights, Classroom sets a larger pronunciation line.

### The three boundaries

1. **Replace mode is not themeable.** The replace painter samples the background and ink of
   the run it repaints and uses those; the theme never reaches that layer. The line: the theme
   governs the card, the popover, the settings, the pack manager, the lens ring and the
   scanning pill; the page governs anything painted into the page, which is replaced text and
   the tapped-word highlight (accent at low alpha over the page's own colours).
2. **Ruby colour is derived, not chosen.** The theme supplies a hue (`--ruby-hue`) and a
   chroma cap; at paint time the renderer samples the background behind the run and picks the
   lightness on that hue that meets the floor of **4.5:1** (ruby is small text). If no
   lightness on that hue reaches 4.5:1, which happens on mid-tone backgrounds, the ruby is
   painted on a pill of the theme's surface colour, where 4.5:1 is guaranteed by the palette
   rules below. The showcase stands in for a white page with the consonant colour.
3. **Symbol kinds are a constrained set.** Consonants, vowels and the rest (stress, length,
   boundaries) are three semantic colours per palette. They must each pass the contrast floor
   on the surface and stay mutually apart after simulating protanopia and deuteranopia, so
   the distinction never rests on red against green; in every palette the pair is blue
   against amber with a neutral third, separated in lightness as well as hue.

### Rules a build step checks (a theme that fails does not ship)

Given a palette block:

- `ink`, `inkMuted`, `accent`, `guess`, `ipaConsonant`, `ipaVowel`, `ipaOther` each ≥ 4.5:1
  against `surface` (WCAG contrast ratio); for the Ink theme ≥ 7:1.
- `inkFaint` ≥ 3:1 against `surface` (labels only, never body text).
- Each pair `accent/accentBg`, `guess/guessBg`, `chipInk/chipBg`, `danger/dangerBg`,
  `accentInk/accent` ≥ 4.5:1.
- For each of protanopia and deuteranopia (Brettel-style matrices), the simulated RGB
  distance between every two of `ipaConsonant`, `ipaVowel`, `ipaOther` ≥ 60 on a 0–255 scale.
- The ruby rule is checked at build against the surface and page-bg colours of every palette,
  and at runtime against the sampled background.

The six palettes on the page pass these rules today (checked with the same formulas while
writing them; Paper's consonant and neutral had to be moved apart in lightness to pass the
simulation rule). The token file is already guarded by a build diff; the checker runs in the
same step and its report is part of the diff.

### The set

A theme is worth having when it changes a rule or a purpose, not a hue. Three:

| Theme | For | What it changes |
|---|---|---|
| **Paper** (default) | long reading; the page stays dominant | lowest chroma, warm neutrals, restrained blue accent, subtle kind colours |
| **Ink** | low vision, bright sunlight on a phone, projectors in bad rooms | contrast floor 7:1, pure black and white, 2 px borders, heavier weights, saturated kind colours |
| **Classroom** | learning and teaching sounds | strong kind colours, 19 px pronunciation line, warm paper surface; the pronunciation line is the point |

Each in light and dark; six palettes; `surface.html` shows the card in all six. Anything
that would only tint Paper is not a theme.

### The setting

Theme and Mode are two rows under Advanced, Appearance, both global (DR-6). Mode defaults to
System.

### Consequences

- `tokens.json` gains `palettes[theme][mode]` and a `semantic` map; the Kotlin generator
  emits one object per palette and a role map.
- `Card.ipa` symbols carry a `kind` so both renderers colour them without a second table.
- The replace painter and the ruby painter take a hue and a sampler, never a colour.
