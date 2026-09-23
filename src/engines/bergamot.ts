// ─── the translation engine ──────────────────────────────────────────────────
//
// What a word means when no dictionary says it. The cascade answers first and this fills what
// it could not: a word with no entry, a pair no lex pack covers, a phrase a reader selected.
// Everything it produces is marked as a guess, because a machine's answer wearing a
// dictionary's authority is the one thing the whole cascade is shaped to avoid.
//
// Runs where espeak runs, and for the same reason: it needs a document and Web Workers, and a
// Chromium service worker has neither. The files are loaded from a URL rather than bundled, so
// what ships is the engine as its authors published it.

/** One request, in the shape the engine takes it. */
interface Ask {
  from: string;
  to: string;
  text: string;
  html?: boolean;
}

import { PUBLISHED } from '@/host/published';
import { whole } from '@/host/whole';

let translator: any = null;
let starting: Promise<any> | null = null;

/** One host's list of translation models, each marked with where it came from. */
async function listing(from: string): Promise<Listed[]> {
  const res = await fetch(`${from}/models.json`);
  if (!res.ok) throw new Error(`${res.status} asking for the translation models`);
  return ((await res.json()) as Listed[]).map((model) => ({ ...model, at: from }));
}

interface Listed {
  from: string;
  to: string;
  files: Record<string, { name: string; url?: string; sha256?: string }>;
  config?: Record<string, unknown>;
  at?: string;
}

/**
 * Where the models come from: the listing published beside the packs.
 *
 * The listing names, for each direction, the files Mozilla publishes for Firefox's own
 * translations, where Mozilla publishes them and the checksum each has to have - pinned to
 * the versions this product was checked against, so what arrives does not change when
 * Mozilla publishes something new. A host of the reader's own may list bare names instead,
 * and those are looked for beside its listing, which is how a host serving its own models
 * works.
 */
function backing(
  TranslatorBacking: new (options?: Record<string, unknown>) => any,
  base: string,
) {
  return class OwnHost extends TranslatorBacking {
    async loadModelRegistery() {
      // The reader's own host first, and the published listing for the directions it does
      // not have (see @/host/published). Each keeps where it came from, since a listing may
      // name bare files that are served beside it.
      const theirs = base === PUBLISHED ? [] : await listing(base).catch(() => []);
      const published = await listing(PUBLISHED).catch(() => []);
      const all = [
        ...theirs,
        ...published.filter((one) => !theirs.some((own) => own.from === one.from && own.to === one.to)),
      ];
      if (all.length === 0) throw new Error('no list of translation models could be had');
      return all.map((model) => ({ ...model, model }));
    }

    async loadTranslationModel({ from, to }: { from: string; to: string }) {
      const registry = await this.registry;
      const found = registry.find((it: any) => it.from === from && it.to === to);
      if (!found) throw new Error(`no model for ${from} to ${to}`);
      const files = found.model.files ?? {};
      const wanted = ['model', 'lex', 'vocab', 'trgvocab', 'srcvocab', 'qualityModel'];
      const fetched: Record<string, ArrayBuffer> = {};
      await Promise.all(
        wanted
          .filter((name) => files[name])
          .map(async (name) => {
            const file = files[name];
            const res = await fetch(file.url ?? `${found.model.at ?? base}/models/${file.name}`);
            if (!res.ok) throw new Error(`${res.status} fetching ${file.name}`);
            // Read so that a Firefox background page fetching it is not put to sleep halfway:
            // see [whole].
            const got = await whole(res);
            const bytes = got.buffer.slice(got.byteOffset, got.byteOffset + got.byteLength);
            // What was checked is what is used: a file that arrived different - cut short, or
            // an error page served under its name - is refused rather than handed to the
            // engine as a model.
            if (file.sha256) {
              const got = await sha256(bytes);
              if (got !== file.sha256) throw new Error(`${file.name} arrived as ${got}`);
            }
            fetched[name] = bytes;
          })
      );
      // The engine names the shortlist and the vocabularies differently from the registry a
      // model is published under, and takes either one vocabulary or a pair.
      const vocabs = fetched.srcvocab && fetched.trgvocab
        ? [fetched.srcvocab, fetched.trgvocab]
        : [fetched.vocab];
      return {
        model: fetched.model,
        shortlist: fetched.lex,
        vocabs,
        qualityModel: fetched.qualityModel ?? null,
        config: found.model.config ?? {},
      };
    }
  };
}

/** A file's checksum, as the listing writes it. */
async function sha256(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * The engine, started once. Nothing starts it until a word needs it.
 *
 * Where the models live is passed in rather than read here. This runs in the page an
 * extension keeps for engines, and that page has `chrome.runtime` and nothing else: reading
 * the setting from it is not merely unavailable, `chrome.storage` is undefined there, so
 * asking cost a swallowed error and a product with translation quietly switched off.
 */
async function engine(base: string): Promise<any> {
  if (translator) return translator;
  if (!starting) {
    starting = (async () => {
      if (!base) throw new Error('no host for the translation models');
      const url = chrome.runtime.getURL('bergamot/translator.js');
      const mod = await import(/* @vite-ignore */ url);
      const Backing = backing(mod.TranslatorBacking, base);
      const options = { workerUrl: chrome.runtime.getURL('bergamot/translator-worker.js') };
      translator = new mod.BatchTranslator(options, new Backing(options));
      return translator;
    })();
  }
  return starting;
}

/**
 * Translate a batch of words or phrases, in the order they were given.
 *
 * One request per piece rather than one joined request: what comes back has to line up with
 * the tokens the core is waiting to fill, and a joined text comes back as a sentence nobody
 * can cut apart again at exactly the boundaries it went in on.
 */
export async function translate(
  base: string,
  from: string,
  to: string,
  pieces: string[],
): Promise<string[]> {
  if (pieces.length === 0 || from === to) return [];
  const it = await engine(base);
  const answers = await Promise.all(
    pieces.map((text) =>
      it
        .translate({ from, to, text, html: false } satisfies Ask)
        .then((res: any) => String(res?.target?.text ?? '').trim())
        .catch(() => '')
    )
  );
  return answers;
}

/** Whether a pair can be translated at all, so nothing offers what it cannot do. */
export async function pairs(base: string): Promise<{ from: string; to: string }[]> {
  try {
    const it = await engine(base);
    const registry = await it.backing.registry;
    return registry.map((model: any) => ({ from: model.from, to: model.to }));
  } catch {
    return [];
  }
}
