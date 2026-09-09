// Where a language's pack comes from and where it stays.
//
// A pack is a file the core opens as bytes, not a table this side reads, so it is kept
// exactly as it arrived. It is downloaded once and held after that, because the reader who
// needs a dictionary offline is the reader on a train.
//
// The host it comes from is a runtime setting and appears nowhere in the source.
import { createStore, get as read, set as keep, del, keys } from 'idb-keyval';
import { closePack, openLanguages, openPack } from '@/core';

const store = createStore('phonetix-packs', 'lang-pack');

/** Where the packs are served from, or nothing when the reader has not said. */
async function host(): Promise<string | undefined> {
  try {
    return (await storage.getItem<string>('local:packBaseUrl'))?.replace(/\/+$/, '');
  } catch {
    return undefined;   // storage unavailable is not a reason to fail a lookup
  }
}

async function cached(lang: string): Promise<Uint8Array | undefined> {
  const bytes = await read<ArrayBuffer>(lang, store);
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
  return bytes ? openPack(bytes) : null;
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
  if (already) return already;

  const base = await host();
  if (!base) return null;

  const res = await fetch(`${base}/packs/${lang}.pack`);
  if (!res.ok) throw new Error(`${res.status} fetching the ${lang} pack`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  const opened = await openPack(bytes);
  await keep(opened, bytes.slice().buffer, store);
  return opened;
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
}
