# Brief: architecture and design for a merged reading-assistance product

You are being asked to make the decisions that set the shape of a new product built from two
existing ones. Everything below is measured from the two codebases or from research done today
(2026-09-08), not assumed. Where a fact is uncertain it says so. Do not ask for the source; it
will not be provided, and the facts here are what the decisions rest on.

**Treat this as greenfield.** Much of it will be rewritten from scratch rather than carried
over, specifically so that the shape of the old code does not become an artefact in the new
one. The two codebases below are evidence about the problem: what the data looks like, what
turned out to be hard, what a working solution had to handle. They are not a constraint on the
answer, and "this already exists" is not a reason to choose anything. Judge every option on
its merits alone.

## What the owner has decided already

These are settled and are not yours to revisit.

- The merged product is **translation-first**. IPA becomes one feature of it rather than the
  point of it.
- The reader picks a language. It may be the language of the page they are on.
- The in-page IPA transliteration can be switched on or off.
- The tooltip or card **always** shows the IPA, as part of the answer, whatever else it shows.
- **Both the browser extension and the Android app must carry the whole feature set.** That
  includes hover translation in the browser, which does not exist today.

## The two projects

### Phonetix

A browser extension plus an Android app in one repository, shipped together in one GitHub
release (signed Firefox XPI, Chrome zip, Android APK).

The extension: WXT + Vite + Svelte 5, Chrome MV3 and Firefox MV2, deliberately unminified.
Roughly 2300 lines of entrypoints, 2200 lines of pure library code, 760 lines of popup.
It annotates words on a page with IPA. Three display modes. A hover tooltip that renders the
IPA symbol by symbol, each symbol clickable for a description, a Wikipedia link, an
articulation diagram, a Seeing Speech link, and audio.

Its data: 54 bundled dictionaries, `{word: ipa}` gzipped JSON, 35 MB total, built offline from
the kaikki Wiktextract dump. Accent overlay files. Homograph classifiers for 10 languages.
espeak-ng compiled to WASM, 23 MB of data, as the last-resort generator. An optional
user-supplied "pack host" URL that replaces a bundled dictionary at runtime.

Resolution is a strict cascade: dictionary for the block's language, then a cross-language
lookup for what that missed, then cached espeak, then live espeak. One answer drives the page,
the tooltip and the audio so the three never disagree.

The Android app: an AccessibilityService that paints IPA over other apps' words in overlay
windows and keeps them on their words while the page scrolls. 6700 lines of Kotlin, 4900 of it
the placement engine. It is a **thin subset** of the extension: one language (English), no
accents, no homographs, no espeak, one display form, five settings against the extension's
twelve.

It has no translation capability of any kind. This was verified rather than assumed.

### Taplex

Android only. 8300 lines of Kotlin in one flat package, six days old, version 0.1, MIT.
Tap a word in any app and get its Wiktionary entry.

Its answer is **a dictionary entry, not a translation**: the lemma the tapped spelling belongs
to, which form it is, and every sense with its marks and examples. Machine translation is the
fallback and the interface labels it a guess.

Its data: a read-only SQLite pack per language pair, with an `entries` table (lemma, part of
speech, IPA, senses as a compressed JSON blob) and a `forms` table mapping every inflected
spelling to its entry with a label. **The pack already carries IPA**, from the same kaikki
Wiktextract dump Phonetix builds its IPA-only dictionaries from. A Phonetix dictionary is a
strict subset of a Taplex pack.

Packs are built **on the phone**, streaming roughly a gigabyte of JSONL from kaikki.org through
a foreground service into SQLite over several minutes. Nothing is bundled.

Three ways of showing an answer, each its own window: an entry card; a draggable circle that
rides above the fingertip and explains whatever it passes without freezing anything and without
taking touches from the app underneath; and a whole-page in-place replacement that repaints
each run of text in the page's own sampled background and ink colours, at a type size derived
from the measured line height, following the page as it scrolls.

