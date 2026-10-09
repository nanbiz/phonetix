// What a reader says to the translator panel, recorded and written down.
//
// The microphone is the extension's, never the page's: asked for from the page being read, it
// is asked for under that site's name, once for every site. So it is granted once, on a page of
// the extension's own that exists for that and closes again, and recorded where each browser
// lets an extension record without a window of its own: Chromium's offscreen page, made with
// the reason to record, and Firefox's toolbar popup, which is a page the reader can see. Firefox
// gives its background page no microphone even once the extension has been allowed one.
//
// Where it is written down follows the other engines: the offscreen page on Chromium, the
// background page on Firefox.

import { ask } from './voice';
import { allowed, resample } from '@/engines/listen';
import { ENCODER_URL, MODEL_HOST, type Hosts } from '@/engines/whisper';
import type { Heard } from './messages';

const IS_FIREFOX = import.meta.env.BROWSER === 'firefox';

/** What the panel shows while a question is under way: recording or writing it down, and how
 *  much of the model has arrived where it is still arriving. */
export interface Listening {
  /** The microphone being opened, hearing, or writing down what it heard. */
  phase: 'asking' | 'listening' | 'thinking';
  share?: number;
}

/** Where the model comes from. A host of the reader's own may serve it, as one may serve the
 *  dictionaries, with the encoder under speech-v1/ as the release has it; nothing in the
 *  product sets it. */
async function modelHosts(): Promise<Hosts> {
  const got = await browser.storage.local.get('speechBaseUrl');
  const own = got.speechBaseUrl;
  if (typeof own !== 'string' || !own) return { model: MODEL_HOST, encoder: ENCODER_URL };
  const base = own.replace(/\/?$/, '/');
  return { model: base, encoder: `${base}speech-v1/${ENCODER_URL.split('/').pop()}` };
}

/** Shown to the panel through storage, which a content script can watch. */
async function show(listening: Listening | null): Promise<void> {
  if (listening) await browser.storage.local.set({ listening });
  else await browser.storage.local.remove('listening');
}

// The offscreen page cannot reach storage, so it tells the host and the host tells the panel.
if (!IS_FIREFOX) {
  browser.runtime.onMessage.addListener((message: unknown) => {
    if (message && typeof message === 'object' && 'listening' in message) {
      void show((message as { listening: Listening | null }).listening);
    }
    return false;
  });
}

/** The page that asks for the microphone, once. Resolves to whether it was given. */
const GRANT_PAGE = 'microphone.html';

/**
 * Ask the reader for the microphone, on a page of the extension's own, opened beside the tab
 * the panel is in so that closing it comes back there.
 */
async function grant(tab: number | undefined): Promise<boolean> {
  const opened = await browser.tabs.create({
    url: browser.runtime.getURL(`/${GRANT_PAGE}`),
    ...(tab !== undefined ? { openerTabId: tab } : {}),
  });
  return new Promise<boolean>((resolve) => {
    const answered = (message: unknown) => {
      if (!message || typeof message !== 'object' || !('microphone' in message)) return false;
      done((message as { microphone: boolean }).microphone);
      return false;
    };
    const closed = (id: number) => {
      if (id === opened.id) done(false);
    };
    const done = (given: boolean) => {
      browser.runtime.onMessage.removeListener(answered);
      browser.tabs.onRemoved.removeListener(closed);
      if (opened.id !== undefined) void browser.tabs.remove(opened.id).catch(() => undefined);
      if (tab !== undefined) void browser.tabs.update(tab, { active: true }).catch(() => undefined);
      resolve(given);
    };
    browser.runtime.onMessage.addListener(answered);
    browser.tabs.onRemoved.addListener(closed);
  });
}

/** Whether the extension may use the microphone, asked where it would be used. */
async function permitted(): Promise<'granted' | 'denied' | 'prompt'> {
  if (IS_FIREFOX) return allowed();
  return ask<'granted' | 'denied' | 'prompt'>('microphone', '', []);
}

/** The recording under way on Firefox, as the popup sends it here piece by piece, and how to
 *  end it from here. */
interface Arriving {
  pieces: Float32Array[];
  rate: number;
  /** Whether the popup heard anything loud enough to be speech. A popup closed by a press
   *  elsewhere never says, and what it sent is taken as said. */
  spoke: boolean;
  /** Resolves once the popup has stopped: by a pause, by a press, or by being closed. */
  ended: Promise<void>;
  stop: () => void;
}
let arriving: Arriving | null = null;
/** Called with the recording as soon as the popup starts one. */
let started: ((it: Arriving) => void) | null = null;

if (IS_FIREFOX) {
  browser.runtime.onConnect.addListener((port) => {
    if (port.name !== 'listen') return;
    let end: () => void = () => {};
    const it: Arriving = {
      pieces: [],
      rate: 48000,
      spoke: true,
      ended: new Promise<void>((resolve) => (end = resolve)),
      stop: () => port.postMessage({ stop: true }),
    };
    type Sent = { piece?: number[]; rate?: number; done?: boolean; spoke?: boolean };
    port.onMessage.addListener((message: Sent) => {
      if (message.piece) {
        it.pieces.push(Float32Array.from(message.piece));
        it.rate = message.rate ?? it.rate;
      }
      if (message.done) {
        it.spoke = message.spoke ?? true;
        end();
      }
    });
    // A popup closed by a press elsewhere ends the recording as surely as a pause does, and
    // what was said up to then is the question.
    port.onDisconnect.addListener(() => end());
    arriving = it;
    // Listening from the first piece of what is said, not from the popup opening: its
    // microphone takes the better part of a second to start, and a reader shown it is on
    // starts talking.
    const first = (message: { piece?: number[]; done?: boolean }) => {
      if (!message.piece && !message.done) return;
      port.onMessage.removeListener(first);
      started?.(it);
    };
    port.onMessage.addListener(first);
  });
}

