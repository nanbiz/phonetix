# Chrome Web Store — privacy / data-safety disclosure

Chrome takes this in the Developer Dashboard ("Privacy practices" tab), not the
manifest. Below are the answers to give, matching what the code actually does.

## What phonetix does with data

- It reads the text of the page to detect language and to transcribe words locally
  (bundled dictionaries + the espeak WASM). That processing is **on-device**.
- When the user **hovers** a word, that single word (website content) is sent to the
  **Wikimedia Foundation** — `*.wiktionary.org` (IPA + the word's language) and
  `commons.wikimedia.org` (an audio recording and an articulation diagram). Anonymous
  request (`origin=*`, no cookies, no account).
- Optionally, if the user sets a "dictionary pack host" (empty by default), pronunciation
  **data** files are fetched from that user-supplied URL.
- No analytics, no telemetry, no account, no advertising. Nothing is sold or transferred.

## "Data use" checkboxes to select

Collected/used: **Website content** — "the word the user hovers is sent to Wikimedia to
fetch its pronunciation, audio, and articulation diagram."

Do **not** check: personally identifiable info, health, financial, authentication,
personal communications, location, web history, user activity, or any other category —
none of those are collected.

Certifications (check all three, they are true):
- I do **not** sell or transfer user data to third parties (outside the approved use case).
- I do **not** use or transfer user data for purposes unrelated to the item's single purpose.
- I do **not** use or transfer user data to determine creditworthiness or for lending.

Single purpose statement: "Show IPA pronunciations for the words on a web page."

Permission justifications:
- `host_permissions` / `<all_urls>`: the extension overlays pronunciation on any page the
  user reads, so it must run on any site. It reads text only to transcribe it and stores
  nothing off-device.
- `storage`: saves the user's settings (frequency, accent, toggles) locally.
- `offscreen`: runs the espeak-ng WASM synthesizer (no DOM in the MV3 service worker).

Remote code: **none.** espeak-ng is bundled WASM loaded from a packaged URL; every fetch
returns data (JSON/audio/SVG), never executable code. `wasm-unsafe-eval` in the CSP is for
that local WASM and applies to extension pages only.

## Privacy policy (required URL) — facts to host

Phonetix does not collect, store, or sell personal data. It processes the text of pages
you visit locally to show IPA pronunciations. When you hover a word, that word is sent to
the Wikimedia Foundation (wiktionary.org, commons.wikimedia.org) to fetch its
pronunciation, an audio recording, and an articulation diagram; these requests are
anonymous and carry no account or identifier. If you configure an optional dictionary pack
host, pronunciation data is fetched from the URL you provide. No analytics or tracking of
any kind is used. Settings are stored only in your browser.
