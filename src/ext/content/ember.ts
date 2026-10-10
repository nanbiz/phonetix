// The ember: a small fireball that goes with the pointer while Phonetix is on, and goes into the
// word the pointer is on.
//
// It says where a card would come from before it comes: on a page where every word can be
// asked about, the pointer itself gives no sign of which word that is. It trails the arrow's
// tip, and over a word it flies into that word, which takes a soft light; from word to word the
// light slides rather than jumping, the way the phone's mark moves between words, because a
// mark that jumps is read as several marks appearing.
//
// Drawn in a shadow root of its own that never takes the pointer, so the page under it is the
// page, and nothing here decides which word is under the pointer: that is the reader's code
// in index.ts, which tells this where to be.
import tokenCss from '@/ui/tokens.css?inline';
import { darkHere } from './inline';
import { THEME, themeOf } from '@/ui/theme';
import { OURS } from './scan';

/** How much of the way to where it is going it covers each frame: a trail, not a lag. */
const EASE = 0.3;

// A small fireball with no fixed outline: a few blurred flames of different heat, each drifting
// and swelling round the middle on a rhythm of its own, so their sum keeps changing shape and
// never settles into one visible loop. The outer element's transform is its position, so every
// animation runs on the flames inside it.
const CSS = `
  .ember-frame { position: fixed; inset: 0; pointer-events: none; }
  .ember {
    position: fixed; left: 0; top: 0; width: 20px; height: 20px; margin: -10px 0 0 -10px;
    opacity: 0; transition: opacity 200ms ease-out;
  }
  .ember > * { position: absolute; filter: blur(1.4px); }
  .flame-red {
    inset: 2px 1px 1px 3px; border-radius: 62% 38% 55% 45% / 48% 60% 40% 52%;
    background: radial-gradient(circle at 45% 55%, rgb(235 50 0 / 0.95) 0%,
      rgb(200 30 0 / 0.5) 55%, transparent 75%);
    animation: drift-a 1.3s ease-in-out infinite alternate, turn 2.9s linear infinite;
  }
  .flame-orange {
    inset: 3px 4px 4px 2px; border-radius: 40% 60% 45% 55% / 60% 42% 58% 40%;
    background: radial-gradient(circle at 55% 45%, #ff8a00 0%, rgb(255 110 0 / 0.65) 50%,
      transparent 75%);
    animation: drift-b 0.83s ease-in-out infinite alternate, turn 2.1s linear infinite reverse;
  }
  .flame-amber {
    inset: 5px 3px 3px 5px; border-radius: 55% 45% 62% 38% / 42% 58% 45% 55%;
    background: radial-gradient(circle, var(--color-accent) 0%,
      color-mix(in srgb, var(--color-accent) 55%, transparent) 50%, transparent 75%);
    animation: drift-c 1.07s ease-in-out infinite alternate, turn 1.7s linear infinite;
  }
  .flame-core {
    inset: 7px; border-radius: 50%; filter: blur(0.8px);
    background: radial-gradient(circle, #fffbe6 0%, #ffe066 45%, transparent 75%);
    animation: flicker 0.37s ease-in-out infinite alternate, drift-b 0.61s ease-in-out infinite
      alternate-reverse;
  }
  .ember.on { opacity: 1; }
  .ember.on.in { opacity: 0; transition: opacity 200ms ease-in 40ms; }
  .ember-word {
    position: fixed; left: 0; top: 0; border-radius: 6px; opacity: 0;
    background: color-mix(in srgb, var(--color-accent) 16%, transparent);
    box-shadow: 0 0 10px 1px color-mix(in srgb, var(--color-accent) 22%, transparent);
    transition: opacity 130ms ease-out, transform 150ms cubic-bezier(0.2, 0.8, 0.3, 1),
      width 150ms cubic-bezier(0.2, 0.8, 0.3, 1), height 150ms cubic-bezier(0.2, 0.8, 0.3, 1);
  }
  .ember-word.on { opacity: 1; }
  .still .ember > * { animation: none; }
  .still .ember, .still .ember-word { transition: none; }
  @keyframes turn { to { rotate: 360deg; } }
  @keyframes drift-a {
    0% { translate: -3px 1.5px; scale: 0.85 1.1; }
    50% { translate: 2px -3px; scale: 1.15 0.9; }
    100% { translate: 1px 2.5px; scale: 0.95 1.2; }
  }
  @keyframes drift-b {
    0% { translate: 2.5px 1px; scale: 1.15 0.85; }
    60% { translate: -2px -2px; scale: 0.88 1.15; }
    100% { translate: -2.5px 2px; scale: 1.2 1; }
  }
  @keyframes drift-c {
    0% { translate: 0 -2.5px; scale: 0.9; }
    45% { translate: 2.5px 1.5px; scale: 1.15 0.92; }
    100% { translate: -2px 1px; scale: 1 1.15; }
  }
  @keyframes flicker {
    0% { scale: 0.85; opacity: 0.85; }
    100% { scale: 1.1; opacity: 1; }
  }
  @media (prefers-reduced-motion: reduce) {
    .ember > * { animation: none; }
    .ember, .ember-word { transition: none; }
  }
`;

