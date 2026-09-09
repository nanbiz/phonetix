// Where a language's pack comes from and where it stays.
//
// A pack is a file the core opens as bytes, not a table this side reads, so it is kept
// exactly as it arrived. It is downloaded once and held after that, because the reader who
// needs a dictionary offline is the reader on a train.
//
// The host it comes from is a runtime setting and appears nowhere in the source.
import { createStore, get, set, del, keys } from 'idb-keyval';
import { openLanguages, openPack } from '@/core';

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
  const bytes = await get<ArrayBuffer>(lang, store);
  return bytes === undefined ? undefined : new Uint8Array(bytes);
}

/**
 * A language's pack, open in the core.
 *
 * The pack says which language it is for and that is what comes back: a file named de.pack
 * holding Spanish would otherwise answer Spanish words to a reader who asked for German.
 * Nothing is cached before it opens, because a file that cannot be read is worse in the
 * cache than absent from it.
 */
export async function open(lang: string): Promise<string | null> {
  if ((await openLanguages()).includes(lang)) return lang;

  const held = await cached(lang);
  if (held) return openPack(held);

  const base = await host();
  if (!base) return null;

  const res = await fetch(`${base}/packs/${lang}.pack`);
  if (!res.ok) throw new Error(`${res.status} fetching the ${lang} pack`);
  const bytes = new Uint8Array(await res.arrayBuffer());
  const opened = await openPack(bytes);
  await set(opened, bytes.slice().buffer, store);
  return opened;
}

/** Which languages are held on this machine, open or not. */
export async function held(): Promise<string[]> {
  return (await keys(store)).map(String).sort();
}

/** Give up a pack, on the machine and in the core. */
export async function forget(lang: string): Promise<void> {
  await del(lang, store);
}
