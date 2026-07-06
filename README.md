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
- **Regional variants** — data-driven IPA transforms (`src/lib/regions.ts`) for
  accents like Latin-American Spanish (seseo, yeísmo) and Brazilian Portuguese.
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
pnpm dev              # Chrome
pnpm dev:firefox      # Firefox
pnpm build            # + build:firefox
pnpm zip:firefox      # distributable; sign unlisted with web-ext for install
pnpm check            # svelte-check
pnpm build:dict       # rebuild IPA dictionaries from the kaikki dump
```

## Testing

- `node --experimental-strip-types scripts/test-homograph.ts` — homograph unit test.
- `scripts/proofread/` — drives the built extension in headless chromium over a
  corpus of real pages (CDP over `--remote-debugging-pipe`), extracts every
  rendered IPA span, and quantifies failure classes (`analyze.py`). This is how
  the resolution cascade is regression-checked against real content.
