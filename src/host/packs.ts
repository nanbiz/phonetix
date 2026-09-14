// Where a language's pack comes from and where it stays.
//
// A pack is a file the core opens as bytes, not a table this side reads, so it is kept
// exactly as it arrived. It is downloaded once and held after that, because the reader who
// needs a dictionary offline is the reader on a train.
//
// The host it comes from is a runtime setting and appears nowhere in the source.
import { createStore, get as read, set as keep, del, keys } from 'idb-keyval';
import { buildIpaPack, closePack, openLanguages, openPack } from '@/core';

const store = createStore('phonetix-packs', 'lang-pack');
/**
 * The packs built out of what the product carries, kept apart from the ones a reader fetched.
 *
 * Two different things under one name would be one thing: a carried pack says how a language's
 * words are said, a fetched one says what they mean, and the core holds one pack per language.
 * Separate stores keep "what this machine holds" honest - a carried pack is not a dictionary a
 * reader went and got - and let a fetched one take the open slot when it arrives.
 */
const carriedStore = createStore('phonetix-packs', 'carried-pack');

/** Languages whose open pack is one we carried, so a fetched one may take its place. */
const carried = new Set<string>();

/**
 * Where the packs are served from, or nothing when the reader has not said.
 *
 * Read from the browser's own store rather than through the framework's helper. The helper is
 * an auto-import, and an auto-import exists only in the bundles the framework builds that way:
 * in the page the engines run in it is simply not defined, so asking for it threw, the catch
 * below turned that into "the reader has set no host", and translation was quietly off with
 * nothing anywhere saying why.
 *
 * Through `browser` rather than `chrome`, for the reason the messaging is: on Gecko the chrome
 * namespace is callback-style, so awaiting it there returned undefined and every reader on
 * Firefox was told they had set no host - no dictionaries, no translation, no explanation.
 */
export async function host(): Promise<string | undefined> {
  try {
    const got = await browser.storage.local.get('packBaseUrl');
    const said = got?.packBaseUrl;
    return typeof said === 'string' && said !== ''
      ? said.replace(/\/+$/, '')
      : undefined;
  } catch {
    return undefined;   // storage unavailable is not a reason to fail a lookup
  }
}

async function cached(lang: string): Promise<Uint8Array | undefined> {
  const bytes = await read<ArrayBuffer>(lang, store);
  return bytes === undefined ? undefined : new Uint8Array(bytes);
}

/** The pack built from what we carry, where one has been built already. */
async function carriedPack(lang: string): Promise<Uint8Array | undefined> {
  const bytes = await read<ArrayBuffer>(lang, carriedStore);
  return bytes === undefined ? undefined : new Uint8Array(bytes);
}

/**
 * A language's pack, open in the core, from what this machine already holds.
 *
 * Nothing is fetched here. A dictionary is tens of megabytes and the reader decides which
 * ones they want; a page that quietly pulled one down because it happened to be in that
 * language would be spending their connection on their behalf.
 */
export async function open(lang: string): Promise<string | null> {
  if ((await openLanguages()).includes(lang)) return lang;
  const bytes = await cached(lang);
  if (bytes) {
    carried.delete(lang);
    return openPack(bytes);
  }
  return fromWhatWeCarry(lang);
}

/**
 * The dictionary for a language out of what this build carries.
 *
 * Every language the product knows how to pronounce ships with it, as the gzipped map it has
 * always shipped as - a fraction of the size of the pack it becomes. The pack is built the
 * first time the language is read and kept, so the cost is paid once and a reader who has
 * configured nothing at all is still answered.
 *
 * Nothing here reaches the network. What a host adds on top is what a dictionary cannot carry:
 * meanings, which are built from the dumps and are far larger.
 */
async function fromWhatWeCarry(lang: string): Promise<string | null> {
  if (!/^[a-z]{2,3}(-[a-z0-9]+)?$/i.test(lang)) return null;
  try {
    const already = await carriedPack(lang);
    if (already) {
      const opened = await openPack(already);
      carried.add(opened);
      return opened;
    }
    const res = await fetch(browser.runtime.getURL(`/dictionaries/${lang}.json.gz` as never));
    if (!res.ok) return null;
    const built = await buildIpaPack(lang, new Uint8Array(await res.arrayBuffer()));
    if (!built) return null;
    const opened = await openPack(built);
    // Kept as a pack rather than rebuilt on every start: building one is seconds for a big
    // language, and a reader opens a page more often than they install.
    await keep(opened, built.slice().buffer, carriedStore);
    carried.add(opened);
    return opened;
  } catch {
    return null;   // a build without the dictionaries answers what the host gives it
  }
}

/**
 * Fetch a language's pack, keep it, and open it.
 *
 * The pack says which language it is for and that is what comes back: a file named de.pack
 * holding Spanish would otherwise answer Spanish words to a reader who asked for German. It
 * is opened before it is kept, because a file that cannot be read is worse in the cache than
 * absent from it.
 */
export async function get(lang: string): Promise<string | null> {
  const already = await open(lang);
  // Unless what is open is one we carried: that one says how the language's words are said,
  // and what is being fetched says what they mean. The fetched pack takes the place of the
  // carried one, which is where the meanings a reader asked for come from.
  if (already && !carried.has(already)) return already;

  const base = await host();
  if (!base) return null;

  const res = await fetch(`${base}/packs/${lang}.pack`);
  if (!res.ok) throw new Error(`${res.status} fetching the ${lang} pack`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  const opened = await openPack(bytes);
  await keep(opened, bytes.slice().buffer, store);
  carried.delete(opened);
  await told();
  return opened;
}

/**
 * Say which dictionaries this machine holds, where a page can hear it.
 *
 * A page reading itself has no way of knowing a dictionary has arrived: it redraws when a
 * setting changes, and which packs are held is not a setting. Without this, a reader who
 * fetched the dictionary for the page in front of them sat looking at the same bare page until
 * they touched something else or loaded it again.
 */
async function told(): Promise<void> {
  try {
    await browser.storage.local.set({ heldPacks: await held() });
  } catch {
    // A page that cannot be told redraws on the next setting it is given, as it did before.
  }
}

/** One pack as the host that serves them describes it, which is what packbuild writes. */
export interface Offered {
  id: string;
  lang: string;
  built: number;
  entries: number;
  keys: number;
  glosses: number;
  bytes: number;
  sha256: string;
}

/**
 * What the host has to offer.
 *
 * Nothing when the reader has not said where their dictionaries come from: an extension that
 * went looking on its own would be an extension deciding who to talk to.
 */
export async function offered(): Promise<Offered[]> {
  const base = await host();
  if (!base) return [];
  const res = await fetch(`${base}/packs.json`);
  if (!res.ok) throw new Error(`${res.status} fetching the list of packs`);
  const listed = (await res.json()) as Offered[] | { packs: Offered[] };
  return Array.isArray(listed) ? listed : (listed.packs ?? []);
}

/** Which languages are held on this machine, open or not. */
export async function held(): Promise<string[]> {
  return (await keys(store)).map(String).sort();
}

/** Give up a pack, on the machine and in the core. */
export async function forget(lang: string): Promise<void> {
  await del(lang, store);
  await closePack(lang);
  await told();
  // And back to what the product carries, so giving up a dictionary leaves the language
  // readable rather than bare.
  await fromWhatWeCarry(lang);
}
