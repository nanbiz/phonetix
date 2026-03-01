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