Both projects find words the same way: they ask each accessibility node for a rectangle per
character and union those per word. Taplex additionally names two failure cases Phonetix does
not: a node that returns no character locations, and Chrome returning the paragraph box for
every character.

Taplex's weaknesses: one 1905-line class holding five features, an unsynchronised SQLite handle
closed while other coroutines query it, main-thread file writes in the hot loop, and 19
rendered UI states whose comparison task CI never invokes, so they assert nothing.

## Platform reach, researched today

- **Firefox for Android** has run arbitrary AMO extensions since December 2023. It needs
  `browser_specific_settings.gecko_android` in the manifest and MV2, because MV3 background
  service workers do not work there. Content scripts and storage behave as on desktop. Native
  messaging is unavailable. This is close to a drop-in for the existing extension.
- **Chrome for Android** does not support extensions and will not. **Edge for Android** does,
  through the Chromium codepath.
- **iOS**: a Safari Web Extension is shippable, and since 2026 a standards-based ZIP can be
  uploaded to App Store Connect without a Mac build step. Two risks: background service worker
  reliability on iOS has a history of the worker being killed and not woken, and Safari has no
  `wasm-unsafe-eval`, so running WebAssembly needs the broader `unsafe-eval`. The second one
  matters because espeak is WASM.
- **iOS has no equivalent of an Android AccessibilityService.** A third-party app cannot paint
  on another app's window. There is no path to it. The Android overlay has no iOS counterpart,
  and every alternative is user-invoked per instance or shows content in a separate floating
  surface.

So the surfaces are: browser extension on desktop Chrome/Firefox/Edge, Firefox and Edge on
Android, and possibly Safari on iOS; plus the Android native app for everything that is not a
browser. There is no iOS native app.

## Translation engines, researched today

- **Chrome's built-in `Translator` API** is desktop Chromium only. Not in Firefox, not in
  Chrome for Android, not in Safari. It also has hardware gates: 22 GB free disk and 16 GB RAM.
  Unusable as the primary path for a Firefox-first product.
- **Bergamot/Marian compiled to WASM** (MPL-2.0) is bundleable by third parties and works in
  both browsers. Engine about 5 MB. Models are per direction: roughly 16 MB for a "tiny" pair,
  roughly 33 MB for "base", gzipped.
- **ML Kit on-device translation** on Android: free, offline, roughly 30 MB per language,
  requires a "powered by Google" attribution, and its independence from Play Services is
  unverified. Taplex already uses it.
- **CTranslate2 with quantized OPUS-MT** is the only option that gives one engine on all three
  surfaces, at roughly 50-80 MB per pair, at the cost of owning an Android NDK build.
- **The important finding**: for a product that annotates single words, a bilingual dictionary
  lookup answers almost everything, and machine translation is only needed for multi-word
  phrases and dictionary misses. kaikki/Wiktextract is the only source carrying glosses and
  IPA in the same record. This maps exactly onto the cascade Phonetix already has, with a
  translation tier where espeak sits.

## How much can be shared, measured

The extension's library is 2214 lines and almost none of it touches a browser API:
about 900 lines are data tables that want to be JSON (IPA symbols, the language table,
accents, regional rules), about 700 lines are pure logic (homographs, segmentation, the
sprinkle curve, IPA normalisation, language detection, the display transform), about 400 are
CSS, and about 200 are platform glue.

The Android app already hand-duplicates roughly 500 lines of that in Kotlin.

The existing product keeps its two halves honest with generated vector files and port tests:
a file of input-and-expected-answer cases is generated from one side, and the other side has a
test that reads it and asserts it gives the same answers. Where a pair is covered that way,
drift fails the build. Where it is not, drift is silent. This is offered as evidence that the
approach works at small scale, not as a reason to keep it.

The owner's stated reason for caring about sharing is not saved lines. It is that a change made
to one side must not be possible to make without the other side being noticed, particularly
when the change is being made by a language model that has only one of the two files in front
of it. Weigh the options against that, not against line count.

The owner's own preference is a Rust core. Treat that as a strong prior to be tested rather
than as a decision already made: if it is right, say why in terms that beat the alternatives on
merit; if it is wrong, say so plainly and say what beats it.

