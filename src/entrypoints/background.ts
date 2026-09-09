import { onMessage } from '@/lib/messaging';
import type { WiktionaryInfo } from '@/lib/messaging';
import {
  getCachedBatch, setCachedBatch, getCachedDict, setCachedDict, getCachedPack, setCachedPack,
} from '@/lib/cache';
import { lookUp, openLanguages, openPack } from '@/lib/core';
import { ofTranscription } from '@/lib/answer';
import { normalizeIpa } from '@/lib/ipa-normalize';
import { ACCENTS, voiceForAccent } from '@/lib/accents';
import { Languages, WiktionaryLanguages, LANG_NAME_TO_CODE } from '@/lib/types';
import type { Language, PhonemeResult } from '@/lib/types';
import { parseWikitext } from '@/lib/wiktionary-parse';

/** Each language's script, derived from its own dictionary's keys on load. */
const dictScript = new Map<string, string>();
import { parseClassifier, disambiguate, type HomographEntry } from '@/lib/homograph';
import { applyRegion } from '@/lib/regions';
import { dominantScript, isLetterSpelling, espeakAllowed } from '@/lib/segment';
import { pickLanguage } from '@/lib/langdetect';
import { scoreTexts } from '@/lib/eld-engine';

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
    // The offscreen document can be created but never answer (init failure, killed
    // mid-flight); without a timeout the caller's Promise.all hangs and the content
    // script never reaches observeDOM(), leaving the page half-transcribed. Time out.
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error('Phonemization timed out'));
    }, 15000);
    chrome.runtime.sendMessage(
      {
        target: 'offscreen',
        type: 'phonemize-batch',
        data: { words, voice },
      },
      (response: any) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
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

// ─── espeak host (browser-specific) ─────────────────────────────────
// Chrome MV3 background is a service worker (no DOM/AudioContext), so espeak
// runs in an offscreen document. Firefox MV3 background is an event page with
// DOM, so it runs the engine directly. The dead branch is tree-shaken per build.

const IS_FIREFOX = import.meta.env.BROWSER === 'firefox';

async function phonemizeHost(words: string[], voice: string): Promise<Record<string, string>> {
  if (IS_FIREFOX) {
    const { phonemizeBatch } = await import('@/lib/espeak-engine');
    return phonemizeBatch(words, voice);
  }
  return phonemizeViaOffscreen(words, voice);
}

// ─── Dictionary loading ─────────────────────────────────────────────

const dictCache = new Map<string, Map<string, string>>();
const dictLoading = new Map<string, Promise<Map<string, string>>>();

async function decompressGz(res: Response): Promise<Record<string, string>> {
  if (res.status === 404) return {};   // this language simply has no dictionary
  if (!res.ok) throw new Error(`fetch ${res.status}`);
  const stream = res.body!.pipeThrough(new DecompressionStream('gzip'));
  return JSON.parse(await new Response(stream).text());
}

/** A language's dictionary object. Prefers a downloaded pack (cached in IndexedDB
 *  after the first fetch) from the user-configured host, and otherwise the copy
 *  bundled with the extension. The host lives only in runtime storage. */
async function fetchDictObject(lang: string): Promise<Record<string, string>> {
  let base: string | undefined;
  try {
    base = (await storage.getItem<string>('local:packBaseUrl'))?.replace(/\/+$/, '');
  } catch {
    base = undefined;   // storage unavailable is not a reason to fail the dictionary
  }
  if (base) {
    const cached = await getCachedDict(lang);
    if (cached) return cached;
    try {
      const obj = await decompressGz(await fetch(`${base}/dictionaries/${lang}.json.gz`));
      await setCachedDict(lang, obj);
      return obj;
    } catch (e) {
      console.warn(`[Phonetix] Pack fetch failed for ${lang}, using bundled:`, e);
    }
  }
  return decompressGz(await fetch(chrome.runtime.getURL(`dictionaries/${lang}.json.gz`)));
}

/** Accent overlays, keyed "<lang>.<accentId>". */
const overlayCache = new Map<string, Map<string, string>>();
const overlayLoading = new Map<string, Promise<Map<string, string>>>();

/**
 * The words an accent pronounces differently from the language's standard.
 * Empty for the standard accent, and for any accent with no data behind it: the
 * page then shows the base dictionary, which is the standard pronunciation.
 */
async function loadOverlay(lang: string, accentId: string): Promise<Map<string, string>> {
  if (!accentId || accentId === lang) return new Map();
  if (!ACCENTS[lang]?.some(a => a.id === accentId)) return new Map();

  const key = `${lang}.${accentId}`;
  if (overlayCache.has(key)) return overlayCache.get(key)!;
  if (overlayLoading.has(key)) return overlayLoading.get(key)!;

  const promise = (async () => {
    try {
      const obj = await decompressGz(await fetch(chrome.runtime.getURL(`dictionaries/accents/${key}.json.gz`)));
      const map = new Map<string, string>();
      for (const [word, raw] of Object.entries(obj)) {
        const ipa = normalizeIpa(word, raw as string);
        if (ipa) map.set(word, ipa);
      }
      overlayCache.set(key, map);
      console.log(`[Phonetix] Loaded ${key} accent: ${map.size.toLocaleString()} words`);
      return map;
    } catch (e) {
      console.warn(`[Phonetix] No accent overlay for ${key}:`, e);
      return new Map<string, string>();
    } finally {
      overlayLoading.delete(key);
    }
  })();

  overlayLoading.set(key, promise);
  return promise;
}

async function loadDictionary(lang: string): Promise<Map<string, string>> {
  if (dictCache.has(lang)) return dictCache.get(lang)!;
  if (dictLoading.has(lang)) return dictLoading.get(lang)!;

  const promise = (async () => {
    try {
      const obj = await fetchDictObject(lang);
      // Entries can list variants or mark optional sounds; the page shows one
      // pronunciation, so each value is reduced to one as it is loaded.
      const map = new Map<string, string>();
      for (const [word, raw] of Object.entries(obj)) {
        const ipa = normalizeIpa(word, raw);
        if (ipa) map.set(word, ipa);
      }
      dictCache.set(lang, map);
      dictScript.set(lang, dominantScript([...map.keys()]));  // the language's script, from its own data
      console.log(`[Phonetix] Loaded ${lang} dictionary: ${map.size.toLocaleString()} entries (${dictScript.get(lang)})`);
      return map;
    } catch (e) {
      // Do not cache the failure: a transient fetch error would otherwise leave
      // the language permanently empty for the rest of the session. A language
      // with genuinely no dictionary returns {} above and is cached as empty.
      console.error(`[Phonetix] Failed to load dictionary for ${lang}:`, e);
      return new Map<string, string>();
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

// ─── Homograph classifiers ──────────────────────────────────────────

const homographCache = new Map<string, Map<string, HomographEntry>>();
const homographLoading = new Map<string, Promise<Map<string, HomographEntry>>>();

async function loadHomographs(lang: string): Promise<Map<string, HomographEntry>> {
  if (homographCache.has(lang)) return homographCache.get(lang)!;
  if (homographLoading.has(lang)) return homographLoading.get(lang)!;

  const promise = (async () => {
    try {
      const url = chrome.runtime.getURL(`homographs/${lang}.json.gz`);
      const res = await fetch(url);
      if (!res.ok) {
        const empty = new Map<string, HomographEntry>();
        homographCache.set(lang, empty);
        return empty;
      }
      const ds = new DecompressionStream('gzip');
      const text = await new Response(res.body!.pipeThrough(ds)).text();
      const map = parseClassifier(JSON.parse(text));
      homographCache.set(lang, map);
      console.log(`[Phonetix] Loaded ${lang} homographs: ${map.size} entries`);
      return map;
    } catch (e) {
      console.warn(`[Phonetix] Failed to load homographs for ${lang}:`, e);
      const empty = new Map<string, HomographEntry>();
      homographCache.set(lang, empty);
      return empty;
    } finally {
      homographLoading.delete(lang);
    }
  })();

  homographLoading.set(lang, promise);
  return promise;
}

// ─── Language detection via eld ──────────────────────────────────────
// eld runs in-process (its ngram model is injected statically, see eld-engine).

function detectLang(text: string): Language {
  const [s] = scoreTexts([text]);
  return (s?.ranked[0] as Language) || 'en';
}

async function detectBlocks(
  pageLang: string,
  blocks: { text: string; words: string[] }[],
): Promise<string[]> {
  const scores = scoreTexts(blocks.map((b) => b.text));
  const analyzed = blocks.map((b, i) => ({
    words: b.words,
    ranked: scores[i]?.ranked ?? [],
    reliable: scores[i]?.reliable ?? false,
  }));

  // Page-wide candidate pool: any language eld proposed for any block is a
  // candidate for every block, so a short English title becomes English once
  // another block on the page looked English to eld. Bounded to the most-proposed
  // languages so a genuinely multilingual page doesn't load dozens of dictionaries.
  const freq = new Map<string, number>();
  for (const a of analyzed) for (const c of a.ranked) freq.set(c, (freq.get(c) ?? 0) + 1);
  const top = [...freq.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12).map(([l]) => l);
  const pool = new Set<string>([pageLang, ...top]);
  const candidates = [...pool];

  const dicts = new Map<string, Map<string, string>>();
  for (const c of pool) dicts.set(c, await loadDictionary(c));

  const coverageOf = (lang: string, words: string[]): number => {
    const d = dicts.get(lang);
    if (!d || d.size === 0 || words.length === 0) return 0;
    let hit = 0;
    for (const w of words) if (d.has(w) || d.has(w.toLowerCase())) hit++;
    return hit / words.length;
  };

  return analyzed.map((a) =>
    pickLanguage(pageLang, candidates, a.reliable, a.ranked[0], (l) => coverageOf(l, a.words)),
  );
}

// ─── Wiktionary lookup ──────────────────────────────────────────────

const wiktCache = new Map<string, WiktionaryInfo>();


async function lookupWiktionary(detectedLang: Language, word: string): Promise<WiktionaryInfo> {
  const cacheKey = `${detectedLang}:${word.toLowerCase()}`;
  if (wiktCache.has(cacheKey)) return wiktCache.get(cacheKey)!;

  const miss: WiktionaryInfo = { exists: false, audioUrl: null, matchedTitle: null, foundLang: null, wordLang: null, wiktIpa: null };

  // A miss reached only because a fetch errored (offline, a Wikimedia blip) must not be
  // cached, or one bad moment permanently disables the link/recording for that word.
  const err = { hit: false };
  const cacheMiss = (r: WiktionaryInfo) => { if (!err.hit) wiktCache.set(cacheKey, r); return r; };

  // Only try Wiktionary editions that have parsing support
  const wiktLangs = WiktionaryLanguages;

  // Phase 1: Try detected language's Wiktionary (if it has parsing support)
  if (wiktLangs.includes(detectedLang)) {
    const primary = await tryWiktionaryLang(detectedLang, word, detectedLang, err);
    if (primary?.wordLang === detectedLang) {
      wiktCache.set(cacheKey, primary);
      return primary;
    }

    // Phase 2: Try other Wiktionaries with parsing support
    const others = wiktLangs.filter(l => l !== detectedLang);
    const results = await Promise.all(others.map(l => tryWiktionaryLang(l, word, detectedLang, err)));

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
    // A real entry (the word exists) is always worth caching; only a bare miss is
    // withheld when a fetch errored.
    if (best) { wiktCache.set(cacheKey, best); return best; }
    return cacheMiss(miss);
  }

  // For languages without Wiktionary parsing: try English Wiktionary only
  if (wiktLangs.includes('en')) {
    const result = await tryWiktionaryLang('en', word, detectedLang, err);
    if (result) {
      wiktCache.set(cacheKey, result);
      return result;
    }
  }

  return cacheMiss(miss);
}

/**
 * Try a single language's Wiktionary for a word.
 * Tries multiple capitalizations; prefers entries where wordLang matches
 * expectedWordLang (e.g. skip Norwegian "Time" when we want English "time").
 */
async function tryWiktionaryLang(
  lang: Language,
  word: string,
  expectedWordLang?: Language,
  errFlag?: { hit: boolean }
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
      // The recording must be in the word's language, not this Wiktionary edition's:
      // the English edition of "casa" lists an English recording, but for the Italian
      // word only an Italian one may play.
      const audioFilename = findBestAudio(images, candidate, expectedWordLang || lang);
      const audioUrl = audioFilename
        ? `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(audioFilename)}`
        : null;

      // Parse full wikitext — prefer section matching expectedWordLang
      const wikitext: string = pages[pageId]?.revisions?.[0]?.['*'] || '';
      const { wordLang, wiktIpa } = parseWikitext(wikitext, Languages[lang], LANG_NAME_TO_CODE, expectedWordLang);

      const result: WiktionaryInfo = { exists: true, audioUrl, matchedTitle, foundLang: lang, wordLang, wiktIpa };

      // If wordLang matches what we want, return immediately
      if (!expectedWordLang || wordLang === expectedWordLang) return result;

      // Otherwise save as fallback and try next capitalization
      if (!fallback || (result.audioUrl && !fallback.audioUrl)) fallback = result;
    } catch {
      // A network/CORS error is not the same as "the word is not on Wiktionary": let
      // the caller know so it does not cache the empty result as a permanent miss.
      if (errFlag) errFlag.hit = true;
      continue;
    }
  }

  return fallback;
}


/** ISO 639-1 (our language codes) to ISO 639-3, which Lingua Libre audio uses. */
const ISO1_TO_3: Record<string, string> = {
  af: 'afr', ar: 'ara', bg: 'bul', bn: 'ben', bs: 'bos', ca: 'cat', cs: 'ces',
  cy: 'cym', da: 'dan', de: 'deu', el: 'ell', en: 'eng', eo: 'epo', es: 'spa',
  et: 'est', eu: 'eus', fa: 'fas', fi: 'fin', fr: 'fra', ga: 'gle', hi: 'hin',
  hr: 'hrv', hu: 'hun', hy: 'hye', id: 'ind', is: 'isl', it: 'ita', ja: 'jpn',
  ka: 'kat', kk: 'kaz', ko: 'kor', ku: 'kur', la: 'lat', lt: 'lit', lv: 'lav',
  mk: 'mkd', ml: 'mal', ms: 'msa', my: 'mya', nl: 'nld', no: 'nor', pl: 'pol',
  pt: 'por', ro: 'ron', ru: 'rus', sk: 'slk', sl: 'slv', sq: 'sqi', sr: 'srp',
  sv: 'swe', sw: 'swa', ta: 'tam', te: 'tel', th: 'tha', tr: 'tur', uk: 'ukr',
  ur: 'urd', uz: 'uzb', vi: 'vie', zh: 'zho',
};

/**
 * The language a recording is in, read from its filename, or null when unknown.
 *
 * Two conventions carry it: Lingua Libre names a file "LL-Q150 (fra)-Speaker-word.wav"
 * with an ISO 639-3 code in parentheses, and the older files start with the language
 * like "En-us-cat.ogg" or "De-Katze.ogg". A word spelled the same in two languages
 * ("animations" in English and French) has a recording under each, so the language
 * has to be checked or the wrong one plays.
 */
function audioLanguage(name: string): { lang: string; sure: boolean } | null {
  const ll = name.match(/\bLL-Q\d+ \(([a-z]{3})\)/i);
  if (ll) return { lang: ll[1].toLowerCase(), sure: true };   // ISO 639-3, unambiguous
  // The leading token of an old-convention file (En-us-cat.ogg) is usually a language,
  // but an ordinary capitalised first word collides with real ISO codes (Cat-… → "cat",
  // Catalan), so this reading is not certain.
  const old = name.match(/^([a-z]{2,3})(?:-[a-z]{2,})?[-_]/i);
  if (old) return { lang: old[1].toLowerCase(), sure: false };
  return null;
}

/**
 * The best audio recording for the word in this language, or null.
 *
 * A recording whose language is known and does not match is rejected: a French
 * recording of "animations" must never play for the English word. A recording whose
 * convention we cannot read is kept as a last resort, since dropping it would lose
 * legitimate audio for languages neither convention covers.
 */
function findBestAudio(images: { title: string }[], word: string, lang: Language): string | null {
  const iso3 = ISO1_TO_3[lang];
  const candidates: { name: string; matches: boolean }[] = [];

  for (const img of images) {
    const colonIdx = img.title.indexOf(':');
    const name = colonIdx >= 0 ? img.title.slice(colonIdx + 1) : img.title;
    if (!/\.(ogg|mp3|wav|oga|flac)$/i.test(name)) continue;

    const fl = audioLanguage(name);
    if (fl === null) {
      candidates.push({ name, matches: false });          // unknown convention, last resort
    } else if (fl.lang === lang || fl.lang === iso3) {
      candidates.push({ name, matches: true });
    } else if (fl.sure) {
      continue;                                           // certainly a different language: drop
    } else {
      // An uncertain reading that does not match: keep as a last resort rather than
      // discard a legitimate recording whose name merely collides with an ISO code.
      candidates.push({ name, matches: false });
    }
  }

  if (candidates.length === 0) return null;

  const wordLower = word.toLowerCase();
  candidates.sort((a, b) => {
    if (a.matches !== b.matches) return a.matches ? -1 : 1;   // language match first
    const aWord = a.name.toLowerCase().includes(wordLower) ? 1 : 0;
    const bWord = b.name.toLowerCase().includes(wordLower) ? 1 : 0;
    if (aWord !== bWord) return bWord - aWord;
    return a.name.length - b.name.length;
  });

  // Nothing whose language matches, only unknown-convention files: keep the best of
  // those. They are ambiguous, but better than silence and cannot be proven wrong.
  return candidates[0].name;
}

// ─── Message handlers ───────────────────────────────────────────────

export default defineBackground(() => {
  console.log('[Phonetix] Background service worker started');

  onMessage('phonemize', async ({ data }) => {
    // `voice` carries the chosen accent. Which words it changes comes from the
    // dictionary overlay; which voice espeak speaks in comes from voiceForAccent,
    // because espeak has no voice for every accent and invents one when asked.
    const { words, voice: accent, lang, fallbacks = [] } = data;
    const result: PhonemeResult = {};
    const srcLang = lang || accent.split('-')[0];
    const voice = voiceForAccent(srcLang, accent);

    // Tier 1 — block-language dictionary, with the chosen accent laid over it.
    let remaining = words;
    if (lang) {
      const [dict, overlay] = await Promise.all([loadDictionary(lang), loadOverlay(lang, accent)]);
      if (dict.size > 0) {
        const { found, notFound } = lookupDictionary(remaining, dict);
        for (const w in found) {
          // An overlay entry is the accent's real, tagged pronunciation, so it is
          // used as it stands. The rule transforms are an approximation for the
          // words the overlay does not cover, so they apply only to the base.
          const accented = overlay.get(w) ?? overlay.get(w.toLowerCase());
          const ipa = accented ?? applyRegion(found[w], accent, w);
          result[w] = { ipa, lang, src: 'dict' };
        }
        remaining = notFound;
      }
    }

    // Tier 2 — cross-language dictionary fallback. A loanword or proper noun
    // (Renault, Mount Everest, Javier) misses the block's dictionary but lives in
    // another language present on the page. Trying those before espeak stops the
    // wrong-language butchering. The candidates are the page's own languages
    // (passed by the content script), not a fixed list.
    for (const fb of fallbacks) {
      if (remaining.length === 0) break;
      if (fb === lang) continue;
      const dict = await loadDictionary(fb);
      if (dict.size === 0) continue;
      const { found, notFound } = lookupDictionary(remaining, dict);
      for (const w in found) result[w] = { ipa: found[w], lang: fb, src: 'dict' };
      remaining = notFound;
    }

    // Tiers 3–4 (espeak) only for words whose script matches the block language's
    // script (derived from its dictionary). A foreign-script word (a Cyrillic name
    // in a Japanese page, say) would just make espeak spell out letter names, so it
    // stays untranslated and the page shows the original.
    const expectScript = dictScript.get(srcLang) ?? '';
    const espeakable = remaining.filter((w) => espeakAllowed(w, expectScript));

    // Tier 3 — IDB cache of prior espeak output for the block voice.
    const { cached, uncached } = await getCachedBatch(espeakable, voice);
    for (const w in cached) {
      if (isLetterSpelling(cached[w])) continue;
      result[w] = { ipa: applyRegion(cached[w], accent, w), lang: srcLang, src: 'espeak' };
    }

    // Tier 4 — espeak grapheme-to-phoneme, the last-resort coverage net.
    if (uncached.length > 0) {
      let espeakResults: Record<string, string> = {};
      try {
        espeakResults = await phonemizeHost(uncached, voice);
        // Drop letter-name garbage before caching so it never resurfaces.
        for (const w of Object.keys(espeakResults)) {
          if (isLetterSpelling(espeakResults[w])) delete espeakResults[w];
        }
        await setCachedBatch(espeakResults, voice);
      } catch (error) {
        console.error('[Phonetix] Phonemization error:', error);
        for (const w of uncached) espeakResults[w] = w;
      }
      for (const w in espeakResults) result[w] = { ipa: applyRegion(espeakResults[w], accent, w), lang: srcLang, src: 'espeak' };
    }

    return result;
  });

  onMessage('getHomographWords', async ({ data }) => {
    const clf = await loadHomographs(data.lang);
    const words: string[] = [];
    for (const [word, entry] of clf) if (entry.classes.length > 1) words.push(word);
    return words;
  });

  onMessage('disambiguate', async ({ data }) => {
    const clf = await loadHomographs(data.lang);
    if (clf.size === 0) return data.items.map(() => null);
    return data.items.map((it) => {
      const entry = clf.get(it.word);
      if (!entry || entry.classes.length <= 1) return null;
      return applyRegion(disambiguate(entry, it.tokens, it.index), data.voice, it.word);
    });
  });

  onMessage('detectLanguage', async ({ data: text }) => {
    return detectLang(text);
  });

  onMessage('detectBlocks', async ({ data }) => {
    return detectBlocks(data.pageLang, data.blocks);
  });

  onMessage('getHealth', async () => {
    const errors: string[] = [];
    let eld = false, dict = false, espeak = false;
    try {
      eld = scoreTexts(['This is plainly an English sentence for detection.'])[0]?.ranked[0] === 'en';
      if (!eld) errors.push('eld: wrong or empty detection');
    } catch (e) { errors.push(`eld: ${e}`); }
    // The probe can land while the worker is cold and loading several
    // dictionaries at once, so give a failed load a second chance before
    // calling the subsystem broken.
    for (let attempt = 0; attempt < 3 && !dict; attempt++) {
      if (attempt) await new Promise((r) => setTimeout(r, 1500));
      try {
        dict = (await loadDictionary('en')).size > 1000;
      } catch (e) {
        if (attempt === 2) errors.push(`dict: ${e}`);
      }
    }
    if (!dict && errors.every((e) => !e.startsWith('dict:'))) errors.push('dict: en dictionary empty');
    try {
      const r = await phonemizeHost(['hello'], 'en');
      espeak = typeof r['hello'] === 'string' && r['hello'].length > 0 && r['hello'] !== 'hello';
      if (!espeak) errors.push('espeak: no phonemes produced');
    } catch (e) { errors.push(`espeak: ${e}`); }
    return { eld, dict, espeak, errors };
  });

  onMessage('checkWiktionary', async ({ data }) => {
    return lookupWiktionary(data.lang, data.word);
  });

  // Chrome: synthesize + play inside the offscreen document.
  // The sagittal diagrams are fetched here and handed to the page as data URLs:
  // a strict page CSP blocks an image from Wikimedia, and would leave the tooltip
  // with a broken picture on exactly the sites that set one.
  const diagrams = new Map<string, string>();
  onMessage('symbolDiagram', async ({ data }) => {
    const cached = diagrams.get(data.file);
    if (cached !== undefined) return cached;

    try {
      const url = `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(data.file)}?width=480`;
      const res = await fetch(url);
      if (!res.ok) throw new Error(String(res.status));
      // Encoded by hand rather than with FileReader, which a Chrome service worker
      // does not have.
      const type = res.headers.get('content-type') || 'image/svg+xml';
      const bytes = new Uint8Array(await res.arrayBuffer());
      let binary = '';
      for (let i = 0; i < bytes.length; i += 8192) {
        binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
      }
      const dataUrl = `data:${type};base64,${btoa(binary)}`;
      diagrams.set(data.file, dataUrl);
      return dataUrl;
    } catch (e) {
      // Do not cache the empty result: a transient fetch error would otherwise leave
      // the diagram permanently blank for this symbol even after the network recovers.
      console.warn(`[Phonetix] No diagram for ${data.file}:`, e);
      return '';
    }
  });

  // A recording, fetched here and handed to the page as bytes: the content script
  // plays it through Web Audio, which a page's media-src CSP cannot block the way it
  // blocks an <audio> element loading the same URL.
  onMessage('fetchAudio', async ({ data }) => {
    try {
      const res = await fetch(data.url);
      if (!res.ok) throw new Error(String(res.status));
      return Array.from(new Uint8Array(await res.arrayBuffer()));
    } catch (e) {
      console.warn(`[Phonetix] Audio fetch failed for ${data.url}:`, e);
      return [];
    }
  });

  // espeak returns WAV bytes, which the content script plays through Web Audio. The
  // engine needs a DOM and AudioContext, which a Chrome service worker lacks, so
  // there it runs in the offscreen document; on Firefox the background has both.
  onMessage('synthesizeAudio', async ({ data }) => {
    const voice = voiceForAccent(data.voice.split('-')[0], data.voice);
    if (IS_FIREFOX) {
      const { synthesizeWav } = await import('@/lib/espeak-engine');
      return Array.from(await synthesizeWav(data.word, voice));
    }
    await ensureOffscreen();
    const bytes: number[] = await chrome.runtime.sendMessage({
      target: 'offscreen',
      type: 'synthesize-wav',
      data: { word: data.word, voice },
    });
    return bytes ?? [];
  });

  // ─── the core ───────────────────────────────────────────────────────
  // One core, here, because a pack is tens of megabytes and a copy per tab would be a copy
  // per tab. Everything a page shows about a word comes through these three.

  onMessage('openLexPack', async ({ data }) => {
    try {
      return await openLexPack(data.lang);
    } catch (e) {
      console.warn(`[Phonetix] No pack for ${data.lang}:`, e);
      return null;
    }
  });

  onMessage('lexLanguages', async () => {
    try {
      return await openLanguages();
    } catch (e) {
      console.warn('[Phonetix] The core did not start:', e);
      return [];
    }
  });

  onMessage('lookUpWord', async ({ data }) => {
    try {
      return await lookUp(data.word, data.source, data.target);
    } catch (e) {
      console.warn(`[Phonetix] Lookup failed for ${data.word}:`, e);
      // A cascade that could not run is not a cascade that found nothing: the state says
      // which, so the card can offer the pack rather than claim the word does not exist.
      return { ...ofTranscription(data.word, '', data.source), state: 'NoPack' as const };
    }
  });
});

/**
 * A language's dictionary pack, opened in the core.
 *
 * Downloaded once from the host the reader configured and then kept, because the reader who
 * needs a dictionary offline is the reader on a train. The pack says which language it is
 * for, and that is what comes back: a file named de.pack holding Spanish would otherwise
 * answer Spanish words to a reader who asked for German.
 */
async function openLexPack(lang: string): Promise<string | null> {
  if ((await openLanguages()).includes(lang)) return lang;

  const cached = await getCachedPack(lang);
  if (cached) return openPack(cached);

  let base: string | undefined;
  try {
    base = (await storage.getItem<string>('local:packBaseUrl'))?.replace(/\/+$/, '');
  } catch {
    base = undefined;   // storage unavailable is not a reason to fail the lookup
  }
  if (!base) return null;

  const res = await fetch(`${base}/packs/${lang}.pack`);
  if (!res.ok) throw new Error(`${res.status} fetching the ${lang} pack`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  // Opened before it is kept: bytes that are not a pack throw here, and a file that cannot
  // be read is worse than no file once it is in the cache.
  const opened = await openPack(bytes);
  await setCachedPack(opened, bytes);
  return opened;
}
