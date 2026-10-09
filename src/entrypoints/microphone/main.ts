// The page that asks for the microphone, once, under the extension's own name.
//
// The translator panel lives in the page being read, and a microphone asked for there is asked
// for under that site's name, once for every site. So the first press of the panel's microphone
// opens this page instead: the browser asks here, the answer is the extension's for good, and
// the page closes again. Recording itself happens in the extension's own background pages.
import { mount, unmount } from 'svelte';
import Hearing from '@/ui/listen/Hearing.svelte';
import '@/ui/listen/hearing.css';
import { themeOf } from '@/ui/theme';
import { current, darkSide } from '@/settings';

const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
document.documentElement.className = themeOf(dark);
void current().then((settings) => {
  document.documentElement.className = themeOf(darkSide(settings, dark), settings.theme);
});

const app = document.getElementById('app')!;
let drawn = mount(Hearing, { target: app, props: { state: 'asking' } });

void (async () => {
  let given = false;
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    for (const track of stream.getTracks()) track.stop();
    given = true;
  } catch {
    void unmount(drawn);
    drawn = mount(Hearing, { target: app, props: { state: 'refused' } });
  }
  // The host closes this page once it has the answer.
  await browser.runtime.sendMessage({ microphone: given }).catch(() => undefined);
})();
