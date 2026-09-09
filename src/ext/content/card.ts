// The card, over the page.
//
// It lives in a shadow root of its own so that a page's stylesheet cannot reach it and its
// own cannot reach the page. What it draws is the same component the viewbook draws, from the
// same Answer the phone's card is drawn from.
import { mount, unmount } from 'svelte';

import Opened from '@/ui/card/Opened.svelte';
import type { Answer } from '@/core/answer';
import cardCss from '@/ui/card/card.css?inline';
import tokenCss from '@/ui/tokens.css?inline';
import { darkPage } from './inline';
import { OURS } from './scan';

/** How far from the word the card sits, so the word it is about stays readable. */
const GAP = 8;

let host: HTMLElement | null = null;
let shadow: ShadowRoot | null = null;
let frame: HTMLElement | null = null;
let drawn: ReturnType<typeof mount> | null = null;
/** What the card on screen is about, so a second ask about the same word is not a redraw. */
let about: string | null = null;

function build(): { shadow: ShadowRoot; frame: HTMLElement } {
  if (shadow && frame) return { shadow, frame };
  host = document.createElement('div');
  host.id = OURS;
  // Fixed and above everything: a card that a page's own stacking context could cover would
  // be a card the reader cannot read.
  host.style.cssText =
    'position:fixed;inset:0 auto auto 0;width:0;height:0;z-index:2147483647;';
  document.body.appendChild(host);
  // Open, so that a check can read what the card says. The boundary keeps the page's CSS out
  // either way; closed would only hide it from the tests that prove it draws.
  shadow = host.attachShadow({ mode: 'open' });
  const style = document.createElement('style');
  style.textContent = `${tokenCss}\n${cardCss}`;
  shadow.appendChild(style);
  frame = document.createElement('div');
  // The theme the tokens are keyed by: every colour is defined inside one, so a card naming
  // no theme would have no colours at all. Which one comes from the page it is drawn over,
  // the same way the annotations decide, so a card and the words it is about never come out
  // of two different palettes.
  frame.className = `theme-paper mode-${darkPage() ? 'dark' : 'light'}`;
  frame.style.cssText = 'position:fixed;width:var(--card-width);max-width:calc(100vw - 16px);';
  shadow.appendChild(frame);
  return { shadow, frame };
}

/**
 * Put the card where it fits: under the word, or over it when there is no room below.
 *
 * Measured after it is drawn rather than guessed, because how tall it is depends on what the
 * word turned out to be.
 */
function place(at: DOMRect): void {
  if (!frame) return;
  const box = frame.getBoundingClientRect();
  const below = at.bottom + GAP;
  const above = at.top - GAP - box.height;
  const top = below + box.height <= window.innerHeight ? below : Math.max(GAP, above);
  const middle = at.left + at.width / 2 - box.width / 2;
  const left = Math.min(Math.max(GAP, middle), window.innerWidth - box.width - GAP);
  frame.style.top = `${Math.round(top)}px`;
  frame.style.left = `${Math.round(left)}px`;
}

/** What the card can be asked to do, which is the session's business rather than the card's. */
export interface CardActions {
  /** Whether what the play button plays is a person rather than a machine. */
  recorded?: boolean;
  /** Say the word the card is about. */
  onPlay?: () => void;
  /** Play a recording of one sound, which is a file rather than a synthesised voice. */
  onPlayUrl?: (url: string) => void;
  /** A picture of the mouth making a sound, from wherever the host can reach it. */
  diagram?: (file: string) => Promise<string>;
  onOpen?: (url: string) => void;
}

/** Where the card is anchored, so it can be put back in place when it changes height. */
let anchor: DOMRect = new DOMRect();

/** Show one answer, anchored to the word it is about. */
export function show(answer: Answer, at: DOMRect, actions: CardActions = {}): void {
  anchor = at;
  const { frame: box } = build();
  const key = `${answer.spelling}:${answer.state}:${actions.recorded ?? false}`;
  if (drawn && about === key) {
    place(at);
    return;
  }
  hide();
  const { frame: fresh } = build();
  drawn = mount(Opened, {
    target: fresh,
    props: {
      answer,
      recorded: actions.recorded ?? false,
      diagram: actions.diagram,
      onPlay: actions.onPlay,
      onPlayUrl: actions.onPlayUrl,
      onOpen: actions.onOpen ?? ((url: string) => window.open(url, '_blank', 'noopener')),
      // A sheet opening under the card makes it taller, and a card that grew where it stood
      // can end up hanging off the bottom of the window.
      onSymbol: () => requestAnimationFrame(() => place(anchor)),
    },
  });
  about = key;
  void box;
  // Placed after it has drawn, since where it fits depends on how tall it turned out to be.
  requestAnimationFrame(() => place(at));
}

/** Take the card down. */
export function hide(): void {
  if (drawn) {
    unmount(drawn);
    drawn = null;
  }
  about = null;
}

/** Whether a card is on screen, for a gesture deciding whether to close one. */
export function showing(): boolean {
  return drawn !== null;
}

/** Whether an event happened inside the card, which must not dismiss it. */
export function inside(target: EventTarget | null): boolean {
  return host !== null && target instanceof Node && host.contains(target);
}
