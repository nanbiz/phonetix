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

import { whole } from '@/host/whole';

/** The model: Whisper's small size, which knows every language the panel offers, quantized.
 *  Small rather than base because the panel is mostly asked one or two words, which base wrote
 *  down wrong one time in three; small, cut to the audio below, is as quick as base was. Pinned
 *  to one revision, so what arrives does not change when the repository does. */
const MODEL = 'onnx-community/whisper-small';
const REVISION = '36050c46d777d46dc4b5f43f6d90574fc38f8732';
/** Where the model is published. */
export const MODEL_HOST = 'https://huggingface.co/';
/** The encoder made to take audio shorter than thirty seconds (tools/cut_whisper_encoder.py),
 *  published beside the dictionaries, and what it has to hash to. */
export const ENCODER_URL =
  'https://github.com/nanbiz/phonetix/releases/download/speech-v1/whisper-small-encoder-q8.onnx';
const ENCODER_SHA256 = '40332deda207fbedfe7800c12082247abaf727e7a5aedb761ae6a6490ed9e0ef';
const ENCODER_FILE = 'onnx/encoder_model_quantized.onnx';
/** How much audio the encoder is given at the least, in the processor's frames of ten
 *  milliseconds: ten seconds. Shorter is quicker, and at five seconds one- and two-word questions came out wrong
 *  twice as often; at ten they came out as right as at the full thirty, in a third of the time. */
const LEAST_FRAMES = 1000;
/** The most processor threads to run on. */
const THREADS = 8;

/** Where the model's files come from: the model's own host, and the encoder's. */
export interface Hosts {
  model: string;
  encoder: string;
}

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

/** A file's checksum, as the release writes it. */
async function sha256(bytes: Uint8Array<ArrayBuffer>): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * The engine, started once. Nothing starts it until a reader speaks.
 *
 * [hosts] is where the model comes from; [told] hears how far along it is the first time,
 * when it is fetched rather than read from the cache.
 */
function engine(hosts: Hosts, told: Getting): Promise<Engine> {
  getting = told;
  if (!starting) {
    starting = (async () => {
      const url = chrome.runtime.getURL('whisper/transformers.js');
      const lib = await import(/* @vite-ignore */ url);
      lib.env.allowLocalModels = false;
      lib.env.remoteHost = hosts.model;
      lib.env.useBrowserCache = true;
      // The runtime beside the library, never the one the library would fetch from a CDN:
      // an extension runs only the code it shipped.
      lib.env.backends.onnx.wasm.wasmPaths = {
        mjs: chrome.runtime.getURL('whisper/ort-wasm-simd-threaded.mjs'),
        wasm: chrome.runtime.getURL('whisper/ort-wasm-simd-threaded.wasm'),
      };
      // Threads where the page may share memory between them, which an extension's pages may
      // on Chromium (the manifest isolates them); one where it may not.
      // The model run on a worker of its own rather than on this page's thread: three seconds
      // of arithmetic on Firefox's background page held up everything else the extension
      // does there, the panel's own "writing it down" included.
      lib.env.backends.onnx.wasm.proxy = true;
      lib.env.backends.onnx.wasm.numThreads = self.crossOriginIsolated
        ? Math.min(THREADS, navigator.hardwareConcurrency || 4)
        : 1;
      // Every file read here rather than by the library: the encoder comes from its own
      // release and has to be the one that was published, a Firefox background page reading
      // a file of a hundred megabytes has to be kept awake while it arrives, and what a reader
      // is shown arriving is all the files together.
      const files = new Map<string, { loaded: number; total: number }>();
      const arrived = (file: string, loaded: number, total: number) => {
        files.set(file, { loaded, total });
        let all = 0;
        let here = 0;
        for (const it of files.values()) {
          here += it.loaded;
          all += it.total;
        }
        getting(all > 0 ? here / all : 0);
      };
      lib.env.fetch = async (input: string | URL, init?: RequestInit) => {
        const asked = String(input);
        const encoder = asked.endsWith(`/${ENCODER_FILE}`);
        const res = await fetch(encoder ? hosts.encoder : asked, init);
        if (!res.ok) return res;
        const total = Number(res.headers.get('content-length')) || 0;
        const bytes = await whole(res, (loaded) => arrived(asked, loaded, total || loaded));
        if (encoder && (await sha256(bytes)) !== ENCODER_SHA256) {
          throw new Error('the speech model arrived different from the one published');
        }
        return new Response(bytes, {
          status: 200,
          headers: {
            'content-type': res.headers.get('content-type') ?? 'application/octet-stream',
            'content-length': String(bytes.length),
          },
        });
      };
      const options = { revision: REVISION };
      const [processor, tokenizer, model] = await Promise.all([
        lib.AutoProcessor.from_pretrained(MODEL, options),
        lib.AutoTokenizer.from_pretrained(MODEL, options),
        lib.WhisperForConditionalGeneration.from_pretrained(MODEL, {
          ...options,
          dtype: { encoder_model: 'q8', decoder_model_merged: 'q8' },
          device: 'wasm',
        }),
      ]);
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
export async function prepare(hosts: Hosts, told: Getting): Promise<void> {
  await engine(hosts, told);
}

/** Whisper's name for a language, where it differs from the one the extension uses. */
const WHISPER_NAMES: Record<string, string> = { nb: 'no', nn: 'nn', fil: 'tl' };

/**
 * What was said, in [lang].
 *
 * The language is the reader's, not Whisper's to guess: the panel says which side of its arrow
 * is being spoken, and the reader turns the arrow to speak the other. Guessed, a single word or
 * a short phrase, which is most of what is asked here, is too little to tell two languages
 * apart by, and a word written down in the wrong language is no word at all.
 *
 * [samples] are mono, at 16 kHz, which is what the model listens at.
 */
export async function transcribe(hosts: Hosts, samples: Float32Array, lang: string): Promise<string> {
  const { processor, tokenizer, model, Tensor } = await engine(hosts, getting);
  const { input_features: whole30 } = await processor(samples);
  // As much of the thirty-second window as was said, at the least ten seconds: the rest is
  // the silence the processor padded it with, and the encoder's time is in proportion.
  const [, bins, frames] = whole30.dims as number[];
  const said = Math.ceil((samples.length / 160 + 50) / 100) * 100;
  const kept = Math.min(frames, Math.max(LEAST_FRAMES, said));
  const cut = new Float32Array(bins * kept);
  for (let bin = 0; bin < bins; bin++) {
    cut.set(whole30.data.subarray(bin * frames, bin * frames + kept), bin * kept);
  }
  const ids = await model.generate({
    input_features: new Tensor('float32', cut, [1, bins, kept]),
    language: WHISPER_NAMES[lang] ?? lang,
    task: 'transcribe',
    max_new_tokens: 96,
  });
  return String(tokenizer.batch_decode(ids, { skip_special_tokens: true })[0] ?? '').trim();
}
