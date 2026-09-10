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

let translator: any = null;
let starting: Promise<any> | null = null;

/**
 * Where the models come from, which is wherever the reader said their dictionaries live.
 *
 * The engine's own default is a repository on the open internet. That is somebody else's host
 * and nothing here should reach for it on a reader's behalf, so the backing is replaced: the
 * registry and the model files are asked for at the reader's own pack host, beside the packs,
 * and with no host set there is no translation rather than a request to a stranger.
 */
function backing(
  TranslatorBacking: new (options?: Record<string, unknown>) => any,
  base: string,
) {
  return class OwnHost extends TranslatorBacking {
    async loadModelRegistery() {
      const res = await fetch(`${base}/models.json`);
      if (!res.ok) throw new Error(`${res.status} asking for the translation models`);
      const listed = (await res.json()) as {
        from: string;
        to: string;
        files: Record<string, { name: string }>;
      }[];
      return listed.map((model) => ({ ...model, model }));
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
            const res = await fetch(`${base}/models/${files[name].name}`);
            if (!res.ok) throw new Error(`${res.status} fetching ${files[name].name}`);
            fetched[name] = await res.arrayBuffer();
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
        .translate({ from, to, text, html: false } as Ask)
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
