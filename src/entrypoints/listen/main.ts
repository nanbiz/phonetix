// Firefox's toolbar popup, while the translator panel's microphone is on.
//
// Firefox gives an extension's background page no microphone, even once the extension has
// been allowed one, and gives one to a page of the extension's the reader can see. So for as
// long as a question is being said, the toolbar popup is this page: it records, passes the
// recording to the background as it arrives, and closes when the reader stops talking.
import { mount } from 'svelte';
import Hearing from '@/ui/listen/Hearing.svelte';
import '@/ui/listen/hearing.css';
import { themeOf } from '@/ui/theme';
import { current, darkSide } from '@/settings';
import { record } from '@/engines/listen';

const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
document.documentElement.className = themeOf(dark);
void current().then((settings) => {
  document.documentElement.className = themeOf(darkSide(settings, dark), settings.theme);
});
mount(Hearing, { target: document.getElementById('app')!, props: { state: 'recording' } });

/** How often what was recorded is passed on: often enough that a popup closed by a press
 *  elsewhere loses almost nothing, seldom enough not to send a message per sample block. */
const PASS_MS = 100;

void (async () => {
  const port = browser.runtime.connect({ name: 'listen' });
  let held: Float32Array[] = [];
  let rate = 48000;
  let spoke = false;
  const pass = () => {
    if (held.length === 0) return;
    const piece = new Float32Array(held.reduce((n, it) => n + it.length, 0));
    let at = 0;
    for (const it of held) {
      piece.set(it, at);
      at += it.length;
    }
    held = [];
    port.postMessage({ piece: Array.from(piece), rate });
  };
  try {
    const recording = await record((piece, at) => {
      held.push(piece);
      rate = at;
    });
    const passing = setInterval(pass, PASS_MS);
    port.onMessage.addListener((message: { stop?: boolean }) => {
      if (message.stop) recording.stop();
    });
    spoke = (await recording.done).spoke;
    clearInterval(passing);
    pass();
  } finally {
    port.postMessage({ done: true, spoke });
    window.close();
  }
})();