Options considered, with today's status:
- Duplicate the logic in both languages, share generated data, and hold each pair with a port
  test. Zero runtime cost and one toolchain per side. Every feature is written twice, and drift
  is loud only where a test covers it.
- Kotlin Multiplatform. Kotlin/JS is mature; Kotlin/Wasm is still Beta as of September 2026.
  The Android side then has no foreign-function boundary at all, because the core is already
  Kotlin. The browser side consumes generated JavaScript, which costs bundle size and makes
  the browser-side code harder to step through.
- A TypeScript core run on Android inside QuickJS. Workable only if the shared piece is called
  once per scan with a batch, never per word, because the Android hot path re-reads the
  accessibility tree many times a second.
- A Rust core, WASM in the browser and a native library on Android through uniffi. The most
  proven "one real core" option, at the cost of a third toolchain and moving the logic out of
  TypeScript.

## What to decide

Answer all of these. Be decisive, give the reason, and say what each choice costs.

1. **The product model.** What is the thing the reader manipulates, and what are its states?
   How do translation and IPA compose in one annotation without the page becoming unreadable?
   What appears inline versus only on hover or tap? What happens when there is no pack, no
   entry, an unknown source language, or only a machine-translated guess?
2. **The pack format**, and the language-pair problem. A pack today is per language. A
   translation is per pair, and 54 source languages against any target is thousands of
   combinations. Decide the format and how it is built and delivered, given that the browser
   cannot build a pack from a gigabyte dump and that Android currently does exactly that.
   Note that a Taplex pack is SQLite, which a browser cannot open natively.
3. **The module structure across both platforms.** Name the modules, say what each owns, and
   draw the line between what is shared, what is generated, and what is inherently
   platform-specific. Pick one of the four sharing options above, or a combination, and justify
   it against the owner's actual concern. Say explicitly where the foreign-function boundary
   sits, how often it is crossed, and what crosses it, because the Android side re-reads the
   accessibility tree many times a second and a boundary in the wrong place is fatal there.
   Consider in particular whether one binary pack format can be read by one implementation on
   both platforms, since the data layer may be a larger shared surface than the logic.
4. **Which Android overlay survives.** Phonetix's placement engine is measured and has a test
   suite that photographs the screen; Taplex's has features Phonetix lacks. Decide what is
   carried over and in which direction, and what is thrown away.
5. **The layout of the answer surface**: the tooltip in the browser and the card on Android.
   It must carry a translation, the IPA, the part of speech, the form the reader tapped, senses
   with their marks and examples, and provenance (dictionary or machine guess). It must work on
   a phone and on a desktop. Design it.

## What to hand back, and in what shape

Four artefacts. No prose essays outside them.

1. **Decision records.** One per decision, each with the context, the options with what each
   costs, the decision, and its consequences. Short.
2. **A module table.** One row per module: name, what it owns, which platform it runs on,
   whether it is shared, generated or platform-specific, and what it depends on. Plus
   interface sketches that are types and signatures only, no implementation.
3. **The view model**, in this exact schema, which is already in use in these repositories:
   a JSON object with `requirements`, `views`, `states` and `stories` arrays. Each entry has a
   `uid` (like `VIEW-HOME`, `STATE-ENTRY-NONE`, `REQ-ENTRY`), a `tag` (`VIEW`, `STATE`, `REQ`),
   a `title`, a one-sentence `statement` saying what it is for or what has to be true of it, a
   `status`, a `relations` array of `{role, to}` pairs, and for views and states a `renders`
   array of image filenames and a `sources` array of file paths. Views also carry a `states`
   array of state titles. Cover the merged product, not either app alone.
4. **The answer surface as real HTML and CSS**, one self-contained file, showing every state
   from the view model. Light and dark. It will be rendered and looked at, so it has to stand
   up as a rendering rather than as a description. Use CSS custom properties for every colour,
   size and spacing value, declared once at the top, because those become the shared design
   tokens the Android side is built from.
