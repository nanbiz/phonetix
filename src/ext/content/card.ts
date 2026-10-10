// The card, over the page.
//
// It lives in a shadow root of its own so that a page's stylesheet cannot reach it and its
// own cannot reach the page. What it draws is the same component the viewbook draws, from the
// same Answer the phone's card is drawn from.
import { flushSync, mount, unmount, type ComponentProps } from 'svelte';

import Opened from '@/ui/card/Opened.svelte';
import { atDetail, type Answer } from '@/core/answer';
import cardCss from '@/ui/card/card.css?inline';
import tokenCss from '@/ui/tokens.css?inline';
import { darkHere } from './inline';
import { THEME, themeOf } from '@/ui/theme';
import { OURS } from './scan';
import { reactive } from './props.svelte';

/** How wide a card over a page may grow, in CSS pixels. */
const PAGE_CARD_WIDTH = 440;

/** How far the card keeps from the edges of the window. */
const MARGIN = 8;

let host: HTMLElement | null = null;
let shadow: ShadowRoot | null = null;
let frame: HTMLElement | null = null;
let drawn: ReturnType<typeof mount> | null = null;
/** What the card on screen was drawn with, which a card filled in is updated through. */
let props: ComponentProps<typeof Opened> | null = null;
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
  // Wider over a page than the phone's: a long compound and the card's two buttons share a
  // line, and its transcription and accent another, where at the phone's width both broke.
  frame.style.cssText =
    `--card-width:${PAGE_CARD_WIDTH}px;position:fixed;width:max-content;min-width:220px;` +
    'max-width:min(var(--card-width), calc(100vw - 16px));pointer-events:none;';
  shadow.appendChild(frame);
  return { shadow, frame };
}

/** The sides of its word a card can stand on, named for where the card is. */
type Side = 'below' | 'above' | 'left' | 'right';
const SIDES: Side[] = ['below', 'above', 'left', 'right'];

/** Where a finger is on the screen while it holds the side button, or nothing with no finger
 *  down: a card put under the hand that is asking is a card nobody can read. */
let hand: Point | null = null;

/** How far round a finger is kept clear, and how far round a word the ring drawn on it
 *  reaches, as the phone has them. */
const HAND = 36;
const RING = 30;

/**
 * The part of the page the reader can see, in the coordinates a fixed box is placed in: the
 * visual viewport, which is the window less a keyboard and, zoomed in, a part of it.
 */
export function seen(): DOMRect {
  const v = window.visualViewport;
  return v
    ? new DOMRect(v.offsetLeft, v.offsetTop, v.width, v.height)
    : new DOMRect(0, 0, window.innerWidth, window.innerHeight);
}

/** Where the card is, for the side button's actions to keep clear of; nothing with no card. */
export function cardBox(): DOMRect | null {
  return drawn && frame ? frame.getBoundingClientRect() : null;
}

/** Say where the finger holding the side button is, or that there is none. A card the finger
 *  has come onto moves out from under it; one it is clear of stays where it is, since a card
 *  that shifted with every movement of the hand could not be read. */
export function handAt(at: Point | null): void {
  hand = at;
  if (!at || !drawn || !frame) return;
  const box = frame.getBoundingClientRect();
  if (covers(box, new DOMRect(at.x - HAND, at.y - HAND, HAND * 2, HAND * 2)) > 0) place(anchor);
}

/** The side of its word the card is on now. */
function sideOf(card: Element | null): Side {
  return SIDES.find((side) => card?.classList.contains(side)) ?? 'below';
}

/** How much of one box lies over another, as an area. */
function covers(a: DOMRect, b: DOMRect | null): number {
  if (!b) return 0;
  const w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
  const h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
  return w > 0 && h > 0 ? w * h : 0;
}

/**
 * Put the card beside its word, the way the phone does: on the side away from the hand first,
 * then the other side, then to the left or the right of the word; the first of those that is
 * inside the window and covers neither the word nor the finger. With no finger down the word's
 * place in the window decides instead, a word low in it answered above. Where none is clear,
 * the one that covers least, the word counting four times the finger.
 *
 * Measured after it is drawn rather than guessed, because how tall it is depends on what the
 * word turned out to be.
 */
