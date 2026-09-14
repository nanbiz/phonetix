// The panel the keyboard shortcut opens, over the page.
//
// It lives in a shadow root of its own, like the card, so a page's stylesheet cannot reach it
// and ours cannot reach the page. What it draws is the component both surfaces would draw;
// what it asks is the host, which owns the engines.
import { mount, unmount } from 'svelte';

import Ask from '@/ui/ask/Ask.svelte';
import askCss from '@/ui/settings/settings.css?inline';
import cardCss from '@/ui/card/card.css?inline';
import tokenCss from '@/ui/tokens.css?inline';
import { current, set } from '@/settings';
import { asked } from '@/settings/shape';
import { sendMessage } from '@/host/messages';
import { darkHere } from './inline';
import { THEME, themeOf } from '@/ui/theme';
import { OURS } from './scan';

let host: HTMLElement | null = null;
let drawn: ReturnType<typeof mount> | null = null;

/** Take the panel down. */
export function close(): void {
  if (drawn) unmount(drawn);
  drawn = null;
  host?.remove();
  host = null;
}

/** Whether the panel is up, so the shortcut closes what it opened. */
export function showing(): boolean {
  return drawn !== null;
}

/**
 * Ask for a word, over whatever is being read.
 *
 * The language it comes back in is the one the reader is learning, which they pick here and
 * which is remembered; the language they typed in is worked out by the core rather than
 * assumed, so they can ask in whatever language the word came to them in.
 */
export async function open(): Promise<void> {
  if (drawn) {
    close();
    return;
  }
  const settings = await current();
  const packs = await sendMessage('packs', {}).catch(() => ({ held: [] as string[] }));
  host = document.createElement('div');
  host.id = `${OURS}-ask`;
  host.style.cssText = 'position:fixed;inset:0 auto auto 0;width:0;height:0;z-index:2147483646;';
  document.body.appendChild(host);
  const shadow = host.attachShadow({ mode: 'open' });
  const style = document.createElement('style');
  style.textContent = `${tokenCss}\n${cardCss}\n${askCss}`;
  shadow.appendChild(style);
  const frame = document.createElement('div');
  frame.className = themeOf(darkHere(), settings.theme || THEME);
  // Where a panel over a page belongs: near the top, out of the way of what is being read,
  // and never wider than the window it is drawn in.
  frame.style.cssText =
    'position:fixed;top:72px;left:50%;transform:translateX(-50%);' +
    'width:min(28rem, calc(100vw - 32px));max-height:calc(100vh - 96px);overflow:auto;';
  shadow.appendChild(frame);

  let learning = settings.learning || packs.held.find((lang) => lang !== settings.target) || '';
  let recent = settings.recent;
  drawn = mount(Ask, {
    target: frame,
    props: {
      learning,
      held: packs.held,
      recent,
      onLearning: (lang: string) => {
        learning = lang;
        void set('learning', lang);
        // Kept so the next panel offers it near the top: the list is every language there is,
        // and a reader asks in a handful of them.
        recent = asked(recent, lang);
        void set('recent', recent);
      },
      ask: async (text: string, into: string) => {
        // What language it was typed in, asked of the core rather than assumed.
        const guess = await sendMessage('detect', { text }).catch(() => null);
        const from = guess?.language || settings.target || 'en';
        if (!into || into === from) return { answer: null, missing: false };
        return sendMessage('say', { text, source: into, target: from }).catch(() => null);
      },
      close,
    },
  });
}
