// ─── the microphone ──────────────────────────────────────────────────────────
//
// One thing said to the translator panel, recorded from the moment the reader presses the
// microphone to the moment they stop talking. Recorded in a page of the extension's own, never
// in the page being read: a microphone asked for from a page is asked for under that site's
// name, once per site, and an extension's own page asks once, under the extension's name.

/** How long a pause ends what is being said. */
const PAUSE_MS = 1200;
/** How long to wait for anything to be said at all. */
const NOTHING_MS = 7000;
/** The longest one question is. */
const LONGEST_MS = 15000;
/** How loud the quietest speech is, as the root mean square of a sample's amplitude. */
const SPEECH_FLOOR = 0.015;
/** How much louder than the room speech is. */
const OVER_ROOM = 3;
/** The span loudness is measured over. */
const WINDOW_MS = 50;
/** The rate the model listens at. */
export const RATE = 16000;

export interface Recording {
  /** Stop now, as a reader does who pressed the microphone again. */
  stop(): void;
  /** Everything recorded, at the rate it was recorded at, once it has stopped, and whether
   *  anything in it was loud enough to be speech. A recording with none is not a question:
   *  Whisper given silence or a hum writes "you" or "Thank you" rather than nothing. */
  done: Promise<Recorded>;
}

export interface Recorded {
  samples: Float32Array;
  rate: number;
  spoke: boolean;
}

/** Whether this page may use the microphone without asking, has been refused it, or would
 *  have to ask. */
export async function allowed(): Promise<'granted' | 'denied' | 'prompt'> {
  try {
    const status = await navigator.permissions.query({ name: 'microphone' });
    return status.state;
  } catch {
    return 'prompt';
  }
}

/**
 * Start recording.
 *
 * It stops by itself once the reader has said something and paused, or has said nothing for a
 * while, or has gone on for longer than a question is; or when [Recording.stop] is called.
 * [heard] is given each piece as it arrives, at the rate it was recorded at, for a page that
 * passes the recording on as it goes.
 */
export async function record(
  heard?: (piece: Float32Array, rate: number) => void,
): Promise<Recording> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
  });
  // The device's own rate: Firefox refuses to connect a microphone to a context running at
  // any other, so the recording is brought down to the model's rate afterwards instead.
  const context = new AudioContext();
  await context.audioWorklet.addModule(chrome.runtime.getURL('capture.js'));
  const source = context.createMediaStreamSource(stream);
  const capture = new AudioWorkletNode(context, 'phonetix-capture');
  source.connect(capture);
  const rate = context.sampleRate;

  const pieces: Float32Array[] = [];
  const span = Math.round((rate * WINDOW_MS) / 1000);
  let sum = 0;
  let counted = 0;
  let elapsed = 0;
  let room = Infinity;
  let spoke = false;
  let lastLoud = 0;
  let finish: () => void = () => {};
  const done = new Promise<Recorded>((resolve) => {
    let finished = false;
    finish = () => {
      if (finished) return;
      finished = true;
      capture.port.onmessage = null;
      source.disconnect();
      capture.disconnect();
      for (const track of stream.getTracks()) track.stop();
      void context.close().catch(() => undefined);
      const total = pieces.reduce((n, piece) => n + piece.length, 0);
      const samples = new Float32Array(total);
      let at = 0;
      for (const piece of pieces) {
        samples.set(piece, at);
        at += piece.length;
      }
      resolve({ samples, rate, spoke });
    };
  });

  capture.port.onmessage = (event: MessageEvent<Float32Array>) => {
    const piece = event.data;
    pieces.push(piece);
    heard?.(piece, rate);
    for (const value of piece) {
      sum += value * value;
      counted += 1;
      if (counted < span) continue;
      const loudness = Math.sqrt(sum / counted);
      elapsed += WINDOW_MS;
      sum = 0;
      counted = 0;
      // The room is the quietest it has been; speech is well over it, and over a floor that a
      // silent, perfectly clean microphone would otherwise put right at zero.
      room = Math.min(room, loudness);
      if (loudness > Math.max(SPEECH_FLOOR, room * OVER_ROOM)) {
        spoke = true;
        lastLoud = elapsed;
      }
      if (
        (spoke && elapsed - lastLoud > PAUSE_MS) ||
        (!spoke && elapsed > NOTHING_MS) ||
        elapsed > LONGEST_MS
      ) {
        finish();
        return;
      }
    }
  };
  return { stop: () => finish(), done };
}

/** A recording brought to the rate the model listens at, each new sample the average of the
 *  ones it stands for, so what is above the new rate's range is not folded back into it. */
export function resample(samples: Float32Array, rate: number): Float32Array {
  if (rate === RATE) return samples;
  const step = rate / RATE;
  const out = new Float32Array(Math.floor(samples.length / step));
  for (let i = 0; i < out.length; i++) {
    const from = Math.floor(i * step);
    const to = Math.min(samples.length, Math.max(from + 1, Math.floor((i + 1) * step)));
    let sum = 0;
    for (let j = from; j < to; j++) sum += samples[j];
    out[i] = sum / (to - from);
  }
  return out;
}