let frame: HTMLElement | null = null;
let ball: HTMLElement | null = null;
let light: HTMLElement | null = null;
let theme = THEME;
/** Whether it moves at once rather than easing: the reader's own setting, or the device's. */
let still = false;
/** Where it is drawn, and where it is going. */
let at: { x: number; y: number } | null = null;
let goal: { x: number; y: number } | null = null;
let frameAsked = 0;
/** The word it last lit, so the same word is not lit again every frame. */
let lit: DOMRect | null = null;

function build(): void {
  if (frame) return;
  const host = document.createElement('div');
  host.id = `${OURS}-ember`;
  host.style.cssText = 'position:fixed;inset:0 auto auto 0;width:0;height:0;z-index:2147483645;' +
    'pointer-events:none;';
  document.documentElement.appendChild(host);
  const shadow = host.attachShadow({ mode: 'open' });
  const style = document.createElement('style');
  style.textContent = `${tokenCss}\n${CSS}`;
  shadow.appendChild(style);
  frame = document.createElement('div');
  shadow.appendChild(frame);
  light = document.createElement('div');
  light.className = 'ember-word';
  ball = document.createElement('div');
  ball.className = 'ember';
  for (const layer of ['flame-red', 'flame-orange', 'flame-amber', 'flame-core']) {
    const part = document.createElement('div');
    part.className = layer;
    ball.appendChild(part);
  }
  frame.append(light, ball);
  paint();
}

function paint(): void {
  if (frame) frame.className = `ember-frame ${themeOf(darkHere(), theme)}${still ? ' still' : ''}`;
}

/** Draw in this palette, and move at once rather than easing where the reader asked for no
 *  animation. */
export function emberPaintedIn(chosen: string, animated: boolean): void {
  theme = chosen || THEME;
  still = !animated;
  paint();
}

/**
 * Be at the pointer's tip, at ([x], [y]), or go into [word] where the pointer is on one.
 */
export function emberAt(x: number, y: number, word: DOMRect | null): void {
  build();
  // Into the word it is on, where it goes out as the word lights: resting under the word it
  // sat on the line below, over the words being read there.
  goal = word ? { x: word.left + word.width / 2, y: word.top + word.height / 2 } : { x, y };
  if (!at || still) at = { ...goal };
  ball?.classList.add('on');
  ball?.classList.toggle('in', word !== null);
  lightWord(word);
  if (!frameAsked) frameAsked = requestAnimationFrame(step);
}

/** Put it away: the pointer has left the page, or is on something of ours. */
export function emberAway(): void {
  ball?.classList.remove('on', 'in');
  lightWord(null);
  at = null;
  goal = null;
}

function lightWord(word: DOMRect | null): void {
  if (!light) return;
  if (!word) {
    light.classList.remove('on');
    lit = null;
    return;
  }
  if (lit && lit.left === word.left && lit.top === word.top && lit.width === word.width) return;
  // Grown a little past the word, so the light reads as being around it rather than on it.
  const pad = 3;
  light.style.width = `${Math.round(word.width + pad * 2)}px`;
  light.style.height = `${Math.round(word.height + pad * 2)}px`;
  light.style.transform = `translate(${Math.round(word.left - pad)}px, ${Math.round(word.top - pad)}px)`;
  light.classList.add('on');
  lit = word;
}

function step(): void {
  frameAsked = 0;
  if (!ball || !at || !goal) return;
  at = { x: at.x + (goal.x - at.x) * EASE, y: at.y + (goal.y - at.y) * EASE };
  const close = Math.abs(goal.x - at.x) < 0.5 && Math.abs(goal.y - at.y) < 0.5;
  if (close) at = { ...goal };
  ball.style.transform = `translate(${at.x.toFixed(1)}px, ${at.y.toFixed(1)}px)`;
  if (!close) frameAsked = requestAnimationFrame(step);
}
