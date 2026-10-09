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
import type { Listening } from '@/host/speech';
import { darkHere } from './inline';
import { THEME, themeOf } from '@/ui/theme';
import { OURS } from './scan';

let host: HTMLElement | null = null;
/** Everything typing sends, which leaves a shadow root for the page to hear. */
const TYPING = [
  'keydown', 'keypress', 'keyup', 'beforeinput', 'input',
  'compositionstart', 'compositionupdate', 'compositionend', 'paste',
] as const;
let drawn: ReturnType<typeof mount> | null = null;

/** Take the panel down. */
export function close(): void {
  // Nothing to wait for: the panel has no transition out.
  if (drawn) void unmount(drawn);
  drawn = null;
  host?.remove();
  host = null;
  document.removeEventListener('pointerdown', outside, true);
}

/** A press anywhere but the panel puts it away: the question was not asked. */
function outside(event: PointerEvent): void {
  if (host && !event.composedPath().includes(host)) close();
}

/** Whether the panel is up, so the shortcut closes what it opened. */
export function showing(): boolean {
  return drawn !== null;
}

/**
 * Ask for a word or a phrase, over whatever is being read, between the reader's own language
 * and the one they are learning, either way round.
 */
export async function open(page = ''): Promise<void> {
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
  // What is typed into the panel stays in it. A page that sends typing to a box of its own
  // whenever its document has the keys and no field of its own is focused - a chat's message
  // box - sees the panel only as this element, which is no field, and took every key typed
  // into the panel for its own. The panel's own handlers are inside, and have run by here.
  for (const kind of TYPING) host.addEventListener(kind, (event) => event.stopPropagation());
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

  const mine = settings.target;
  // The language chosen before, and otherwise the one the page is in, where that is not the
  // reader's own: a reader on a Spanish page asking for a word is asking for it in Spanish.
  const learning =
    [settings.learning, page, ...packs.held].find((lang) => lang && lang !== mine) ?? '';
  let recent = settings.recent;
  drawn = mount(Ask, {
    target: frame,
    props: {
      mine,
      learning,
      held: packs.held,
      recent,
      onMine: (lang: string) => void set('target', lang),
      onLearning: (lang: string) => {
        void set('learning', lang);
        // Kept so the next panel offers it near the top: the list is every language there is,
        // and a reader asks in a handful of them.
        recent = asked(recent, lang);
        void set('recent', recent);
      },
      ask: (text: string, mine: string, learning: string, turned: boolean | undefined) =>
        sendMessage('ask', { text, mine, learning, turned }).catch(() => null),
      hear: (lang: string) => sendMessage('listen', { lang }),
      stopHearing: () => void sendMessage('stopListening', {}).catch(() => undefined),
      // What the microphone is doing, which the host keeps where a page can watch it.
      watchHearing: (told: (now: Listening | null) => void) => {
        const changed = (changes: Record<string, { newValue?: unknown }>, area: string) => {
          if (area === 'local' && 'listening' in changes) {
            told((changes.listening.newValue as Listening | undefined) ?? null);
          }
        };
        browser.storage.onChanged.addListener(changed);
        return () => browser.storage.onChanged.removeListener(changed);
      },
      close,
    },
  });
  document.addEventListener('pointerdown', outside, true);
}