/**
 * Whether Firefox lets the extension use the microphone, known before the press that needs it:
 * Firefox opens a popup only while the press that asked for it is still being handled, so the
 * popup is opened before anything is awaited, and asking first would be awaiting.
 */
let mayHear: PermissionState = 'prompt';
if (IS_FIREFOX) {
  void navigator.permissions
    .query({ name: 'microphone' })
    .then((status) => {
      mayHear = status.state;
      status.onchange = () => (mayHear = status.state);
    })
    .catch(() => undefined);
}

/** Open Firefox's toolbar popup as the page that records, and resolve once it records. Called
 *  before anything is awaited, for the reason above. */
function openRecorder(): Promise<Arriving> {
  arriving = null;
  const begun = new Promise<Arriving>((resolve, reject) => {
    started = resolve;
    // The popup connects as soon as it has the microphone, or never does.
    setTimeout(() => reject(new Error('the microphone did not start')), 5000);
  });
  // The Firefox build is Manifest V2, where the toolbar button is the browser action.
  const button = browser.action ?? browser.browserAction;
  void button.setPopup({ popup: '/listen.html' });
  const opened = button.openPopup();
  // The popup is the settings view again as soon as this one is open, or failed to open.
  void opened
    .catch(() => undefined)
    .then(() => button.setPopup({ popup: '/popup.html' }));
  return Promise.all([begun, opened]).then(([it]) => it).finally(() => (started = null));
}

/** Everything the popup recorded, once it has stopped. */
async function recorded(it: Arriving): Promise<{ samples: Float32Array; rate: number; spoke: boolean }> {
  await it.ended;
  if (arriving === it) arriving = null;
  const samples = new Float32Array(it.pieces.reduce((n, piece) => n + piece.length, 0));
  let at = 0;
  for (const piece of it.pieces) {
    samples.set(piece, at);
    at += piece.length;
  }
  return { samples, rate: it.rate, spoke: it.spoke };
}

/** Hear a question on Firefox, the popup already opening. */
async function hearInPopup(lang: string, opening: Promise<Arriving>): Promise<Heard> {
  try {
    const hosts = await modelHosts();
    const { prepare, transcribe } = await import('@/engines/whisper');
    let share: number | undefined;
    // Asking until the popup's microphone is sending: the model's progress is told meanwhile,
    // and a reader shown it as hearing would start talking too soon.
    let phase: Listening['phase'] = 'asking';
    const tell = () => void show({ phase, ...(share !== undefined && share < 1 ? { share } : {}) });
    const ready = prepare(hosts, (got) => {
      share = got;
      tell();
    });
    ready.catch(() => undefined);
    const it = await opening;
    phase = 'listening';
    tell();
    const { samples, rate, spoke } = await recorded(it);
    if (!spoke) return { kind: 'nothing' };
    phase = 'thinking';
    tell();
    await ready;
    const text = await transcribe(hosts, resample(samples, rate), lang);
    return text ? { kind: 'said', text } : { kind: 'nothing' };
  } finally {
    await show(null);
  }
}

/**
 * What the reader said in [lang]; or that they would not let the extension hear them, or said
 * nothing.
 *
 * [tab] is the tab the panel is in, which a page asking for the microphone opens beside.
 *
 * On Firefox, a press that first has to ask for the microphone ends there: the popup that
 * records can only be opened by a press, and the one that asked has been handled by the time
 * the reader answered. The next press hears.
 */
export function hear(lang: string, tab: number | undefined): Promise<Heard> {
  if (IS_FIREFOX && mayHear === 'granted') return hearInPopup(lang, openRecorder());
  return (async (): Promise<Heard> => {
    try {
      let state = await permitted();
      if (IS_FIREFOX) mayHear = state;
      if (state !== 'granted') {
        await grant(tab);
        state = await permitted();
        if (IS_FIREFOX) mayHear = state;
        if (state !== 'granted') return { kind: 'refused' };
        if (IS_FIREFOX) return { kind: 'nothing' };
      }
      if (IS_FIREFOX) return { kind: 'nothing' };
      const hosts = await modelHosts();
      // Listening is shown by the page that records, once its microphone is sending.
      const text = await ask<string>('listen', lang, [], undefined, undefined, hosts);
      return text ? { kind: 'said', text } : { kind: 'nothing' };
    } finally {
      await show(null);
    }
  })();
}

/** End what is being recorded now, as the panel's second press does. */
export async function stopHearing(): Promise<void> {
  if (IS_FIREFOX) {
    arriving?.stop();
    return;
  }
  await ask('stop-listening', '', []).catch(() => undefined);
}
