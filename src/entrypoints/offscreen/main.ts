/**
 * Offscreen document for running espeak-ng.
 *
 * Handles both phonemization (text → IPA) and audio synthesis
 * (text → PCM → WAV playback via espeak-ng's own voice engine).
 *
 * The espeak-ng JS+data files live in public/espeak/ and are loaded at
 * runtime via chrome.runtime.getURL — NOT bundled through Vite.
 */

let espeakModule: any = null;
let espeakWorker: any = null;
let initPromise: Promise<void> | null = null;
let audioCtx: AudioContext | null = null;

async function initEspeak() {
  if (espeakModule) return;
  const espeakUrl = chrome.runtime.getURL('espeak/espeak-ng.js');
  const mod = await import(/* @vite-ignore */ espeakUrl);
  const EspeakInit = mod.default;
  espeakModule = await EspeakInit();
  espeakWorker = new espeakModule.eSpeakNGWorker();
}

function phonemizeWord(word: string, voice: string): string {
  if (!espeakWorker) throw new Error('espeak not ready');
  espeakWorker.set_voice(voice, '');
  const resultPtr = espeakWorker.text_to_phonemes(word, 1); // 1 = IPA
  const ptr = resultPtr.ptr;
  const heap = espeakModule.HEAPU8;
  let end = ptr;
  while (heap[end] !== 0 && end < ptr + 50000) end++;
  return new TextDecoder('utf-8')
    .decode(heap.slice(ptr, end))
    .replace(/_/g, '')
    .trim();
}

chrome.runtime.onMessage.addListener((message: any, _sender: chrome.runtime.MessageSender, sendResponse: (response: any) => void) => {
  if (message.target !== 'offscreen') return false;

  if (message.type === 'phonemize-batch') {
    const { words, voice } = message.data as { words: string[]; voice: string };

    (async () => {
      if (!initPromise) initPromise = initEspeak();
      await initPromise;

      const results: Record<string, string> = {};
      for (const word of words) {
        try {
          results[word] = phonemizeWord(word, voice);
        } catch {
          results[word] = word;
        }
      }
      sendResponse({ success: true, results });
    })();

    return true;
  }

  if (message.type === 'speak-word') {
    const { word, voice } = message.data as { word: string; voice: string };

    (async () => {
      if (!initPromise) initPromise = initEspeak();
      await initPromise;

      try {
        espeakWorker.set_voice(voice, '');
        espeakWorker.set_rate(130); // slightly slower than default 175

        const chunks: Int16Array[] = [];
        espeakWorker.synthesize(word, (audioData: Int16Array) => {
          if (audioData.length > 0) chunks.push(new Int16Array(audioData));
        });

        if (chunks.length === 0) {
          sendResponse({ success: false, error: 'No audio produced' });
          return;
        }

        // Concatenate PCM chunks
        const totalLen = chunks.reduce((s, c) => s + c.length, 0);
        const pcm = new Int16Array(totalLen);
        let offset = 0;
        for (const c of chunks) { pcm.set(c, offset); offset += c.length; }

        // Play via AudioContext
        if (!audioCtx) audioCtx = new AudioContext();
        const sampleRate = espeakWorker.samplerate || 22050;
        const buf = audioCtx.createBuffer(1, pcm.length, sampleRate);
        const channel = buf.getChannelData(0);
        for (let i = 0; i < pcm.length; i++) channel[i] = pcm[i] / 32768;

        const src = audioCtx.createBufferSource();
        src.buffer = buf;
        src.connect(audioCtx.destination);
        src.start();

        sendResponse({ success: true });
      } catch (e: any) {
        sendResponse({ success: false, error: e.message });
      }
    })();

    return true;
  }

  if (message.type === 'ping') {
    sendResponse({ ready: !!espeakWorker });
    return true;
  }

  return false;
});

// Start initializing immediately
initPromise = initEspeak();
initPromise
  .then(() => console.log('[Phonetix] espeak-ng initialized in offscreen document'))
  .catch((e) => console.error('[Phonetix] Failed to init espeak-ng:', e));
