// The host: one core, the packs it has open, and the answers it gives.
//
// One instance and one place, because a pack is tens of megabytes and a copy per tab would
// be a copy per tab. Nothing here decides what a word means; that is the core's, compiled
// once and run on both platforms.
import {
  annotate, complete, curve, detect, lookUp, openLanguages, readRuns, readScreen,
  readWiktionary, symbolsOf, type Said,
} from '@/core';
import type { Batch } from '@/core/tokens';
import { onMessage } from './messages';
import { voiceOf } from '@/data/accents';
import { forget, get, held, offered, open } from './packs';
import { audio, ipa } from './voice';

/** Start answering. Called once, by the background entry point. */
export function host(): void {
  onMessage('openPack', async ({ data }) => {
    try {
      return await get(data.lang);
    } catch (e) {
      console.warn(`[Phonetix] No pack for ${data.lang}:`, e);
      return null;
    }
  });

  onMessage('languages', async () => openLanguages());

  onMessage('annotate', async ({ data }) => {
    // Both packs, because a translation is a join between them: with only the source open
    // every word would come back with its English gloss, which is the anchor rather than the
    // answer. A page in a language with no pack is still annotated, since the states say what
    // each word reached and a host with no tokens could not tell that from a blank page.
    await Promise.all([
      open(data.source).catch(() => null),
      data.target === data.source ? null : open(data.target).catch(() => null),
    ]);
    const batch = await annotate(data.runs, data.source, data.target, data.options);
    return said(batch, data.source);
  });

  onMessage('curve', async () => curve());

  onMessage('packs', async () => ({
    held: await held(),
    open: await openLanguages(),
    // What is on offer is asked for rather than remembered: a reader who changed where their
    // dictionaries come from means it from that moment.
    offered: await offered().catch((e) => {
      console.warn('[Phonetix] No list of packs:', e);
      return [];
    }),
  }));

  onMessage('getPack', async ({ data }) => {
    try {
      return await get(data.lang);
    } catch (e) {
      console.warn(`[Phonetix] Could not fetch the ${data.lang} pack:`, e);
      return null;
    }
  });

  onMessage('forgetPack', async ({ data }) => {
    await forget(data.lang);
    return true;
  });

  onMessage('diagram', async ({ data }) => {
    try {
      return await drawing(data.file, data.width);
    } catch (e) {
      console.warn(`[Phonetix] No diagram for ${data.file}:`, e);
      // Nothing rather than a broken picture: the sheet shows no diagram slot at all when
      // there is none, which reads better than a frame with a failure in it.
      return '';
    }
  });

  onMessage('symbols', async ({ data }) => symbolsOf(data.ipa));

  onMessage('detect', async ({ data }) => detect(data.text));

  onMessage('readScreen', async ({ data }) => readScreen(data.text));

  onMessage('readRuns', async ({ data }) => readRuns(data.texts));

  onMessage('enrich', async ({ data }) => {
    try {
      return await fromWiktionary(data.word, data.lang);
    } catch (e) {
      console.warn(`[Phonetix] Wiktionary said nothing about ${data.word}:`, e);
      return null;
    }
  });

  onMessage('fetch', async ({ data }) => {
    try {
      const res = await fetch(data.url);
      if (!res.ok) throw new Error(String(res.status));
      return Array.from(new Uint8Array(await res.arrayBuffer()));
    } catch (e) {
      console.warn(`[Phonetix] Nothing fetched from ${data.url}:`, e);
      return [];
    }
  });

  onMessage('speak', async ({ data }) => {
    try {
      return await audio(voiceOf(data.lang, data.accent ?? ''), data.word);
    } catch (e) {
      console.warn(`[Phonetix] Nothing said ${data.word}:`, e);
      return [];
    }
  });

  onMessage('lookUp', async ({ data }) => {
    // A missing pack is an answer the cascade gives; anything else is a fault, and it
    // travels back as one rather than as a card claiming the word does not exist.
    await Promise.all([
      open(data.source),
      data.target === data.source ? null : open(data.target),
    ]);
    return lookUp(data.word, data.source, data.target);
  });
}

/**
 * Fill in how the words no pack could say are said.
 *
 * The core asks for what it is missing rather than the host deciding to be helpful: a word a
 * pack answered keeps the pronunciation the dictionary recorded, and only the rest reach the
 * engine. What comes back goes through the core, so that it is marked as synthesised in the
 * one place that decides what a reader is told.
 */
async function said(batch: Batch, lang: string): Promise<Batch> {
  const wanted = batch.misses.filter((miss) => miss.need !== 'Gloss');
  if (wanted.length === 0) return batch;
  // By the language of the word rather than of the page. A page is not always in one: an
  // English line on a Spanish page read with the Spanish voice comes back saying "the" as
  // "te", which is a pronunciation of a word nobody was reading.
  const byLang = new Map<string, string[]>();
  for (const miss of wanted) {
    const token = batch.tokens[miss.token];
    if (!token) continue;
    const spoken = token.lang || lang;
    const words = byLang.get(spoken) ?? [];
    if (!words.includes(token.spelling)) words.push(token.spelling);
    byLang.set(spoken, words);
  }
  const spoken = new Map<string, Record<string, string>>();
  for (const [voice, words] of byLang) {
    try {
      spoken.set(voice, await ipa(voice, words));
    } catch (e) {
      console.warn(`[Phonetix] The ${voice} voice did not answer:`, e);
    }
  }
  const results = wanted
    .map((miss) => {
      const token = batch.tokens[miss.token];
      const said = token ? spoken.get(token.lang || lang)?.[token.spelling] : undefined;
      return { token: miss.token, ipa: said };
    })
    .filter((result): result is { token: number; ipa: string } => Boolean(result.ipa));
  if (results.length === 0) return batch;
  return complete(batch.batch, results, 'espeak');
}

/**
 * A sagittal section of the mouth making a sound, as a data URL.
 *
 * Commons serves these as SVG, so the file is asked for at a width and comes back as a
 * raster: a page cannot be handed an SVG it did not fetch itself, and the sheet wants one
 * size anyway. Fetched here because the page's own policy would refuse the load.
 */
async function drawing(file: string, width: number): Promise<string> {
  const url =
    `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(file)}` +
    `?width=${width}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(String(res.status));
  const bytes = new Uint8Array(await res.arrayBuffer());
  let binary = '';
  for (let at = 0; at < bytes.length; at += 8192) {
    binary += String.fromCharCode(...bytes.subarray(at, at + 8192));
  }
  const type = res.headers.get('content-type') ?? 'image/png';
  return `data:${type};base64,${btoa(binary)}`;
}

/** What has already been asked about, so a reader hovering a word twice asks once. */
const asked = new Map<string, Said | null>();

/**
 * What Wiktionary says about a word.
 *
 * A pack holds what the dump held, and the dump is a snapshot. The page carries two things
 * worth more than anything in it: a transcription a person wrote and a recording of a person
 * saying the word. The page is fetched here, because a page's own policy would refuse it, and
 * read by the core, because reading it is the same work on both platforms.
 */
async function fromWiktionary(word: string, lang: string): Promise<Said | null> {
  const key = `${lang}:${word.toLowerCase()}`;
  if (asked.has(key)) return asked.get(key) ?? null;
  const url =
    'https://en.wiktionary.org/w/index.php?action=raw&title=' + encodeURIComponent(word);
  const res = await fetch(url);
  // A word with no page is an ordinary answer, not a failure: most words have no recording.
  const said = res.ok ? await readWiktionary(await res.text(), lang) : null;
  asked.set(key, said);
  return said;
}
