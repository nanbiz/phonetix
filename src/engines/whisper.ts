// ─── the ear ─────────────────────────────────────────────────────────────────
//
// What a reader said to the translator panel, written down. Whisper, run on the reader's own
// processor: nothing that was said leaves the browser, and it works the same in every browser,
// which a browser's own speech recognition does not (Firefox has none, Chrome's sends the
// recording to Google).
//
// Runs where the voice and the translator run, and for the same reason: it needs a document,
// and a Chromium service worker has none. The library and the WebAssembly runtime ship with the
// extension (scripts/copy-whisper.mjs); the model is fetched the first time it is needed and
// kept in the browser's cache from then on.

/** The model: Whisper's base size, which knows every language the panel offers, in its
 *  quantized form, which is 73 MB where the full one is 276. Pinned to one revision, so what
 *  arrives does not change when the repository does. */
const MODEL = 'onnx-community/whisper-base';
const REVISION = '1846881b6b3a3024392c1eea3ad983695bc23925';
/** Where the model is published. */
export const MODEL_HOST = 'https://huggingface.co/';

/** How far along getting the model is, from 0 to 1, while it arrives. */
export type Getting = (share: number) => void;

interface Engine {
  processor: any;
  tokenizer: any;
  model: any;
  Tensor: any;
}

let starting: Promise<Engine> | null = null;
/** Who hears how far the model has got: whoever asked last, since a download started for one
 *  question is still arriving when the next is asked. */
let getting: Getting = () => {};

/**
 * The engine, started once. Nothing starts it until a reader speaks.
 *
 * [host] is where the model comes from; [told] hears how far along it is the first time,
 * when it is fetched rather than read from the cache.
 */
function engine(host: string, told: Getting): Promise<Engine> {
  getting = told;
  if (!starting) {
    starting = (async () => {
      const url = chrome.runtime.getURL('whisper/transformers.js');
      const lib = await import(/* @vite-ignore */ url);
      lib.env.allowLocalModels = false;
      lib.env.remoteHost = host;
      lib.env.useBrowserCache = true;
      // The runtime beside the library, never the one the library would fetch from a CDN:
      // an extension runs only the code it shipped.
      lib.env.backends.onnx.wasm.wasmPaths = {
        mjs: chrome.runtime.getURL('whisper/ort-wasm-simd-threaded.mjs'),
        wasm: chrome.runtime.getURL('whisper/ort-wasm-simd-threaded.wasm'),
      };
      // Each file reports its own bytes; what a reader is shown is all of them together.
      const files = new Map<string, { loaded: number; total: number }>();
      const progress_callback = (event: { status: string; file?: string; loaded?: number; total?: number }) => {
        if (event.status !== 'progress' || !event.file || !event.total) return;
        files.set(event.file, { loaded: event.loaded ?? 0, total: event.total });
        let loaded = 0;
        let total = 0;
        for (const file of files.values()) {
          loaded += file.loaded;
          total += file.total;
        }
        getting(total > 0 ? loaded / total : 0);
      };
      const options = { revision: REVISION, progress_callback };
      const [processor, tokenizer, model] = await Promise.all([
        lib.AutoProcessor.from_pretrained(MODEL, options),
        lib.AutoTokenizer.from_pretrained(MODEL, options),
        lib.WhisperForConditionalGeneration.from_pretrained(MODEL, {
          ...options,
          dtype: { encoder_model: 'q8', decoder_model_merged: 'q8' },
          device: 'wasm',
        }),
      ]);
      // The encoder's output is used twice, to tell the language and to write the words, and
      // the encoder is nearly all of the time a question takes. Generation drops any input it
      // does not list, so it is listed.
      model.forward_params = [...model.forward_params, 'encoder_outputs'];
      return { processor, tokenizer, model, Tensor: lib.Tensor };
    })().catch((e) => {
      // A failed start is not the answer to every later question: the next one tries again.
      starting = null;
      throw e;
    });
  }
  return starting;
}

/** Make the engine ready, fetching the model if this browser does not hold it yet. */
export async function prepare(host: string, getting: Getting): Promise<void> {
  await engine(host, getting);
}

/** Whisper's name for a language, where it differs from the one the extension uses. */
const WHISPER_NAMES: Record<string, string> = { nb: 'no', nn: 'nn', fil: 'tl' };

/**
 * What was said, and in which of [langs].
 *
 * The language is decided between the two the panel is translating between, not among the
 * hundred Whisper knows: a short question in Spanish can sound more like Portuguese to it, and
 * the reader said it in one of their two. Whisper decides that from the first step of decoding,
 * where it writes the language's token; only these languages' tokens are compared there.
 *
 * [samples] are mono, at 16 kHz, which is what the model listens at.
 */
export async function transcribe(
  host: string,
  samples: Float32Array,
  langs: string[],
): Promise<{ text: string; lang: string }> {
  const { processor, tokenizer, model, Tensor } = await engine(host, getting);
  const inputs = await processor(samples);
  const prepared = await model._prepare_encoder_decoder_kwargs_for_generation({
    inputs_tensor: inputs.input_features,
    model_inputs: { input_features: inputs.input_features },
    model_input_name: 'input_features',
    generation_config: model.generation_config,
  });
  const encoder_outputs = prepared.encoder_outputs;

  const candidates = langs
    .map((lang) => {
      const id = tokenizer.convert_tokens_to_ids(`<|${WHISPER_NAMES[lang] ?? lang}|>`);
      return { lang, id: typeof id === 'number' && id !== tokenizer.unk_token_id ? id : null };
    })
    .filter((it): it is { lang: string; id: number } => it.id !== null);
  let lang = candidates[0]?.lang ?? langs[0] ?? 'en';
  if (candidates.length > 1) {
    const start = tokenizer.convert_tokens_to_ids('<|startoftranscript|>');
    const { logits } = await model.forward({
      encoder_outputs,
      decoder_input_ids: new Tensor('int64', BigInt64Array.from([BigInt(start)]), [1, 1]),
    });
    const width = logits.dims[logits.dims.length - 1];
    const last = logits.data.subarray(logits.data.length - width);
    lang = candidates.reduce((best, it) => (last[it.id] > last[best.id] ? it : best)).lang;
  }

  const ids = await model.generate({
    input_features: inputs.input_features,
    encoder_outputs,
    language: WHISPER_NAMES[lang] ?? lang,
    task: 'transcribe',
    max_new_tokens: 96,
  });
  const text = String(tokenizer.batch_decode(ids, { skip_special_tokens: true })[0] ?? '').trim();
  return { text, lang };
}