function place(at: DOMRect): void {
  if (!frame) return;
  // No wider than what the reader can see, which on a phone is less than the page is wide.
  frame.style.maxWidth = `${Math.round(Math.min(PAGE_CARD_WIDTH, seen().width - MARGIN * 2))}px`;
  const box = frame.getBoundingClientRect();
  const w = box.width;
  const h = box.height;
  // The gap between the word and the card is the arrow's to span, point at the word and base
  // on the card, so the card stands off from its word by exactly as far as the arrow reaches
  // out of it: further and the pointer would have open page to cross on its way in, nearer and
  // the arrow would cover the word.
  const arrow = arrowSize();
  const gap = arrow.reach;
  // Under a finger the word has the ring drawn round it, which is wider than a short word.
  const cx = at.left + at.width / 2;
  const cy = at.top + at.height / 2;
  const word = hand
    ? new DOMRect(
        cx - Math.max(at.width / 2, RING), cy - Math.max(at.height / 2, RING),
        Math.max(at.width, RING * 2), Math.max(at.height, RING * 2))
    : at;
  const palm = hand ? new DOMRect(hand.x - HAND, hand.y - HAND, HAND * 2, HAND * 2) : null;
  const view = seen();
  const lo = view.left + MARGIN;
  const hi = view.right - w - MARGIN;
  const top = view.top + MARGIN;
  const centred = Math.min(Math.max(lo, cx - w / 2), Math.max(lo, hi));
  const middle = Math.max(top, cy - h / 2);
  const spots: Record<Side, DOMRect> = {
    below: new DOMRect(centred, word.bottom + gap, w, h),
    above: new DOMRect(centred, word.top - gap - h, w, h),
    left: new DOMRect(word.left - gap - w, middle, w, h),
    right: new DOMRect(word.right + gap, middle, w, h),
  };
  const handBelow = palm !== null && palm.top + HAND > cy;
  const order: Side[] = handBelow || (palm === null && cy > view.top + view.height / 2)
    ? ['above', 'below', 'left', 'right']
    : ['below', 'above', 'left', 'right'];
  const inside = (r: DOMRect) => r.left >= lo && r.top >= top &&
    r.right <= view.right - MARGIN && r.bottom <= view.bottom - MARGIN;
  let side = order.find((s) => inside(spots[s]) && covers(spots[s], word) === 0 &&
    covers(spots[s], palm) === 0);
  let spot = side ? spots[side] : null;
  if (!side && palm) {
    // Nothing beside the word is clear of the hand: past the hand, on the side of it away from
    // the word, where the card is in sight and the thumb is not on it.
    const past: Side = handBelow ? 'below' : 'above';
    const there = new DOMRect(
      centred, handBelow ? palm.bottom + gap : palm.top - gap - h, w, h);
    if (inside(there) && covers(there, word) === 0) {
      side = past;
      spot = there;
    }
  }
  if (!side || !spot) {
    // Nothing is clear: kept in the window, covering as little as can be had.
    let least = Infinity;
    for (const s of order) {
      const r = spots[s];
      const kept = new DOMRect(
        Math.min(Math.max(lo, r.left), Math.max(lo, hi)),
        Math.min(Math.max(top, r.top), Math.max(top, view.bottom - h - MARGIN)), w, h);
      const cost = covers(kept, word) * 4 + covers(kept, palm);
      if (cost < least) {
        least = cost;
        side = s;
        spot = kept;
      }
    }
  }
  const placedLeft = Math.round(spot!.left);
  const placedTop = Math.round(spot!.top);
  frame.style.top = `${placedTop}px`;
  frame.style.left = `${placedLeft}px`;
  // Where the word is along the card's edge, so the arrow points at it rather than at wherever
  // the middle of the card happened to land: a card pushed against the side of the window is
  // nowhere near the word it belongs to. Never so near a corner that the arrow would hang off
  // the card's rounded corner. Measured from inside the card's border, which is where the
  // arrow is positioned from, and not rounded, so its point is on the word's middle rather than
  // a pixel to one side.
  const card = frame.querySelector('.card');
  const across = side === 'left' || side === 'right';
  const border = across ? card?.clientTop ?? 0 : card?.clientLeft ?? 0;
  const length = across ? h : w;
  const inset = arrow.width / 2 + MARGIN;
  const wanted = across ? cy - placedTop - border : cx - placedLeft - border;
  const pointsAt = Math.min(Math.max(inset, wanted), Math.max(inset, length - inset));
  frame.style.setProperty('--arrow-at', `${pointsAt.toFixed(1)}px`);
  if (card) for (const s of SIDES) card.classList.toggle(s, s === side);
}

/**
 * How wide the arrow is along the card's edge, and how far it reaches out of the card.
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
  const out = Math.max(body.top - wedge.top, wedge.bottom - body.bottom,
    body.left - wedge.left, wedge.right - body.right, 0);
  return { width: Math.max(wedge.width, wedge.height), reach: out };
}

/** Lift the card just enough to keep its bottom edge inside the window. */
function keepInWindow(): void {
  if (!frame) return;
  const box = frame.getBoundingClientRect();
  const view = seen();
  const over = box.bottom - (view.bottom - MARGIN);
  if (over > 0) frame.style.top = `${Math.round(Math.max(view.top + MARGIN, box.top - over))}px`;
}

/**
 * Keep a card that changed height on its side of its word: one over the word keeps its bottom
 * edge where it was and grows upward, one under it or beside it grows downward; either stays in
 * the window.
 */
function regrow(): void {
  if (!frame) return;
  if (sideOf(frame.querySelector('.card')) === 'above') {
    const box = frame.getBoundingClientRect();
    const reach = arrowSize().reach;
    frame.style.top = `${Math.round(Math.max(seen().top + MARGIN, anchor.top - reach - box.height))}px`;
    return;
  }
  keepInWindow();
}

