// The reading core, as the extension holds it.
//
// One instance, in the background, because a pack is tens of megabytes and a copy per tab
// would be a copy per tab. Everything else asks it through a message.
//
// The module is the same crate the phone links: a word answered here and the same word
// answered on the phone go through one cascade, so the two cannot drift into disagreeing
// about what a word means.
import init, { Core } from './wasm/lexcore.js';
import type { Answer } from './answer';
import type { AnnotateOptions, Batch, TextRun } from './tokens';

/** Where the compiled core sits in the extension's own package. */
const BINARY = 'core/lexcore_bg.wasm';

let core: Core | null = null;
let starting: Promise<Core> | null = null;

/**
 * The core, started if it is not already.
 *
 * Started once and shared: instantiating the module is the expensive part, and a second
 * instance would hold a second copy of every open pack.
 */
export function coreReady(): Promise<Core> {
  if (core) return Promise.resolve(core);
  if (!starting) {
    starting = init({ module_or_path: chrome.runtime.getURL(BINARY as never) })
      .then(() => {
        core = new Core();
        return core;
      })
      .catch((e) => {
        // A failed start is not a permanent one: a later call tries again rather than
        // returning the same rejection for the life of the worker.
        starting = null;
        throw e;
      });
  }
  return starting;
}

/** Whether the core has been started, for a health check that must not start it. */
export function coreRunning(): boolean {
  return core !== null;
}

/**
 * Take a pack's bytes into the core.
 *
 * Returns the language the pack turned out to be for, which is read from the pack itself
 * rather than taken from whoever fetched it: a file named de.pack that holds Spanish would
 * otherwise answer Spanish words to a reader who asked for German.
 */
export async function openPack(bytes: Uint8Array): Promise<string> {
  const it = await coreReady();
  return it.openPack(bytes);
}

/** Give up a pack's memory. */
export async function closePack(lang: string): Promise<void> {
  const it = await coreReady();
  it.closePack(lang);
}

/** Which languages the core can answer for right now. */
export async function openLanguages(): Promise<string[]> {
  const it = await coreReady();
  return it.languages();
}

/**
 * What the core says about one word.
 *
 * The answer arrives as JSON because an Answer is a tree and the boundary carries text. Its
 * shape is written once, in the core, and mirrored in [Answer].
 */
export async function lookUp(spelling: string, source: string, target: string): Promise<Answer> {
  const it = await coreReady();
  return JSON.parse(it.lookUp(spelling, source, target)) as Answer;
}

/**
 * What a batch of runs gets drawn on it.
 *
 * The runs cross as parallel arrays because that is how the host holds them already, and
 * serialising a page's text only to parse it straight back would copy every word for nothing.
 */
export async function annotate(
  runs: TextRun[],
  source: string,
  target: string,
  options: AnnotateOptions
): Promise<Batch> {
  const it = await coreReady();
  return JSON.parse(
    it.annotate(
      new Uint32Array(runs.map((run) => run.id)),
      runs.map((run) => run.text),
      runs.map((run) => run.lang ?? ''),
      source,
      target,
      options.mode,
      options.density,
      options.seen ?? []
    )
  ) as Batch;
}

/**
 * Fill in what an engine answered about the words the packs missed.
 *
 * The results go back to the core rather than being drawn beside its tokens, so one answer
 * still drives the page, the card and the audio.
 */
export async function complete(
  batch: number,
  results: { token: number; gloss?: string; ipa?: string }[],
  engine: string
): Promise<Batch> {
  const it = await coreReady();
  return JSON.parse(
    it.complete(
      BigInt(batch),
      new Uint32Array(results.map((r) => r.token)),
      results.map((r) => r.gloss ?? ''),
      results.map((r) => r.ipa ?? ''),
      engine
    )
  ) as Batch;
}

/**
 * The reader's frequency bar, as the densities its positions mean.
 *
 * The whole bar at once, because a settings view says what each position means while the
 * reader drags it, and asking across the boundary per step would be a message per pixel.
 */
export async function curve(): Promise<number[]> {
  const it = await coreReady();
  return Array.from(it.curve());
}

/** Give up a batch the host has finished drawing. */
export async function dropBatch(batch: number): Promise<void> {
  const it = await coreReady();
  it.dropBatch(BigInt(batch));
}
