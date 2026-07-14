import { defineExtensionMessaging } from '@webext-core/messaging';
import type { Language, PhonemeResult } from './types';

export interface WiktionaryInfo {
  exists: boolean;
  /** Direct Wikimedia Commons audio URL, null = no audio found */
  audioUrl: string | null;
  /** The actual page title that matched (for correct link URL) */
  matchedTitle: string | null;
  /** The language Wiktionary this was found on */
  foundLang: string | null;
  /** Actual language of the word (parsed from page content), e.g. "tr" for Turkish on de.wiktionary */
  wordLang: string | null;
  /** Human-curated IPA from Wiktionary (replaces espeak IPA in tooltip) */
  wiktIpa: string | null;
}

interface ProtocolMap {
  phonemize(data: { words: string[]; voice: string; lang?: string; fallbacks?: string[] }): PhonemeResult;
  detectLanguage(text: string): Language;
  /** Decide each block's language from eld's ranked guesses plus which language's
   *  dictionary best covers its words. Handles short mixed-language elements (an
   *  English video title on a German page) that whole-page detection gets wrong. */
  detectBlocks(data: { pageLang: string; blocks: { text: string; words: string[] }[] }): string[];
  checkWiktionary(data: { lang: string; word: string }): WiktionaryInfo;
  /** Homograph words (>1 pronunciation class) for a language, for context-aware override. */
  getHomographWords(data: { lang: string }): string[];
  /** Resolve homograph occurrences to context-appropriate IPA (null = not a homograph). */
  disambiguate(data: { lang: string; voice: string; items: { word: string; tokens: string[]; index: number }[] }): (string | null)[];
  /** Actively probe each subsystem (language detection, dictionary, espeak) so a
   *  test can assert none silently degraded. */
  getHealth(data: Record<string, never>): { eld: boolean; dict: boolean; espeak: boolean; errors: string[] };
  /** A Commons file showing the sound being made, as a data URL. The page's own
   *  CSP would block loading it straight into the page. */
  symbolDiagram(data: { file: string }): string;
  /** Fetch an audio file (Commons recording) as bytes, so the content script can
   *  play it through Web Audio without the page's media-src CSP blocking a load. */
  fetchAudio(data: { url: string }): number[];
  /** Synthesize a word to WAV bytes, played in the content script through Web Audio. */
  synthesizeAudio(data: { word: string; voice: string }): number[];
}

export const { sendMessage, onMessage } = defineExtensionMessaging<ProtocolMap>();

export async function getCurrentTabId(): Promise<number> {
  return new Promise((resolve, reject) => {
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs.length > 0 && tabs[0].id) {
        resolve(tabs[0].id);
      } else {
        reject(new Error('No active tab'));
      }
    });
  });
}