/** What the card can be asked to do, which is the session's business rather than the card's. */
export interface CardActions {
  /** Whether what the play button plays is a person rather than a machine. */
  recorded?: boolean;
  /** The accent this language is being read in, which the card names beside the word. */
  accent?: string;
  /** Whether the card eases in rather than appearing in place. */
  eased?: boolean;
  /** Whether the reader asked for narrow transcriptions. */
  narrow?: boolean;
  /** Say the word the card is about. */
  /** Play the word; resolves once it has been heard. */
  onPlay?: () => Promise<void> | void;
  /** Read the word's language in another accent from now on. */
  onAccent?: (accent: string) => void;
  /** Play a recording of one sound, which is a file rather than a synthesised voice. */
  onPlayUrl?: (url: string) => void;
  /** A picture of the mouth making a sound, from wherever the host can reach it. */
  diagram?: (file: string) => Promise<string>;
  onOpen?: (url: string) => void;
  /** How far the dictionary for the word's language has got, where it is on its way. */
  arriving?: number | null;
  /** Look a word up the way the card's own word was: its lemma, and its other forms. */
  lookUp?: (word: string) => Promise<Answer | null>;
  /** Whether the card takes the pointer from the start rather than from its arrow: one opened
   *  by a tap or for a selection was asked for outright, and there is no hover to come in by. */
  entered?: boolean;
}

/** Where the card is anchored, so it can be put back in place when it changes height. */
let anchor: DOMRect = new DOMRect();

/** Show one answer, anchored to the word it is about. */
export function show(given: Answer, at: DOMRect, actions: CardActions = {}): void {
  const answer = atDetail(given, actions.narrow ?? false);
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
  const next = {
    answer,
    recorded: actions.recorded ?? false,
    accent: actions.accent ?? '',
    diagram: actions.diagram,
    onPlay: actions.onPlay,
    onAccent: actions.onAccent,
    onPlayUrl: actions.onPlayUrl,
    onOpen: actions.onOpen ?? ((url: string) => window.open(url, '_blank', 'noopener')),
    arriving: actions.arriving ?? null,
    lookUp: actions.lookUp,
  };
  if (filling && props) {
    // Filled in where it is, keeping what the reader has open on it, on the side of its word
    // it is already on.
    // Drawn now and measured now, like a card drawn fresh: a card that waited for the next
    // frame to be put back in place sat over its word in a tab that frame was slow to come to.
    flushSync(() => Object.assign(props!, next));
    about = key;
    if (actions.entered) enter();
    regrow();
    requestAnimationFrame(regrow);
    return;
  }
  const stayIn = actions.entered ?? false;
  hide();
  spelling = answer.spelling;
  const { frame: fresh } = build();
  props = reactive({
    ...next,
    eased: actions.eased ?? false,
    // Anchored to a word, so it says which one it is about.
    points: 'below' as const,
    // A sound described makes the card taller. It grows where it stands, so the symbol the
    // reader just pressed stays under the pointer, and moves up only as far as the window's
    // bottom edge makes it.
    onSymbol: () => requestAnimationFrame(keepInWindow),
    // Reading as another form or as the lemma's entry changes how tall the card is. It keeps
    // the side of its word it is on and the edge nearest that word, and grows away from it.
    onGrow: () => requestAnimationFrame(regrow),
  });
  drawn = mount(Opened, { target: fresh, props });
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
  props = null;
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
  const side = sideOf(card);
  if (arrow && (side === 'above' || side === 'below')) {
    const edge = side === 'above' ? box.bottom : box.top;
    if (from.y !== to.y && (from.y - edge) * (to.y - edge) <= 0) {
      const x = from.x + ((to.x - from.x) * (edge - from.y)) / (to.y - from.y);
      if (x >= arrow.left && x <= arrow.right) {
        enter();
        return false;
      }
    }
  } else if (arrow) {
    const edge = side === 'left' ? box.right : box.left;
    if (from.x !== to.x && (from.x - edge) * (to.x - edge) <= 0) {
      const y = from.y + ((to.y - from.y) * (edge - from.x)) / (to.x - from.x);
      if (y >= arrow.top && y <= arrow.bottom) {
        enter();
        return false;
      }
    }
  }
  return true;
}

/**
 * Whether a point is on the way from the word into the card: over the arrow, a little either
 * side of it, or in the gap between it and the word. A card that closes the moment the pointer
 * leaves its word has to let the pointer cross that gap.
 */
export function onTheWayIn(to: Point): boolean {
  if (!drawn || !frame) return false;
  const card = frame.querySelector('.card');
  const arrow = frame.querySelector('.card-arrow')?.getBoundingClientRect();
  if (!card || !arrow) return false;
  const slack = 8;
  // The slack runs towards the word only, which is the side of the arrow away from the card.
  const side = sideOf(card);
  const top = arrow.top - (side === 'below' ? slack : side === 'above' ? 0 : slack);
  const bottom = arrow.bottom + (side === 'above' ? slack : side === 'below' ? 0 : slack);
  const left = arrow.left - (side === 'right' ? slack : side === 'left' ? 0 : slack);
  const right = arrow.right + (side === 'left' ? slack : side === 'right' ? 0 : slack);
  return to.x >= left && to.x <= right && to.y >= top && to.y <= bottom;
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
