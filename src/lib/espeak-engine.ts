// ─── espeak-ng engine ────────────────────────────────────────────────
// Runs in any context that has DOM/AudioContext plus chrome.runtime.getURL:
// the Chrome offscreen document, or the Firefox background event page.
//
// The espeak-ng JS+data files live in public/espeak/ and are loaded at runtime
// via chrome.runtime.getURL — not bundled through Vite.

let espeakModule: any = null;
let espeakWorker: any = null;
let initPromise: Promise<void> | null = null;
let audioCtx: AudioContext | null = null;

export function initEspeak(): Promise<void> {
  if (!initPromise) {
    initPromise = (async () => {
      const espeakUrl = chrome.runtime.getURL('espeak/espeak-ng.js');
      const mod = await import(/* @vite-ignore */ espeakUrl);
      espeakModule = await mod.default();
      espeakWorker = new espeakModule.eSpeakNGWorker();
    })();
  }
  return initPromise;
}

function phonemizeWord(word: string): string {
  const resultPtr = espeakWorker.text_to_phonemes(word, 1); // 1 = IPA
  const ptr = resultPtr.ptr;
  const heap = espeakModule.HEAPU8;
  let end = ptr;
  while (heap[end] !== 0 && end < ptr + 50000) end++;
  return new TextDecoder('utf-8').decode(heap.slice(ptr, end)).replace(/_/g, '').trim();
}

export async function phonemizeBatch(words: string[], voice: string): Promise<Record<string, string>> {
  await initEspeak();
  espeakWorker.set_voice(voice, '');
  const results: Record<string, string> = {};
  for (const word of words) {
    try {
      results[word] = phonemizeWord(word);
    } catch {
      results[word] = word;
    }
  }
  return results;
}

/** Synthesize a word to raw mono PCM. */
function synthPcm(word: string, voice: string): { pcm: Int16Array; sampleRate: number } {
  espeakWorker.set_voice(voice, '');
  espeakWorker.set_rate(130); // slightly slower than default 175

  const chunks: Int16Array[] = [];
  espeakWorker.synthesize(word, (audioData: Int16Array) => {
    if (audioData.length > 0) chunks.push(new Int16Array(audioData));
  });

  const totalLen = chunks.reduce((s, c) => s + c.length, 0);
  const pcm = new Int16Array(totalLen);
  let offset = 0;
  for (const c of chunks) { pcm.set(c, offset); offset += c.length; }
  return { pcm, sampleRate: espeakWorker.samplerate || 22050 };
}

/** Synthesize and play in-context via AudioContext (Chrome offscreen document). */
export async function speak(word: string, voice: string): Promise<void> {
  await initEspeak();
  const { pcm, sampleRate } = synthPcm(word, voice);
  if (pcm.length === 0) return;

  if (!audioCtx) audioCtx = new AudioContext();
  const buf = audioCtx.createBuffer(1, pcm.length, sampleRate);
  const channel = buf.getChannelData(0);
  for (let i = 0; i < pcm.length; i++) channel[i] = pcm[i] / 32768;

  const src = audioCtx.createBufferSource();
  src.buffer = buf;
  src.connect(audioCtx.destination);
  src.start();
}

/** Synthesize a word to WAV bytes for playback elsewhere (Firefox: content script). */
export async function synthesizeWav(word: string, voice: string): Promise<Uint8Array> {
  await initEspeak();
  const { pcm, sampleRate } = synthPcm(word, voice);
  return encodeWav(pcm, sampleRate);
}

/** Wrap mono 16-bit PCM in a minimal WAV container. */
function encodeWav(pcm: Int16Array, sampleRate: number): Uint8Array {
  const dataLen = pcm.length * 2;
  const buf = new ArrayBuffer(44 + dataLen);
  const view = new DataView(buf);
  const writeStr = (o: number, s: string) => { for (let i = 0; i < s.length; i++) view.setUint8(o + i, s.charCodeAt(i)); };

  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + dataLen, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true);       // PCM chunk size
  view.setUint16(20, 1, true);        // audio format = PCM
  view.setUint16(22, 1, true);        // channels = mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byte rate
  view.setUint16(32, 2, true);        // block align
  view.setUint16(34, 16, true);       // bits per sample
  writeStr(36, 'data');
  view.setUint32(40, dataLen, true);
  new Int16Array(buf, 44).set(pcm);
  return new Uint8Array(buf);
}
