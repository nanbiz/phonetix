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
  /** The accent to read in, as a language tag: en-us, es-419, de-ch. Empty is the standard
   *  one, which is what most readers want and what a dictionary lists first. */
  accent: string;
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
  accent: '',
  off: [],
};

/** Where each setting lives, as the storage key it is watched under. */
const KEYS: Record<keyof Settings, `local:${string}`> = {
  on: 'local:on',
  layer: 'local:layer',
  density: 'local:density',
  target: 'local:targetLanguage',
  source: 'local:sourceLanguage',
  accent: 'local:accent',
  narrow: 'local:narrow',
  hideStress: 'local:hideStress',
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
