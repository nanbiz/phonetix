# phonetix

Browser extension that overlays IPA pronunciations on web pages, for language
learners. Reads any page, resolves each word to the International Phonetic
Alphabet, and shows it inline or on hover, with a per-word tooltip (symbol
breakdown, audio, Wiktionary link). Works in Chrome and Firefox from one
codebase (WXT + Svelte 5).

## How a word is resolved

Every word goes through a quality-ordered cascade (`background.ts` `phonemize`).
The first tier that answers wins; the same result drives the page span, the
tooltip headline, and the spoken audio, so all three always agree.

1. **Block-language dictionary** — offline IPA dictionaries built from Wiktionary
   (kaikki dump + per-language Wiktionary TSVs), one per language.
2. **Cross-language fallback** — a word missing from the page's dictionary
   (loanwords, brand names, proper nouns like *Renault*, *Mount Everest*) is
   looked up in English before falling through, so it is not mispronounced in
   the page language.
3. **espeak-ng** — grapheme-to-phoneme WASM, the last-resort coverage net.
   Skipped for abjads (Arabic/Hebrew) and Han, where espeak spells out letter
   names; those dictionary-misses show the original word instead.

On top of the cascade:

- **Homograph disambiguation** — context classifiers (Yarowsky decision rules +
  keyword scoring, `src/lib/homograph.ts`, data in `public/homographs/`) pick the
  right pronunciation of *read*, *live*, *record*, … from surrounding words.
- **Accents** — an accent is the same words pronounced differently, and it is
  built from whichever of two sources actually carries it (`src/lib/accents.ts`):

  - **Tagged data**, where the source has it. Wiktionary labels pronunciations by
    accent, so `scripts/build-accents.mjs` extracts them into overlays holding
    the words an accent says differently from the standard, which the background
    lays over the base dictionary. English has 27k American and 12k British
    words; Portuguese, Catalan, Cantonese, Persian, Armenian, Welsh, Irish,
    Basque and Vietnamese have their own.
  - **Rules**, where it does not. Wiktionary tags almost no Spanish word for
    accent, and only a few hundred German ones, while the shifts that define
    those accents hold for the whole vocabulary: seseo and yeísmo for Latin
    America, žeísmo for Rioplatense, and for Swiss Standard German no ich-Laut,
    a trilled `r`, no glottal stop (`src/lib/regions.ts`). Rules also reach the
    words no dictionary knows, since they apply to espeak's output too. The
    popup says when an accent is rule-derived.

  A *dialect* (Swabian, Saxon, Swiss German proper) is not an accent: it has its
  own words and grammar, so it would need its own dictionary as its own language,
  and none is shipped. espeak has no voice for many accents and answers with
  nonsense when asked for one it lacks, so voices are declared only where
  `scripts/proofread/voice_check.py` proves they exist.
- **Segmentation** — `Intl.Segmenter` (`src/lib/segment.ts`) tokenizes every
  script (Latin, Cyrillic, Greek, Arabic, CJK, Thai, …), not just Latin.
- **Language detection** — per-block via `eld`, plus the page `lang` attribute.
- **Chrome filtering** — nav, footer, buttons and ARIA landmarks are skipped
  (structural, `CHROME_SELECTOR`); article content is kept.

## Cross-browser espeak

espeak-ng needs a DOM/AudioContext. Chrome MV3's background is a service worker
(neither), so it runs in an **offscreen document**. Firefox MV3's background is
an event page with DOM, so it runs the **same engine directly** in the
background (`src/lib/espeak-engine.ts`); the offscreen permission is Chrome-only
(`wxt.config.ts` manifest function). Audio is synthesized where the engine runs
and, on Firefox, played in the content script (which has the user gesture).

## Layout

```
src/entrypoints/  background.ts · content.ts · offscreen/ · popup/
src/lib/          types.ts (Languages, ResolvedIpa) · homograph.ts · regions.ts
                  segment.ts · espeak-engine.ts · messaging.ts · cache.ts
public/           dictionaries/*.json.gz · homographs/*.json.gz · espeak/
scripts/          build-dictionaries.mjs · evaluate-ipa.mjs · proofread/
```

## Develop

```sh
pnpm install
pnpm fetch:dict       # required once: download the prebuilt IPA dictionaries
pnpm dev              # Chrome
pnpm dev:firefox      # Firefox
pnpm build            # + build:firefox
pnpm zip:firefox      # distributable; sign unlisted with web-ext for install
pnpm check            # svelte-check
pnpm build:dict       # rebuild the dictionaries from the ~2.3GB kaikki dump
```

The dictionaries are large binary data and are not committed. `fetch:dict` pulls
the prebuilt set from a release asset; `build:dict` regenerates them from the
kaikki dump. Without them the extension falls back to espeak for every word.

## Testing

Three layers, all runnable from `package.json`:

```sh
pnpm test              # unit: segmenter, script gating, homographs, language decision
pnpm test:integration  # behavioral: controlled fixtures via CDP, hard assertions
pnpm test:e2e          # behavioral: a few real sites, invariants
pnpm test:all          # all three
```

- **Unit** (`scripts/test-*.ts`, node `--experimental-strip-types`): pure logic —
  `homograph`, `resolution` (segmentation + espeak script gating), `langdetect`
  (per-block language decision).
- **Integration / e2e** (`scripts/proofread/suite.py`): builds must exist
  (`pnpm build`); drives the extension in headless chromium over CDP (a
  `--remote-debugging-pipe`, since a debug port is killed by the sandbox on some
  hosts). Asserts a subsystem **health check** (language detection, dictionary and
  espeak are actually alive, not silently degraded), per-title language on mixed
  pages, no letter-name garbage, and non-Latin coverage.
- **Firefox** (`pnpm test:firefox`): the CDP suite drives Chrome, so this drives
  the real Firefox build headless via Marionette and asserts the health probe —
  espeak runs in the background page there, a different path than Chrome's
  offscreen document. Needs a built + signed Firefox xpi in `.output/signed/`.
- **Proofreading** (`scripts/proofread/harness.py` + `analyze.py`): sweeps a large
  corpus and quantifies failure classes for exploratory regression checking.
