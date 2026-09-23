// Where a language's pack comes from and where it stays.
//
// A pack is a file the core opens as bytes, not a table this side reads, so it is kept
// exactly as it arrived. It is downloaded once and held after that, because the reader who
// needs a dictionary offline is the reader on a train.
//
// The host it comes from is a runtime setting and appears nowhere in the source.
import { PUBLISHED } from './published';
import { whole } from './whole';
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
 * Where the packs are fetched from: where they are published, unless something has said
 * otherwise - a check serving its own, or a build pointed elsewhere.
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
      : PUBLISHED;
  } catch {
    // Storage being unavailable is not a reason to fail a lookup, and the published packs are
    // where they always were.
    return PUBLISHED;
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
 * A language's pack, open in the core, from what this machine already holds - and the
 * dictionary of what its words mean fetched in the background the first time it is needed.
 *
 * What is opened now is what is here, so a page is answered at once with how its words are
 * said; the meanings arrive a moment later and the page is told. Making the reader find a list
 * and fetch each dictionary by hand was a step nobody could work out, and until they did, a
 * reader who had chosen a language to read into was answered with nothing in it.
 */
export async function open(lang: string): Promise<string | null> {
  if ((await openLanguages()).includes(lang) && !carried.has(lang)) return lang;
  const bytes = await cached(lang);
  if (bytes) {
    carried.delete(lang);
    return openPack(bytes);
  }
  inBackground(lang);
  if ((await openLanguages()).includes(lang)) return lang;
  return fromWhatWeCarry(lang);
}

/**
 * Open the reader's own language, the one a page is read into.
 *
 * Its meanings are what another language's words are joined to, so they are fetched like any
 * other - except English, which is never joined to: a reader of English is given the gloss
 * itself, and a hundred megabytes of English definitions would arrive to answer nothing. What
 * it does need, how an English word is said, is in what the app carries.
 */
export async function openReadInto(lang: string): Promise<string | null> {
  if (lang !== 'en') return open(lang);
  if ((await openLanguages()).includes(lang)) return lang;
  const bytes = await cached(lang);
  if (bytes) {
    carried.delete(lang);
    return openPack(bytes);
  }
  return fromWhatWeCarry(lang);
}

/** What is being fetched now, and what failed lately, so a language is fetched once at a time
 *  and a host that is down is not asked again on every page. */
const fetching = new Set<string>();
/**
 * The largest dictionary fetched without being asked for, which depends on what the
 * connection costs: up to a size nobody notices where the reader has asked the browser to save
 * data or the line is slow, and otherwise the English dictionary too, a hundred megabytes.
 */
function byItself(): number {
  const connection = (
    navigator as Navigator & { connection?: { saveData?: boolean; effectiveType?: string } }
  ).connection;
  const saving =
    connection?.saveData === true || /(^|-)2g$/.test(connection?.effectiveType ?? '');
  return (saving ? 64 : 160) * 1024 * 1024;
}
const failedAt = new Map<string, number>();
const RETRY_AFTER_MS = 5 * 60_000;

function inBackground(lang: string): void {
  if (!/^[a-z]{2,3}(-[a-z0-9]+)?$/i.test(lang) || fetching.has(lang)) return;
  const failed = failedAt.get(lang);
  if (failed !== undefined && Date.now() - failed < RETRY_AFTER_MS) return;
  fetching.add(lang);
  void (async () => {
    try {
      const listed = (await offered()).find((pack) => pack.lang === lang);
      // Only what is published: asking for a pack that does not exist is a request that
      // can only fail, once per page, for every language nobody built a pack for. And only
      // what is small enough to fetch on a reader's behalf over the connection they are on.
      if (!listed || listed.bytes > byItself()) return;
      await get(lang);
      failedAt.delete(lang);
    } catch {
      failedAt.set(lang, Date.now());
    } finally {
      fetching.delete(lang);
    }
  })();
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
export function get(lang: string): Promise<string | null> {
  // One download per language however many ask: the page fetching it by itself and the reader
  // pressing "get" at the same moment are one question.
  const running = getting.get(lang);
  if (running) return running;
  const started = fetchPack(lang).finally(() => getting.delete(lang));
  getting.set(lang, started);
  return started;
}

const getting = new Map<string, Promise<string | null>>();

async function fetchPack(lang: string): Promise<string | null> {
  const already = await open(lang);
  // Unless what is open is one we carried: that one says how the language's words are said,
  // and what is being fetched says what they mean. The fetched pack takes the place of the
  // carried one, which is where the meanings a reader asked for come from.
  if (already && !carried.has(already)) return already;

  const base = await host();
  if (!base) return null;

  // From the reader's own host first where one is set, even where it lists nothing, and from
  // the published release where that fails. Each is checked against what its own listing
  // says the file has to be: a pack cut short opens as a pack that is missing words, which is
  // worse than not having it.
  const listing = await offered().catch(() => []);
  const from = base === PUBLISHED ? [PUBLISHED] : [base, PUBLISHED];
  let bytes: Uint8Array<ArrayBuffer> | null = null;
  let failed: unknown = null;
  for (const at of from) {
    const listed = listing.find((pack) => pack.lang === lang && (pack.at ?? base) === at);
    try {
      bytes = await packFrom(at, lang, listed?.sha256);
      break;
    } catch (e) {
      failed = e;
    }
  }
  if (!bytes) throw failed instanceof Error ? failed : new Error(`no ${lang} pack could be had`);
  const opened = await openPack(bytes);
  await keep(opened, bytes.slice().buffer, store);
  carried.delete(opened);
  await told();
  return opened;
}

/** One pack from one host, whole and matching its checksum where one is given. */
async function packFrom(
  at: string,
  lang: string,
  sha256: string | undefined,
): Promise<Uint8Array<ArrayBuffer>> {
  const res = await fetch(`${at}/${lang}.pack`);
  if (!res.ok) throw new Error(`${res.status} fetching the ${lang} pack`);
  const bytes = await whole(res);
  if (sha256) {
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    const got = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
    if (got !== sha256) throw new Error(`the ${lang} pack arrived as ${got}`);
  }
  return bytes;
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
  /** Where it is served from: the reader's own host, or the published release. */
  at?: string;
}

/** Each host's list, asked for once a while rather than once per question. */
const listings = new Map<string, { at: number; packs: Offered[] }>();
const LISTING_FOR_MS = 10 * 60_000;

/** What the published packs are, or what a host of the reader's own offers instead. */
export async function offered(): Promise<Offered[]> {
  const base = await host();
  if (!base) return [];
  // The reader's own host first, where one is set, and the published packs for what it does
  // not have (see ./published).
  let theirs: Offered[] = [];
  ownHostDown = false;
  if (base !== PUBLISHED) {
    theirs = await listed(base).catch(() => {
      ownHostDown = true;
      return [];
    });
  }
  let published: Offered[] = [];
  try {
    published = await listed(PUBLISHED);
  } catch (e) {
    // Nothing from either is a failure; the reader's own host answering is not.
    if (theirs.length === 0) throw e;
  }
  return [...theirs, ...published.filter((pack) => !theirs.some((own) => own.lang === pack.lang))];
}

/** Whether the host the reader set answered the last time its list was asked for. The
 *  published packs stand in for it, and the reader who set it is still owed knowing. */
let ownHostDown = false;
export function ownHostAnswered(): boolean {
  return !ownHostDown;
}

/** One host's list of packs, each marked with where it is served from. */
async function listed(base: string): Promise<Offered[]> {
  const had = listings.get(base);
  if (had && Date.now() - had.at < LISTING_FOR_MS) return had.packs;
  const res = await fetch(`${base}/packs.json`);
  if (!res.ok) throw new Error(`${res.status} fetching the list of packs`);
  const got = (await res.json()) as Offered[] | { packs: Offered[] };
  const packs = (Array.isArray(got) ? got : (got.packs ?? [])).map((pack) => ({ ...pack, at: base }));
  listings.set(base, { at: Date.now(), packs });
  return packs;
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
