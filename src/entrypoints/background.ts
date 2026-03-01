import { onMessage } from '@/lib/messaging';
import type { WiktionaryInfo } from '@/lib/messaging';
import { getCachedBatch, setCachedBatch } from '@/lib/cache';
import { Languages, WiktionaryLanguages, LANG_NAME_TO_CODE } from '@/lib/types';
import type { Language } from '@/lib/types';

// ─── Offscreen document management ──────────────────────────────────

let offscreenCreated = false;

async function ensureOffscreen() {
  if (offscreenCreated) return;

  try {
    const contexts = await (chrome.runtime as any).getContexts({
      contextTypes: ['OFFSCREEN_DOCUMENT'],
    });
    if (contexts && contexts.length > 0) {
      offscreenCreated = true;
      return;
    }
  } catch {
    // getContexts not available — try creating directly
  }

  try {
    await chrome.offscreen.createDocument({
      url: 'offscreen.html',
      reasons: [chrome.offscreen.Reason.WORKERS],
      justification: 'Run espeak-ng WASM for IPA phonemization',
    });
    offscreenCreated = true;
  } catch (e: any) {
    if (e.message?.includes('Only a single offscreen')) {
      offscreenCreated = true;
    } else {
      throw e;
    }
  }
}

async function phonemizeViaOffscreen(
  words: string[],
  voice: string
): Promise<Record<string, string>> {
  await ensureOffscreen();

  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage(
      {
        target: 'offscreen',
        type: 'phonemize-batch',
        data: { words, voice },
      },
      (response: any) => {
        if (chrome.runtime.lastError) {
          reject(new Error(chrome.runtime.lastError.message));
          return;
        }
        if (response?.success) {
          resolve(response.results);
        } else {
          reject(new Error(response?.error || 'Phonemization failed'));
        }
      }
    );
  });
}

// ─── Dictionary loading ─────────────────────────────────────────────

const dictCache = new Map<string, Map<string, string>>();
const dictLoading = new Map<string, Promise<Map<string, string>>>();

async function loadDictionary(lang: string): Promise<Map<string, string>> {
  if (dictCache.has(lang)) return dictCache.get(lang)!;
  if (dictLoading.has(lang)) return dictLoading.get(lang)!;

  const promise = (async () => {
    try {
      const url = chrome.runtime.getURL(`dictionaries/${lang}.json.gz`);
      const res = await fetch(url);
      if (!res.ok) {
        console.warn(`[Phonetix] No dictionary for ${lang}`);
        const empty = new Map<string, string>();
        dictCache.set(lang, empty);
        return empty;
      }

      // Decompress gzip using browser's built-in DecompressionStream
      const ds = new DecompressionStream('gzip');
      const decompressed = res.body!.pipeThrough(ds);
      const text = await new Response(decompressed).text();
      const obj = JSON.parse(text) as Record<string, string>;

      const map = new Map<string, string>();
      for (const [word, ipa] of Object.entries(obj)) {
        map.set(word, ipa);
      }

      dictCache.set(lang, map);
      console.log(`[Phonetix] Loaded ${lang} dictionary: ${map.size.toLocaleString()} entries`);
      return map;
    } catch (e) {
      console.warn(`[Phonetix] Failed to load dictionary for ${lang}:`, e);
      const empty = new Map<string, string>();
      dictCache.set(lang, empty);
      return empty;
    } finally {
      dictLoading.delete(lang);
    }
  })();

  dictLoading.set(lang, promise);
  return promise;
}

function lookupDictionary(words: string[], dict: Map<string, string>): { found: Record<string, string>; notFound: string[] } {
  const found: Record<string, string> = {};
  const notFound: string[] = [];

  for (const word of words) {
    const ipa = dict.get(word) || dict.get(word.toLowerCase());
    if (ipa) {
      found[word] = ipa;
    } else {
      notFound.push(word);
    }
  }

  return { found, notFound };
}

// ─── Language detection via eld ──────────────────────────────────────

let eldInstance: any = null;
let eldInitPromise: Promise<void> | null = null;

