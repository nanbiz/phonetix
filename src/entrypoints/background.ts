// The extension's long-lived side, which is the host and nothing else.
//
// Everything it does is in src/host: it owns the core, the packs the reader has, and the
// answers a page asks for. This file exists because the browser needs an entry point.
import { host } from '@/host';

export default defineBackground(() => {
  host();
  // The word a reader is looking for, without reaching for the mouse. The panel itself is the
  // page's, because that is where it is drawn; this only carries the keystroke to it.
  browser.commands?.onCommand.addListener(async (command) => {
    if (command !== 'ask-for-a-word') return;
    const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
    if (!tab?.id) return;
    await browser.tabs
      .sendMessage(tab.id, { phonetix: 'askForAWord', data: {} })
      .catch(() => undefined);
  });
});
