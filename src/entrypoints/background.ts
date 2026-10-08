// The extension's long-lived side, which is the host and nothing else.
//
// Everything it does is in src/host: it owns the core, the packs the reader has, and the
// answers a page asks for. This file exists because the browser needs an entry point.
import { host } from '@/host';
import { current, set } from '@/settings';

export default defineBackground(() => {
  host();
  // The keyboard's two commands. The panel itself is the page's, because that is where it is
  // drawn; this only carries the keystroke to it.
  browser.commands?.onCommand.addListener((command) => {
    if (command === 'switch-on-off') {
      void current().then((settings) => set('on', !settings.on));
      return;
    }
    if (command !== 'translator') return;
    // A tab that cannot be asked - a browser page, a store page - simply has no panel.
    void (async () => {
      const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
      if (!tab?.id) return;
      await browser.tabs.sendMessage(tab.id, { phonetix: 'askForAWord', data: {} });
    })().catch(() => undefined);
  });
});
