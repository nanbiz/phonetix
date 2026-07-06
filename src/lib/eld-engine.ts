// ─── eld language detection engine ───────────────────────────────────
// eld's own loader fetches its ngram model with a computed dynamic import()
// (`import('./ngrams/ngrams'+size+'60.js')`) which no bundler can resolve and
// which a Chrome MV3 service worker forbids outright. Instead we statically
// import the ngram data (bundleable) and inject it into eld's shared state — the
// same thing eld does internally to build its minified files — so detection runs
// synchronously in the service worker with no dynamic import at all.

import { eld } from '@yutengjing/eld';
// @ts-expect-error - eld internals ship no type declarations
import { languageData } from '@yutengjing/eld/src/languageData.js';
// @ts-expect-error - eld ngram data ships no type declarations
import { ngramsData } from '@yutengjing/eld/src/ngrams/ngramsM60.js';
import { Languages } from '@/lib/types';

let injected = false;
function ensureNgrams(): void {
  if (injected) return;
  const data = ngramsData as { languages: Record<string, string>; ngrams: unknown; type: string };
  languageData.langCodes = data.languages;
  languageData.langScore = Array(Object.keys(data.languages).length).fill(0);
  languageData.ngrams = data.ngrams;
  languageData.type = data.type;
  injected = true;
}

export interface BlockScore {
  /** Candidate languages, most likely first (only codes phonetix supports). */
  ranked: string[];
  /** eld's own confidence flag for this text. */
  reliable: boolean;
}

/** Ranked language candidates per text. Ranked (not just the single top guess)
 *  so short mixed-language text can still be resolved by dictionary coverage. */
export function scoreTexts(texts: string[], k = 5): BlockScore[] {
  ensureNgrams();
  return texts.map((t) => {
    if (!t || t.length < 3) return { ranked: [], reliable: false };
    const res = (eld as unknown as { detect: (s: string) => any }).detect(t);
    const scores = res.getScores() as Record<string, number>;
    const ranked = Object.entries(scores)
      .filter(([l]) => l in Languages)
      .sort((a, b) => b[1] - a[1])
      .slice(0, k)
      .map(([l]) => l);
    return { ranked, reliable: res.isReliable() };
  });
}
