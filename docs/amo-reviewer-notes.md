# AMO reviewer notes — building phonetix from source

Paste this into the "Notes to reviewer" / source instructions when submitting a
version, and upload the source archive `phonetix-<version>-sources.zip` (produced by
`pnpm zip:firefox`) as the source code for that version.

## What the build produces

`pnpm build:firefox` compiles the extension (WXT + Vite + Svelte 5) into
`.output/firefox-mv2/`, which is what the signed xpi contains. The build is **not
minified** (`vite build.minify = false` in `wxt.config.ts`), so the packaged JavaScript
in the xpi matches the compiled source directly and can be diffed against this source.

## Toolchain

- Node.js 22
- pnpm (the exact version is pinned in `package.json` `packageManager`; the lockfile is
  `pnpm-lock.yaml`, committed)
- No network is needed for the build itself if the bundled data is used (see below).

## Build steps

```sh
pnpm install            # installs deps from the committed lockfile
pnpm build:firefox      # → .output/firefox-mv2  (the contents of the xpi)
```

Then compare `.output/firefox-mv2/` against the signed package.

## Notes on the parts a reviewer will ask about

- **`postinstall`** runs `scripts/install-browsers.mjs` (downloads Chrome/Firefox
  binaries used only for the local test harness) and `scripts/copy-espeak.mjs`. The
  browser download is **development-only** and not part of the shipped extension; it can
  be skipped with `--ignore-scripts` on install if the espeak files are already present.

- **espeak-ng** (`public/espeak/espeak-ng.js` + `espeak-ng.data`) is the open-source
  espeak-ng speech synthesizer compiled to WebAssembly. It is **copied verbatim** from
  the npm package `@echogarden/espeak-ng-emscripten` (version pinned in the lockfile) by
  `scripts/copy-espeak.mjs` — it is not built or modified here. The 24 MB `.data` file is
  espeak's pronunciation data. It runs only inside the extension's own background/offscreen
  page (`wasm-unsafe-eval` in the CSP is for this WASM and applies to extension pages
  only); no remote code is fetched or executed.

- **Dictionaries** (`public/dictionaries/*.json.gz`) and the espeak WASM blobs are
  **data, not code**, and are not in this source archive (they are large and do not
  affect the JavaScript you are diffing). The build copies them into the output verbatim
  if present; if absent the JavaScript is byte-for-byte identical and the extension simply
  falls back to espeak at runtime. To reproduce them: the dictionaries are generated from
  the public kaikki Wiktionary dump by `scripts/build-dictionaries.mjs` (`pnpm build:dict`)
  or fetched with `pnpm fetch:dict`; espeak is copied by `postinstall`
  (`scripts/copy-espeak.mjs`) from the npm package `@echogarden/espeak-ng-emscripten`.
  The reviewable code is `src/` → the JavaScript in the packaged xpi.

- **`common-words.json`** (top words per language, used by the sparse "sprinkle" display)
  is generated from the `wordfreq` dataset by `scripts/build-common-words.py` and committed.

## Data handling

At runtime the extension fetches, per word the user hovers, from the Wikimedia
Foundation only: `*.wiktionary.org` (IPA + word language, anonymous CORS with
`origin=*`) and `commons.wikimedia.org` (audio recordings, articulation diagrams). Page
text is language-detected locally and not sent anywhere except those per-word lookups.
No analytics, no accounts, no other network destinations. An optional user-entered
"dictionary pack host" (empty by default) fetches a gzip-JSON pronunciation data file if
set — data, not code.