async function initEld() {
  if (eldInstance) return;
  const { eld } = await import('@yutengjing/eld');
  await eld.init('S'); // 'S' = small ngram database, fast + lightweight
  eldInstance = eld;
}

async function detectLang(text: string): Promise<Language> {
  if (!eldInitPromise) eldInitPromise = initEld();
  await eldInitPromise;

  const result = eldInstance.detect(text);
  if (result.language && result.language in Languages) {
    return result.language as Language;
  }
  return 'en'; // fallback
}

// ─── Wiktionary lookup ──────────────────────────────────────────────

const wiktCache = new Map<string, WiktionaryInfo>();


async function lookupWiktionary(detectedLang: Language, word: string): Promise<WiktionaryInfo> {
  const cacheKey = `${detectedLang}:${word.toLowerCase()}`;
  if (wiktCache.has(cacheKey)) return wiktCache.get(cacheKey)!;

  const miss: WiktionaryInfo = { exists: false, audioUrl: null, matchedTitle: null, foundLang: null, wordLang: null, wiktIpa: null };

  // Only try Wiktionary editions that have parsing support
  const wiktLangs = WiktionaryLanguages;

  // Phase 1: Try detected language's Wiktionary (if it has parsing support)
  if (wiktLangs.includes(detectedLang)) {
    const primary = await tryWiktionaryLang(detectedLang, word, detectedLang);
    if (primary?.wordLang === detectedLang) {
      wiktCache.set(cacheKey, primary);
      return primary;
    }

    // Phase 2: Try other Wiktionaries with parsing support
    const others = wiktLangs.filter(l => l !== detectedLang);
    const results = await Promise.all(others.map(l => tryWiktionaryLang(l, word, detectedLang)));

    let matchingLang: WiktionaryInfo | null = null;
    for (const r of results) {
      if (!r) continue;
      if (r.wordLang === detectedLang) {
        if (!matchingLang || (r.audioUrl && !matchingLang.audioUrl)) matchingLang = r;
      }
    }
    if (matchingLang) {
      wiktCache.set(cacheKey, matchingLang);
      return matchingLang;
    }

    // Phase 3: Accept any entry
    const allResults = [primary, ...results].filter(Boolean) as WiktionaryInfo[];
    let best: WiktionaryInfo | null = null;
    for (const r of allResults) {
      if (!best || (r.audioUrl && !best.audioUrl)) best = r;
    }
    const result = best || miss;
    wiktCache.set(cacheKey, result);
    return result;
  }

  // For languages without Wiktionary parsing: try English Wiktionary only
  if (wiktLangs.includes('en')) {
    const result = await tryWiktionaryLang('en', word, detectedLang);
    if (result) {
      wiktCache.set(cacheKey, result);
      return result;
    }
  }

  wiktCache.set(cacheKey, miss);
  return miss;
}

/**
 * Try a single language's Wiktionary for a word.
 * Tries multiple capitalizations; prefers entries where wordLang matches
 * expectedWordLang (e.g. skip Norwegian "Time" when we want English "time").
 */
