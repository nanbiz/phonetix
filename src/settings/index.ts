// What the reader has chosen, in one place.
//
// Each setting is one watched key. The defaults are here rather than at every reader of them,
// because a default repeated is a default that will be two different values the first time one
// of them is edited.
//
// The names are the same on both platforms; how they are stored is not, which is why this file
// is browser-side and the phone has its own store of the same keys.
import type { Layer } from '@/ext/content/inline';

export interface Settings {
  /** Whether the extension annotates at all. */
  on: boolean;
  /** What is drawn over a word. */
  layer: Layer;
  /** One word in every N, from the reader's frequency bar. */
  density: number;
  /** The language the reader is reading into. Empty until they choose one, and with nothing
   *  chosen the answer is a pronunciation rather than a translation. */
  target: string;
  /** The language of the page, when the reader overrides what the page declares. */
  source: string;
  /** Narrow transcriptions rather than broad ones. The card always shows the full form. */
  narrow: boolean;
  /** Leave the stress marks off the line over a word. */
  hideStress: boolean;
  /**
   * Which accent to read each language in, as language tag to accent tag: en → en-us,
   * es → es-419.
   *
   * Per language rather than one for everything, because an accent is only meaningful
   * relative to a language: a page in German has nothing to do with the reader's choice
   * between British and American English, and one field for both meant choosing an accent
   * for a page threw away the choice made for every other. A language with no entry is read
   * the way its dictionary lists it, which is what most readers want.
   */
  accents: Record<string, string>;
  /** How long the cursor rests on a word before its card opens, in milliseconds. A reader who
   *  reads with the pointer wants it slow; one who looks words up wants it instant. */
  delay: number;
  /** Whether the card eases in and the reveal fades. Off is instant, which is what a reader
   *  who finds movement distracting wants and what a slow machine wants. */
  animations: boolean;
  /** Where the dictionaries come from. The reader's own, and nowhere in the source: an
   *  extension that went looking on its own would be an extension deciding who to talk to. */
  host: string;
  /** Sites the reader has switched off, by hostname. Everywhere else is on: a reader who
   *  wants this on the web does not want to name every site it should work on. */
  off: string[];
}

export const DEFAULTS: Settings = {
  on: true,
  layer: 'gloss',
  density: 12,
  target: '',
  source: '',
  narrow: false,
  hideStress: true,
  accents: {},
  delay: 200,
  animations: false,
  host: '',
  off: [],
};

/** Where each setting lives, as the storage key it is watched under. */
const KEYS: Record<keyof Settings, `local:${string}`> = {
  on: 'local:on',
  layer: 'local:layer',
  density: 'local:density',
  target: 'local:targetLanguage',
  source: 'local:sourceLanguage',
  accents: 'local:accents',
  narrow: 'local:narrow',
  hideStress: 'local:hideStress',
  delay: 'local:hoverDelay',
  animations: 'local:animations',
  host: 'local:packBaseUrl',
  off: 'local:sitesOff',
};

/** Everything the reader has chosen, with the defaults filled in. */
export async function current(): Promise<Settings> {
  const out = { ...DEFAULTS };
  await Promise.all(
    (Object.keys(KEYS) as (keyof Settings)[]).map(async (name) => {
      try {
        const value = await storage.getItem(KEYS[name]);
        if (value !== null && value !== undefined) {
          (out as Record<string, unknown>)[name] = value;
        }
      } catch {
        // Storage unavailable is the default, not a failure: a reader with no stored choice
        // and a reader whose storage is unreachable want the same thing to happen.
      }
    })
  );
  return out;
}

/** Watch every setting, told which one changed and what everything is now. */
export function watch(told: (settings: Settings) => void): () => void {
  const stop = (Object.keys(KEYS) as (keyof Settings)[]).map((name) =>
    storage.watch(KEYS[name], async () => told(await current()))
  );
  return () => stop.forEach((off) => off());
}

/** Which accent this language is read in, or none, which is how its dictionary lists it. */
export function accentFor(settings: Settings, lang: string): string {
  return settings.accents[lang] ?? '';
}

/** Read one language in one accent, leaving the choice made for every other alone. */
export function setAccent(settings: Settings, lang: string, accent: string): Record<string, string> {
  const next = { ...settings.accents };
  if (accent === '') delete next[lang];
  else next[lang] = accent;
  return next;
}

/** Whether this site is one the reader switched off. */
export function allowed(settings: Settings, host: string): boolean {
  return settings.on && !settings.off.includes(host);
}

/** Switch this site on or off, leaving the rest as they are. */
export async function setSite(settings: Settings, host: string, on: boolean): Promise<void> {
  const off = on ? settings.off.filter((name) => name !== host) : [...settings.off, host];
  await set('off', [...new Set(off)]);
}

/** Change one setting, which every listener hears. */
export async function set<K extends keyof Settings>(name: K, value: Settings[K]): Promise<void> {
  await storage.setItem(KEYS[name], value);
}
