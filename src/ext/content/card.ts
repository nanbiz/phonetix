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
import { darkHere } from './inline';
import { THEME, themeOf } from '@/ui/theme';
import { OURS } from './scan';

/** How far the card keeps from the edges of the window. */
const MARGIN = 8;

let host: HTMLElement | null = null;
let shadow: ShadowRoot | null = null;
let frame: HTMLElement | null = null;
let drawn: ReturnType<typeof mount> | null = null;
/** What the card on screen is about, so a second ask about the same word is not a redraw. */
let about: string | null = null;
/** Which word the card on screen is about, so an answer filled in later is told apart from a
 *  card for another word. */
let spelling = '';
/** The palette the reader chose, which every surface of ours is drawn in. */
let theme = THEME;
/** Which side of it, where the reader insisted rather than leaving it to the page. */
let side = 'system';
/**
 * Whether the reader has come into the card.
 *
 * Until then the card takes the pointer only on its arrow, and everywhere else the pointer
 * passes through it to the page: a reader moving from a word to the line underneath crosses
 * the card that word opened, and a card that caught the pointer there kept the next line out
 * of reach. Coming in through the arrow, or asking for the card outright, makes it a thing to
 * press and select out of like any other.
 */
let entered = false;

/** Draw in this palette from now on. */
export function paintedIn(chosen: string, lightOrDark = 'system'): void {
  if (chosen === theme && lightOrDark === side) return;
  theme = chosen;
  side = lightOrDark;
  if (frame) frame.className = themeOf(darkHere(), theme);
}

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
  frame.className = themeOf(darkHere(), theme);
  // As wide as what it says, up to the card's width: a one-word answer in a card sized for a
  // definition was mostly empty card. Passed through by the pointer until it is entered.
  frame.style.cssText =
    'position:fixed;width:max-content;min-width:220px;' +
    'max-width:min(var(--card-width), calc(100vw - 16px));pointer-events:none;';
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
  // The gap between the word and the card is the arrow's to span, point at the word and base
  // on the card, so the card stands off from its word by exactly as far as the arrow reaches
  // out of it: further and the pointer would have open page to cross on its way in, nearer and
  // the arrow would cover the word.
  const arrow = arrowSize();
  const below = at.bottom + arrow.reach;
  const above = at.top - arrow.reach - box.height;
  const under = below + box.height <= window.innerHeight;
  const top = under ? below : Math.max(MARGIN, above);
  const middle = at.left + at.width / 2 - box.width / 2;
  const left = Math.min(Math.max(MARGIN, middle), window.innerWidth - box.width - MARGIN);
  const placed = Math.round(left);
  frame.style.top = `${Math.round(top)}px`;
  frame.style.left = `${placed}px`;
  // Where the word is along the card's own width, so the arrow points at it rather than at
  // wherever the middle of the card happened to land: a card pushed against the side of the
  // window is nowhere near the word it belongs to. Never so near a side that the arrow would
  // hang off the card's rounded corner. Measured from inside the card's border, which is
  // where the arrow is positioned from, and not rounded, so its point is on the word's middle
  // rather than a pixel to one side.
  const card = frame.querySelector('.card');
  const border = card?.clientLeft ?? 0;
  const inset = arrow.width / 2 + MARGIN;
  const pointsAt = Math.min(
    Math.max(inset, at.left + at.width / 2 - placed - border),
    Math.max(inset, box.width - inset)
  );
  frame.style.setProperty('--arrow-at', `${pointsAt.toFixed(1)}px`);
  if (card) {
    card.classList.toggle('below', under);
    card.classList.toggle('above', !under);
  }
}

/**
 * How wide the arrow is, and how far it reaches out of the card.
 *
 * Measured rather than repeated here, because the stylesheet decides its size and lays its base
 * over the card's border; whichever side of the card it is on, what lies outside the card is
 * the part that spans the gap.
 */
function arrowSize(): { width: number; reach: number } {
  const arrow = frame?.querySelector('.card-arrow');
  const card = frame?.querySelector('.card');
  if (!arrow || !card) return { width: 0, reach: MARGIN };
  const wedge = arrow.getBoundingClientRect();
  const body = card.getBoundingClientRect();
  const overlap = Math.max(
    0,
    Math.min(wedge.bottom, body.bottom) - Math.max(wedge.top, body.top)
  );
  return { width: wedge.width, reach: wedge.height - overlap };
}

/** Lift the card just enough to keep its bottom edge inside the window. */
function keepInWindow(): void {
  if (!frame) return;
  const box = frame.getBoundingClientRect();
  const over = box.bottom - (window.innerHeight - MARGIN);
  if (over > 0) frame.style.top = `${Math.round(Math.max(MARGIN, box.top - over))}px`;
}

/** What the card can be asked to do, which is the session's business rather than the card's. */
export interface CardActions {
  /** Whether what the play button plays is a person rather than a machine. */
  recorded?: boolean;
  /** The accent this language is being read in, which the card names beside the word. */
  accent?: string;
  /** Whether the card eases in rather than appearing in place. */
  eased?: boolean;
  /** Say the word the card is about. */
  onPlay?: () => void;
  /** Play a recording of one sound, which is a file rather than a synthesised voice. */
  onPlayUrl?: (url: string) => void;
  /** A picture of the mouth making a sound, from wherever the host can reach it. */
  diagram?: (file: string) => Promise<string>;
  onOpen?: (url: string) => void;
  /** How far the dictionary for the word's language has got, where it is on its way. */
  arriving?: number | null;
  /** Whether the card takes the pointer from the start rather than from its arrow: one opened
   *  by a tap or for a selection was asked for outright, and there is no hover to come in by. */
  entered?: boolean;
}