async function tryWiktionaryLang(
  lang: Language,
  word: string,
  expectedWordLang?: Language
): Promise<WiktionaryInfo | null> {
  const cfg = Languages[lang];
  if (!cfg?.wiktLangRe || !cfg?.wiktIpaRe) return null;

  // Try original → lowercase → Title Case → UPPERCASE
  const candidates = [word];
  const lower = word.toLowerCase();
  if (lower !== word) candidates.push(lower);
  const titleCase = word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
  if (titleCase !== word && titleCase !== lower) candidates.push(titleCase);
  const upper = word.toUpperCase();
  if (upper !== word && upper !== titleCase) candidates.push(upper);

  let fallback: WiktionaryInfo | null = null;

  for (const candidate of candidates) {
    try {
      // Fetch images + full wikitext (no rvsection — we need all language sections)
      const apiUrl =
        `https://${lang}.wiktionary.org/w/api.php` +
        `?action=query` +
        `&titles=${encodeURIComponent(candidate)}` +
        `&prop=images|revisions` +
        `&imlimit=50` +
        `&rvprop=content` +
        `&rvlimit=1` +
        `&format=json` +
        `&origin=*`;

      const res = await fetch(apiUrl);
      if (!res.ok) continue;

      const json = await res.json();
      const pages = json?.query?.pages;
      if (!pages) continue;

      const pageId = Object.keys(pages)[0];
      if (!pageId || pages[pageId]?.missing !== undefined) continue;

      const matchedTitle: string = pages[pageId]?.title || candidate;
      const images: { title: string }[] = pages[pageId]?.images || [];
      const audioFilename = findBestAudio(images, candidate, lang);
      const audioUrl = audioFilename
        ? `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(audioFilename)}`
        : null;

      // Parse full wikitext — prefer section matching expectedWordLang
      const wikitext: string = pages[pageId]?.revisions?.[0]?.['*'] || '';
      const { wordLang, wiktIpa } = parseWikitext(wikitext, lang, expectedWordLang);

      const result: WiktionaryInfo = { exists: true, audioUrl, matchedTitle, foundLang: lang, wordLang, wiktIpa };

      // If wordLang matches what we want, return immediately
      if (!expectedWordLang || wordLang === expectedWordLang) return result;

      // Otherwise save as fallback and try next capitalization
      if (!fallback || (result.audioUrl && !fallback.audioUrl)) fallback = result;
    } catch {
      continue;
    }
  }

  return fallback;
}

/**
 * Parse Wiktionary wikitext to extract the word's actual language and IPA.
 */
function parseWikitext(
  wikitext: string,
  wiktLang: Language,
  preferredLang?: Language
): { wordLang: string | null; wiktIpa: string | null; allIpas: string[] } {
  if (!wikitext) return { wordLang: null, wiktIpa: null, allIpas: [] };

  const cfg = Languages[wiktLang];
  if (!cfg?.wiktLangRe || !cfg?.wiktIpaRe) return { wordLang: null, wiktIpa: null, allIpas: [] };

  // Split into level-2 sections (each starts with == ... ==)
  const sections = wikitext.split(/(?=^==[^=])/m);

  function resolveSectionLang(section: string): string | null {
    const m = section.match(cfg.wiktLangRe!);
    if (!m?.[1]) return null;
    const raw = m[1].trim();
    return cfg.wiktLangIsName
      ? (LANG_NAME_TO_CODE[raw] || raw.toLowerCase().slice(0, 2))
      : raw;
  }

  function extractAllIpas(section: string): string[] {
    const re = new RegExp(cfg.wiktIpaRe!.source, 'g');
    const results: string[] = [];
    let m;
    while ((m = re.exec(section)) !== null) {
      const ipa = m[1].replace(/^[/\[]|[/\]]$/g, '').trim();
      if (ipa && !results.includes(ipa)) results.push(ipa);
    }
    return results;
  }

  // First pass: look for a section matching the preferred language
  if (preferredLang) {
    for (const section of sections) {
      if (resolveSectionLang(section) === preferredLang) {
        const allIpas = extractAllIpas(section);
        return { wordLang: preferredLang, wiktIpa: allIpas[0] || null, allIpas };
      }
    }
  }

  // Fallback: use the first section that has a detectable language
  for (const section of sections) {
    const lang = resolveSectionLang(section);
    if (lang) {
      const allIpas = extractAllIpas(section);
      return { wordLang: lang, wiktIpa: allIpas[0] || null, allIpas };
    }
  }

  return { wordLang: null, wiktIpa: null, allIpas: [] };
}

/** Simple Levenshtein distance for IPA disambiguation. */
function ipaDistance(a: string, b: string): number {
  const al = a.length, bl = b.length;
  if (al === 0) return bl;
  if (bl === 0) return al;
  const d: number[][] = [];
  for (let i = 0; i <= al; i++) {
    d[i] = [i];
    for (let j = 1; j <= bl; j++) d[i][j] = i === 0 ? j : 0;
  }
  for (let i = 1; i <= al; i++)
    for (let j = 1; j <= bl; j++)
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
  return d[al][bl];
}

