// Where the reader's choices are kept in a browser, and how they are watched.
//
// The shape of them and everything answered from a value alone is in ./shape, which the phone
// draws the same view from: this half is the browser's storage and nothing else.
import { DEFAULTS, type Settings } from './shape';
import { LANGUAGES } from '@/data/languages';

export {
  DEFAULTS, accentFor, allowed, darkSide, setAccent, translates, type Settings,
} from './shape';

/** Where each setting lives, as the storage key it is watched under. */
const KEYS: Record<keyof Settings, `local:${string}`> = {
  on: 'local:on',
  layer: 'local:layer',
  density: 'local:density',
  target: 'local:targetLanguage',
  source: 'local:sourceLanguage',
  known: 'local:knownLanguages',
  learning: 'local:learning',
  recent: 'local:recentLanguages',
  accents: 'local:accents',
  narrow: 'local:narrow',
  hideStress: 'local:hideStress',
  delay: 'local:hoverDelay',
  cards: 'local:cardsOnPoint',
  animations: 'local:animations',
  host: 'local:packBaseUrl',
  theme: 'local:theme',
  dark: 'local:dark',
  off: 'local:sitesOff',
  side: 'local:markSide',
  pin: 'local:markPinned',
  restY: 'local:markRestY',
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
  // The reader's own language, until they say otherwise, is the one their browser speaks:
  // the card translates into it, and a card with nothing to translate into answers nothing.
  if (!out.target) out.target = browserLanguage();
  // And the languages they read as they are, until they say otherwise, are the ones their
  // browser is told they read: the list a browser sends every site to choose a language from.
  if (!stored.has('known')) out.known = browserLanguages().filter((lang) => lang !== out.target);
  return out;
}

/** The language the browser is set to, where it is one the product knows. */
function browserLanguage(): string {
  const said = (globalThis.navigator?.language ?? '').split('-')[0].toLowerCase();
  return said in LANGUAGES ? said : 'en';
}

/** Every language the browser says its reader reads, first preferred first. */
function browserLanguages(): string[] {
  const said = globalThis.navigator?.languages ?? [];
  return [...new Set(said.map((tag) => tag.split('-')[0].toLowerCase()))]
    .filter((lang) => lang in LANGUAGES);
}

/** Watch every setting, told which one changed and what everything is now. */
export function watch(told: (settings: Settings) => void): () => void {
  const stop = (Object.keys(KEYS) as (keyof Settings)[]).map((name) =>
    storage.watch(KEYS[name], () => {
      current().then(told, (e: unknown) => console.warn('phonetix: settings unreadable', e));
    })
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
