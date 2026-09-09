import { get, set, createStore } from 'idb-keyval';

// Bump version when IPA output format changes (invalidates all cached entries)
const CACHE_VERSION = 'v2';
const ipaStore = createStore('phonetix-ipa', 'word-ipa');

export async function getCachedBatch(
  words: string[],
  voice: string
): Promise<{ cached: Record<string, string>; uncached: string[] }> {
  const cached: Record<string, string> = {};
  const uncached: string[] = [];

  for (const word of words) {
    const key = `${CACHE_VERSION}:${voice}:${word}`;
    const ipa = await get<string>(key, ipaStore);
    if (ipa !== undefined) {
      cached[word] = ipa;
    } else {
      uncached.push(word);
    }
  }

  return { cached, uncached };
}

export async function setCachedBatch(
  results: Record<string, string>,
  voice: string
): Promise<void> {
  for (const [word, ipa] of Object.entries(results)) {
    const key = `${CACHE_VERSION}:${voice}:${word}`;
    await set(key, ipa, ipaStore);
  }
}

// ─── Downloaded dictionary packs ──────────────────────────────────────
// Dictionaries fetched from a remote pack host are cached here so a language is
// downloaded once, then works offline like a bundled one.
const dictStore = createStore('phonetix-dicts', 'lang-dict');

export function getCachedDict(lang: string): Promise<Record<string, string> | undefined> {
  return get<Record<string, string>>(lang, dictStore);
}

export function setCachedDict(lang: string, obj: Record<string, string>): Promise<void> {
  return set(lang, obj, dictStore);
}

// ─── Dictionary packs the core reads ──────────────────────────────────
// A lex pack is a file the core opens as bytes, not a table this side reads, so it is kept
// as it arrived. Downloaded once and then held here, because a pack is tens of megabytes
// and a reader on a train is the reader who needs it most.
const packStore = createStore('phonetix-packs', 'lang-pack');

export async function getCachedPack(lang: string): Promise<Uint8Array | undefined> {
  const bytes = await get<ArrayBuffer>(lang, packStore);
  return bytes === undefined ? undefined : new Uint8Array(bytes);
}

export function setCachedPack(lang: string, bytes: Uint8Array): Promise<void> {
  // Stored as a plain buffer: a typed array survives structured cloning, but the buffer is
  // what every reader of this wants and it is one copy either way.
  return set(lang, bytes.slice().buffer, packStore);
}
