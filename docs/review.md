# Project review — correctness, publishing, test gaps

Independent review of the whole project (three separate passes). Verified against the
code, not guessed. Ordered by what to act on first.

## Publishing: what has to change

No code blockers, and no security holes were found — no XSS, no remote-code path
(espeak is bundled WASM, not fetched), no page-reachable open proxy, no telemetry. The
work to get listed is paperwork and packaging, not fixes.

**Both stores**
- Privacy policy at a stable URL. The extension sends the words a reader hovers to the
  Wikimedia Foundation (`*.wiktionary.org` API, `commons.wikimedia.org`) to fetch IPA,
  audio and diagrams; page text is language-detected locally and not sent. State: no
  accounts, no analytics, no sale/transfer; the only third party is Wikimedia, plus an
  optional user-set pack host.
- Listing: short + full description, a category (Education / Language, or
  Accessibility), and screenshots (reuse `docs/hero.png`, `docs/modes.png`,
  `docs/tooltip*.png`). Public copy is the owner's to write.
- A written justification for `<all_urls>`: it overlays pronunciation on any page the
  reader reads, so it must run everywhere; it reads text only to transcribe it and
  stores nothing off-device.
- Decide how to present the optional remote dictionary-pack host: advanced, off by
  default, fetches a data file (word→IPA JSON), never code.

**Firefox (AMO, listed)**
- Switch `release.yml` from `--channel=unlisted` to a listed submission; listed
  triggers human review.
- Upload the sources zip (`wxt zip -b firefox` already emits
  `phonetix-<v>-sources.zip`) with reviewer build notes: Node 22, pnpm (lockfile
  committed), `pnpm build:firefox`; the dictionaries are a fetched release asset
  (`pnpm fetch:dict`) but the sources zip bundles the built `.json.gz`; `postinstall`
  downloads dev browsers (skippable); espeak `.js/.data` are copied verbatim from
  `@echogarden/espeak-ng-emscripten`.
- Add `browser_specific_settings.gecko.strict_min_version` (only `id` is set now).

**Chrome Web Store (listed)**
- Developer registration (one-time fee).
- Per-permission justifications for `<all_urls>`, `storage`, `offscreen`.
- Attest "no remote code" explicitly — the item most likely to draw a rejection.
  espeak is local WASM; `wasm-unsafe-eval` is for that and applies to extension pages
  only; every fetch returns data (JSON/audio/SVG), never code.
- Data-safety disclosures + privacy-policy link; declare the optional remote host.

## Correctness bugs found (ranked)

1. **Transient failures cached as permanent negatives.** `lookupWiktionary` caches a
   `miss` and `symbolDiagram` caches `''` on *any* fetch error, then never retries for
   the life of the worker. A one-second network blip permanently disables a word's
   Wiktionary link + recording button and blanks a symbol's diagram. Directly
   contradicts the policy `loadDictionary` already follows ("do not cache the failure").
   Fix: distinguish a genuine 404 miss from a fetch error; never cache errors.
2. **Popup writes unchanged settings on open.** The `$effect`s for language, mode and
   sprinkle density fire once on load and write the just-loaded values back; every tab
   watches those keys and runs a full `reprocess()`. So merely opening the popup can
   flash a re-render on all open tabs. Fix: guard the writes to real user changes.
3. **`reprocess()` not serialized.** A second setting change during the first
   reprocess's batched `await` can leave a page with a mix of old/new spans until the
   next change. Fail-safe against double-wrapping, but visibly inconsistent. Fix: an
   in-flight guard/queue.
4. **Observer re-enqueues each rewritten block.** `transformNode` inserts plain text
   nodes beside the spans; the observer sees them and re-scans the whole block. It
   converges and doesn't double-wrap, but doubles tree-walking per change on big/live
   pages. Fix: mark our own inserted nodes.
5. **`audioLanguage` false-positives drop real recordings.** A Commons file like
   `Cat-call.ogg` parses as language `cat` (Catalan) and is dropped for an English
   word. Rare filename/ISO collisions silently lose audio.
6. **No timeout on the offscreen phonemize round-trip.** If the offscreen doc never
   answers, `processMultilingual`'s `Promise.all` hangs and `main()` never reaches
   `observeDOM()` — page left half-transcribed with no dynamic-DOM handling.
7. **`separable` classId case-mismatch** in homograph disambiguation (case-insensitive
   match, case-sensitive `find`) — a data-dependent silent no-op.

Cleanup (not bugs): `pickBestIpa`/`ipaDistance` are dead code (no call site);
`wiktIpa`/`allIpas` are parsed but never displayed (deliberate "one source" design, so
the parse output is effectively dead); `cache.ts` does N serial IndexedDB ops per batch;
`revertAll` calls `document.body.normalize()` over the whole page each reprocess.

## Test gaps (ranked)

1. **Wiktionary parsing has zero tests.** `parseWikitext`/`tryWiktionaryLang` (~190
   lines, per-language regexes) drive the tooltip's link/recording/IPA and are exercised
   by nothing. A broken regex fails invisibly. Add node tests on saved wikitext fixtures.
2. **`audio_lang.py` is vacuous on empty results** — missing audio and unparseable
   filenames both pass. If the whole lookup died it stays green. Assert that K of N
   known-recorded words actually return a correct-language recording.
3. **`phonemize` tier cascade has no unit test** — overlay-vs-`applyRegion`, cross-dict
   fallback, espeak script-gating, letter-spelling drop are only checked by aggregate
   symptoms.
4. **Homographs are English-only** — ja readings, de/nl separable verbs, CJK-compound,
   POS fallback branches are unreached.
5. **Firefox runs only ~4 of ~12 behavioral tests** — sprinkle, voices, audio, packs,
   diagram, popup-layout, symbol-names and the whole integration suite are Chrome-only;
   `sprinkle_check.py` hard-skips Firefox. This is exactly the "works in Chrome, does
   nothing in Firefox" class the CI comment warns about.
6. **`suite.py` `no_variant_junk` guard is defined but never called** — nothing asserts
   rendered on-page IPA is free of variant punctuation.
7. **`displayIpa` broad/narrow strip untested** — the regex deciding what every reader
   sees inline vs in the tooltip has no unit test.
8. **`collectTextNodes` skip heuristics untested** (TECHNICAL_RE, bare-domain, URL-link).
9. **`e2e` phase not in CI**, and its only assertion is a bare span count.
10. **diagram/pack/popup checks prove presence, not correctness** (any high-contrast
    image, any `n>5`, any non-empty control passes).
