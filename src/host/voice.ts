// How a word is said when no dictionary says it.
//
// The core answers what a word means and, where a pack holds one, how it is said. Everything
// else is synthesised: this is the port the core's misses are filled from, and the voice the
// play button on the card plays.
//
// Where the engine runs is the one thing the two browsers disagree about. A Chromium service
// worker has no document and cannot import a module from a URL, so the engine lives in an
// offscreen page and is asked through a message; a Firefox background page has a document, so
// the same engine is simply called. Neither difference reaches anything above this file.

import { host } from './packs';

const CHROMIUM_OFFSCREEN = 'offscreen.html';

const IS_FIREFOX = import.meta.env.BROWSER === 'firefox';

let ready: Promise<void> | null = null;

/**
 * Make sure the page the engine lives in exists. Chromium only.
 *
 * The attempt is remembered so that a page of misses asks for one document rather than one
 * each, and a failed attempt is forgotten again: held, it would be the answer to every later
 * ask, and one bad moment while the worker was starting would leave the voice dead for as
 * long as that worker lived - every word no dictionary holds silently unanswered.
 */
function offscreen(): Promise<void> {
  if (!ready) {
    ready = (async () => {
      const contexts = await chrome.runtime
        .getContexts?.({ contextTypes: ['OFFSCREEN_DOCUMENT'] })
        .catch(() => undefined);
      if (contexts && contexts.length > 0) return;
      try {
        await chrome.offscreen.createDocument({
          url: CHROMIUM_OFFSCREEN,
          reasons: [chrome.offscreen.Reason.AUDIO_PLAYBACK],
          justification: 'Synthesise the pronunciation of a word',
        });
      } catch (e) {
        // Two callers can ask at once and only one document may exist; the loser is not a
        // failure, it is the document already being there.
        if (!String(e).includes('Only a single offscreen')) {
          console.warn('[Phonetix] The voice has nowhere to run:', e);
          throw e;
        }
      }
    })().catch((e) => {
      ready = null;
      throw e;
    });
  }
  return ready;
}

/**
 * Put one question to the engine, and put it again if the page it lives in has gone.
 *
 * An offscreen page can die - it is a document, and a document can be closed or run out of
 * memory - and nothing tells the worker when it does. Asked once, the whole product quietly
 * stops filling in how a word is said for as long as that worker lives: every word no
 * dictionary holds reads as a word nobody wrote an entry for. So a question that reaches
 * nobody is asked again, once, against a document made fresh.
 */
async function ask<T>(
  voice: 'ipa' | 'audio' | 'translate' | 'pairs',
  lang: string,
  words: string[],
  into?: string,
  base?: string,
): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    await offscreen();
    let answered: { ok: T } | { failed: string } | undefined;
    let reached = true;
    try {
      answered = (await chrome.runtime.sendMessage({ voice, lang, words, into, base }));
    } catch (e) {
      // What a browser says when nothing is listening: the page is gone, rather than the
      // engine having failed at something. Anything else is the engine's own answer.
      if (!String(e).includes('Receiving end does not exist')) throw e;
      reached = false;
    }
    if (answered && 'failed' in answered) throw new Error(answered.failed);
    if (answered) return answered.ok;
    if (attempt > 0) throw new Error('the voice did not answer');
    // Nobody is there. The browser can still hold a record of a page that has died, and
    // making another is refused while that record stands, so it is closed before asking for
    // one - and only then is the question put again.
    void reached;
    ready = null;
    await chrome.offscreen?.closeDocument?.().catch(() => undefined);
  }
}

/**
 * How these words are said, as the engine reads them.
 *
 * A transcription a machine produced, which is why what it fills is marked as synthesised
 * rather than passed off as a dictionary's.
 */
export async function ipa(lang: string, words: string[]): Promise<Record<string, string>> {
  if (words.length === 0) return {};
  if (IS_FIREFOX) {
    const { phonemizeBatch } = await import('@/engines/espeak');
    return phonemizeBatch(words, lang);
  }
  return ask<Record<string, string>>('ipa', lang, words);
}

/**
 * What these words mean, as the engine guesses them.
 *
 * A machine's answer, which is why what it fills is marked as a guess rather than passed off
 * as a dictionary's. The cascade has already answered everything a dictionary could.
 */
export async function guessed(from: string, to: string, words: string[]): Promise<string[]> {
  if (words.length === 0 || !from || !to || from === to) return [];
  // Read here, where the setting can be read at all, and carried to the engine.
  const base = (await host()) ?? '';
  if (!base) return [];
  if (IS_FIREFOX) {
    const { translate } = await import('@/engines/bergamot');
    return translate(base, from, to, words);
  }
  return ask<string[]>('translate', from, words, to, base);
}

/** Which pairs the engine can translate at all, so nothing offers what it cannot do. */
export async function translatable(): Promise<{ from: string; to: string }[]> {
  const base = (await host()) ?? '';
  if (!base) return [];
  if (IS_FIREFOX) {
    const { pairs } = await import('@/engines/bergamot');
    return pairs(base);
  }
  return ask<{ from: string; to: string }[]>('pairs', '', [], undefined, base)
    .catch(() => []);
}

/**
 * One word spoken, as WAV bytes.
 *
 * Bytes rather than a sound played here, because the page plays it through Web Audio: a
 * page's own media policy can block an audio element loading a URL and cannot block that.
 */
export async function audio(lang: string, word: string): Promise<number[]> {
  if (IS_FIREFOX) {
    const { synthesizeWav } = await import('@/engines/espeak');
    return Array.from(await synthesizeWav(word, lang));
  }
  return ask<number[]>('audio', lang, [word]);
}