function pickBestIpa(allIpas: string[], espeakHint?: string): string {
  if (allIpas.length <= 1 || !espeakHint) return allIpas[0];
  let best = allIpas[0], bestDist = ipaDistance(espeakHint, allIpas[0]);
  for (let i = 1; i < allIpas.length; i++) {
    const d = ipaDistance(espeakHint, allIpas[i]);
    if (d < bestDist) { best = allIpas[i]; bestDist = d; }
  }
  return best;
}

/**
 * Pick the best audio file from a page's images list.
 */
function findBestAudio(
  images: { title: string }[],
  word: string,
  lang: Language
): string | null {
  const audioFiles: string[] = [];
  for (const img of images) {
    const colonIdx = img.title.indexOf(':');
    const name = colonIdx >= 0 ? img.title.slice(colonIdx + 1) : img.title;
    if (/\.(ogg|mp3|wav|oga|flac)$/i.test(name)) {
      audioFiles.push(name);
    }
  }
  if (audioFiles.length === 0) return null;

  const wordLower = word.toLowerCase();
  const langPrefix = lang.charAt(0).toUpperCase() + lang.slice(1).toLowerCase();
  audioFiles.sort((a, b) => {
    const aLower = a.toLowerCase();
    const bLower = b.toLowerCase();
    const aHasWord = aLower.includes(wordLower) ? 1 : 0;
    const bHasWord = bLower.includes(wordLower) ? 1 : 0;
    if (aHasWord !== bHasWord) return bHasWord - aHasWord;
    const aHasLang = (a.startsWith(langPrefix + '-') || a.startsWith(lang + '-')) ? 1 : 0;
    const bHasLang = (b.startsWith(langPrefix + '-') || b.startsWith(lang + '-')) ? 1 : 0;
    if (aHasLang !== bHasLang) return bHasLang - aHasLang;
    return a.length - b.length;
  });

  return audioFiles[0];
}

// ─── Message handlers ───────────────────────────────────────────────

export default defineBackground(() => {
  console.log('[Phonetix] Background service worker started');

  onMessage('phonemize', async ({ data }) => {
    const { words, voice, lang } = data;

    // Step 1: Check dictionary (if lang is known)
    let dictFound: Record<string, string> = {};
    let remaining = words;

    if (lang) {
      const dict = await loadDictionary(lang);
      if (dict.size > 0) {
        const result = lookupDictionary(words, dict);
        dictFound = result.found;
        remaining = result.notFound;
      }
    }

    // Step 2: Check IDB cache for remaining words
    const { cached, uncached } = await getCachedBatch(remaining, voice);

    // Step 3: Phonemize uncached words via espeak
    let espeakResults: Record<string, string> = {};
    if (uncached.length > 0) {
      try {
        espeakResults = await phonemizeViaOffscreen(uncached, voice);
        await setCachedBatch(espeakResults, voice);
      } catch (error) {
        console.error('[Phonetix] Phonemization error:', error);
        for (const w of uncached) espeakResults[w] = w;
      }
    }

    // Merge: dictionary > IDB cache > espeak
    return { ...espeakResults, ...cached, ...dictFound };
  });

  onMessage('detectLanguage', async ({ data: text }) => {
    return detectLang(text);
  });

  onMessage('detectLanguages', async ({ data }) => {
    if (!eldInitPromise) eldInitPromise = initEld();
    await eldInitPromise;

    return data.texts.map((text) => {
      if (text.length < 40) return null;
      const result = eldInstance.detect(text);
      if (result.language && result.language in Languages) {
        return result.language as Language;
      }
      return null;
    });
  });

  onMessage('checkWiktionary', async ({ data }) => {
    return lookupWiktionary(data.lang, data.word);
  });

  onMessage('speakWord', async ({ data }) => {
    await ensureOffscreen();
    chrome.runtime.sendMessage({
      target: 'offscreen',
      type: 'speak-word',
      data,
    });
  });
});
