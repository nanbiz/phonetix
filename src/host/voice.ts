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

/** Make sure the page the engine lives in exists. Chromium only. */
function offscreen(): Promise<void> {
  if (!ready) {
    ready = (async () => {
      const contexts = await chrome.runtime
        .getContexts?.({ contextTypes: ['OFFSCREEN_DOCUMENT' as chrome.runtime.ContextType] })
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
        if (!String(e).includes('Only a single offscreen')) throw e;
      }
    })();
  }
  return ready;
}

async function ask<T>(
  voice: 'ipa' | 'audio' | 'translate' | 'pairs',
  lang: string,
  words: string[],
  into?: string,
  base?: string,
): Promise<T> {
  await offscreen();
  const answered = (await chrome.runtime.sendMessage({ voice, lang, words, into, base })) as
    | { ok: T }
    | { failed: string }
    | undefined;
  if (!answered) throw new Error('the voice did not answer');
  if ('failed' in answered) throw new Error(answered.failed);
  return answered.ok;
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
