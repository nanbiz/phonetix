// Where the reader's choices are kept in a browser, and how they are watched.
//
// The shape of them and everything answered from a value alone is in ./shape, which the phone
// draws the same view from: this half is the browser's storage and nothing else.
import { DEFAULTS, type Settings } from './shape';

export {
  DEFAULTS, accentFor, allowed, darkSide, readInto, setAccent, type Settings,
} from './shape';

/** Where each setting lives, as the storage key it is watched under. */
const KEYS: Record<keyof Settings, `local:${string}`> = {
  on: 'local:on',
  layer: 'local:layer',
  density: 'local:density',
  target: 'local:targetLanguage',
  source: 'local:sourceLanguage',
  learning: 'local:learning',
  accents: 'local:accents',
  narrow: 'local:narrow',
  hideStress: 'local:hideStress',
  delay: 'local:hoverDelay',
  animations: 'local:animations',
  host: 'local:packBaseUrl',
  theme: 'local:theme',
  dark: 'local:dark',
  off: 'local:sitesOff',
  lens: 'local:lens',
  touchWords: 'local:touchWords',
  apps: 'local:apps',
  allApps: 'local:allApps',
};

/** Everything the reader has chosen, with the defaults filled in. */
export async function current(): Promise<Settings> {
  const out = { ...DEFAULTS };
  /** Which settings were actually stored, so one that never was can be filled in from the
   *  others rather than from its own default. */
  const stored = new Set<string>();
  await Promise.all(
    (Object.keys(KEYS) as (keyof Settings)[]).map(async (name) => {
      try {
        const value = await storage.getItem(KEYS[name]);
        if (value !== null && value !== undefined) {
          (out as Record<string, unknown>)[name] = value;
          stored.add(name);
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

/** Switch this site on or off, leaving the rest as they are. */
export async function setSite(settings: Settings, host: string, on: boolean): Promise<void> {
  const off = on ? settings.off.filter((name) => name !== host) : [...settings.off, host];
  await set('off', [...new Set(off)]);
}

/** Change one setting, which every listener hears. */
export async function set<K extends keyof Settings>(name: K, value: Settings[K]): Promise<void> {
  await storage.setItem(KEYS[name], value);
}
