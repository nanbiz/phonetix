/**
 * Chrome offscreen document: bridges runtime messages to the espeak-ng engine.
 * Needed only on Chromium, whose MV3 background is a service worker with no
 * DOM/AudioContext. Firefox runs the same engine directly in its background page.
 */

import { initEspeak, phonemizeBatch, synthesizeWav } from '@/lib/espeak-engine';

chrome.runtime.onMessage.addListener((message: any, _sender, sendResponse: (response: any) => void) => {
  if (message.target !== 'offscreen') return false;

  if (message.type === 'phonemize-batch') {
    const { words, voice } = message.data as { words: string[]; voice: string };
    (async () => {
      try {
        sendResponse({ success: true, results: await phonemizeBatch(words, voice) });
      } catch (e: any) {
        sendResponse({ success: false, error: e?.message || String(e) });
      }
    })();
    return true;
  }

  // Synthesize to WAV bytes and hand them back; the content script plays them
  // through Web Audio, so playback is not subject to the page's media-src CSP.
  if (message.type === 'synthesize-wav') {
    const { word, voice } = message.data as { word: string; voice: string };
    (async () => {
      try {
        sendResponse(Array.from(await synthesizeWav(word, voice)));
      } catch (e: any) {
        console.error('[Phonetix] synthesize-wav failed:', e);
        sendResponse([]);
      }
    })();
    return true;
  }

  if (message.type === 'ping') {
    sendResponse({ ready: !!espeakReady });
    return true;
  }

  return false;
});

let espeakReady = false;
initEspeak()
  .then(() => { espeakReady = true; console.log('[Phonetix] espeak-ng initialized in offscreen document'); })
  .catch((e) => console.error('[Phonetix] Failed to init espeak-ng:', e));
