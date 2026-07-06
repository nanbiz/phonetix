import { defineExtensionMessaging } from '@webext-core/messaging';
import type { Language, LanguageOption, Mode, PhonemeResult } from './types';

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
  phonemize(data: { words: string[]; voice: string; lang?: string }): PhonemeResult;
  detectLanguage(text: string): Language;
  detectLanguages(data: { texts: string[] }): (Language | null)[];
  checkWiktionary(data: { lang: string; word: string }): WiktionaryInfo;
  /** Homograph words (>1 pronunciation class) for a language, for context-aware override. */
  getHomographWords(data: { lang: string }): string[];
  /** Resolve homograph occurrences to context-appropriate IPA (null = not a homograph). */
  disambiguate(data: { lang: string; voice: string; items: { word: string; tokens: string[]; index: number }[] }): (string | null)[];
  speakWord(data: { word: string; voice: string }): void;
  /** Synthesize a word to WAV bytes (Firefox: played in the content script). */
  synthesizeAudio(data: { word: string; voice: string }): number[];
  extensionToggled(isEnabled: boolean): void;
  languageChanged(language: LanguageOption): void;
  modeChanged(mode: Mode): void;
  accentChanged(accent: string): void;
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