/** Where the card is anchored, so it can be put back in place when it changes height. */
let anchor: DOMRect = new DOMRect();

/** Show one answer, anchored to the word it is about. */
export function show(answer: Answer, at: DOMRect, actions: CardActions = {}): void {
  anchor = at;
  const { frame: box } = build();
  const key = JSON.stringify([
    answer.spelling, answer.state, answer.ipa[0], answer.says[0], actions.recorded ?? false,
    actions.accent ?? '', actions.eased ?? false,
    actions.arriving == null ? null : Math.round(actions.arriving * 100),
  ]);
  if (drawn && about === key) {
    place(at);
    return;
  }
  // The same word with more found out about it is the same card filled in: it neither eases
  // in a second time nor leaves the word it is under.
  const filling = drawn !== null && spelling === answer.spelling;
  // And a card the reader had already come into stays one they are in.
  const stayIn = (filling && entered) || (actions.entered ?? false);
  hide();
  spelling = answer.spelling;
  const { frame: fresh } = build();
  drawn = mount(Opened, {
    target: fresh,
    props: {
      answer,
      recorded: actions.recorded ?? false,
      accent: actions.accent ?? '',
      eased: !filling && (actions.eased ?? false),
      // Anchored to a word, so it says which one it is about.
      points: 'below',
      diagram: actions.diagram,
      onPlay: actions.onPlay,
      onPlayUrl: actions.onPlayUrl,
      onOpen: actions.onOpen ?? ((url: string) => window.open(url, '_blank', 'noopener')),
      arriving: actions.arriving ?? null,
      // A sound described makes the card taller. It grows where it stands, so the symbol the
      // reader just pressed stays under the pointer, and moves up only as far as the window's
      // bottom edge makes it.
      onSymbol: () => requestAnimationFrame(keepInWindow),
    },
  });
  about = key;
  void box;
  if (stayIn) enter();
  // Over the arrow is coming in, whichever way the pointer got there: the arrow is the only
  // part of a card not yet entered that the pointer can be over at all.
  fresh.querySelector('.card-arrow')?.addEventListener('pointerover', enter);
  // Placed as soon as it is drawn, since where it fits depends on how tall it turned out to
  // be, and before the frame is painted: placed a frame later, a card showed for one frame
  // wherever the last one had been and then jumped to its word. Again on the next frame, for
  // anything the card lays out late.
  place(at);
  requestAnimationFrame(() => place(anchor));
}

/**
 * Put the card back over the word it is about, wherever that word is now.
 *
 * A page scrolls under a card that is positioned against the window, so without this the card
 * stays where it was drawn and points at whatever has scrolled into that spot.
 */
export function moveTo(at: DOMRect): void {
  if (!drawn) return;
  anchor = at;
  place(at);
}

/** Take the card down. */
export function hide(): void {
  if (drawn) {
    // Nothing to wait for: the card has no transition out.
    void unmount(drawn);
    drawn = null;
  }
  about = null;
  entered = false;
  if (frame) frame.style.pointerEvents = 'none';
}

/** Let the card take the pointer, because the reader came into it. */
function enter(): void {
  entered = true;
  if (frame) frame.style.pointerEvents = 'auto';
}

/**
 * Whether a point is on a card the reader has not come into.
 *
 * A pointer there got past the word some other way than through the arrow, so it is on its
 * way to whatever the card covers rather than into the card, and the card should be gone
 * before it hides that from the reader.
 */
export function passedOver(from: Point, to: Point): boolean {
  if (!drawn || entered || !frame) return false;
  const card = frame.querySelector('.card');
  if (!card) return false;
  const box = card.getBoundingClientRect();
  if (to.x < box.left || to.x > box.right || to.y < box.top || to.y > box.bottom) return false;
  // A quick hand moves further between two reports than the arrow is tall, and lands in the
  // card having crossed the arrow without ever being reported on it. Where the straight line
  // between the two reports crosses the card's edge says which way it came in.
  const arrow = frame.querySelector('.card-arrow')?.getBoundingClientRect();
  const edge = card.classList.contains('above') ? box.bottom : box.top;
  if (arrow && from.y !== to.y && (from.y - edge) * (to.y - edge) <= 0) {
    const x = from.x + ((to.x - from.x) * (edge - from.y)) / (to.y - from.y);
    if (x >= arrow.left && x <= arrow.right) {
      enter();
      return false;
    }
  }
  return true;
}

/** A place in the window, in the coordinates pointer events report. */
export interface Point {
  x: number;
  y: number;
}

/** Whether a card is on screen, for a gesture deciding whether to close one. */
export function showing(): boolean {
  return drawn !== null;
}

/** Whether an event happened inside the card, which must not dismiss it. */
export function inside(target: EventTarget | null): boolean {
  return host !== null && target instanceof Node && host.contains(target);
}
