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
}

export const DEFAULTS: Settings = {
  on: true,
  layer: 'gloss',
  density: 12,
  target: '',
  source: '',
};

/** Where each setting lives, as the storage key it is watched under. */
const KEYS: Record<keyof Settings, `local:${string}`> = {
  on: 'local:on',
  layer: 'local:layer',
  density: 'local:density',
  target: 'local:targetLanguage',
  source: 'local:sourceLanguage',
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

/** Change one setting, which every listener hears. */
export async function set<K extends keyof Settings>(name: K, value: Settings[K]): Promise<void> {
  await storage.setItem(KEYS[name], value);
}
