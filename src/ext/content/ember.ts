// The ember: a small glow that goes with the pointer while Phonetix is on, and goes into the
// word the pointer is on.
//
// It says where a card would come from before it comes: on a page where every word can be
// asked about, the pointer itself gives no sign of which word that is. It trails the pointer
// a little below it, and over a word it flies into that word, which takes a soft light; from
// word to word the light slides rather than jumping, the way the phone's mark moves between
// words, because a mark that jumps is read as several marks appearing.
//
// Drawn in a shadow root of its own that never takes the pointer, so the page under it is the
// page, and nothing here decides which word is under the pointer: that is the reader's code
// in index.ts, which tells this where to be.
import tokenCss from '@/ui/tokens.css?inline';
import { darkHere } from './inline';
import { THEME, themeOf } from '@/ui/theme';
import { OURS } from './scan';

/** How far below the pointer the ember trails while it is on no word, in pixels: under the
 *  arrow's point rather than over the text it points at. */
const BELOW = 18;
/** How much of the way to where it is going it covers each frame: a trail, not a lag. */
const EASE = 0.3;

const CSS = `
  .ember-frame { position: fixed; inset: 0; pointer-events: none; }
  .ember {
    position: fixed; left: 0; top: 0; width: 10px; height: 10px; margin: -5px 0 0 -5px;
    opacity: 0; transition: opacity 130ms ease-out;
  }
  .ember-core {
    width: 100%; height: 100%; border-radius: 50%;
    background: radial-gradient(circle at 50% 55%,
      color-mix(in srgb, var(--color-accent) 25%, white) 0%,
      var(--color-accent) 45%,
      color-mix(in srgb, var(--color-accent) 60%, #e0400a) 70%,
      transparent 100%);
    box-shadow: 0 0 6px 2px color-mix(in srgb, var(--color-accent) 70%, transparent),
      0 0 14px 5px color-mix(in srgb, var(--color-accent) 30%, transparent);
    animation: burn 1.4s ease-in-out infinite alternate;
  }
  .ember.on { opacity: 1; }
  .ember.on.in { opacity: 0; transition: opacity 140ms ease-in 70ms; }
  .ember-word {
    position: fixed; left: 0; top: 0; border-radius: 6px; opacity: 0;
    background: color-mix(in srgb, var(--color-accent) 16%, transparent);
    box-shadow: 0 0 10px 1px color-mix(in srgb, var(--color-accent) 22%, transparent);
    transition: opacity 130ms ease-out, transform 150ms cubic-bezier(0.2, 0.8, 0.3, 1),
      width 150ms cubic-bezier(0.2, 0.8, 0.3, 1), height 150ms cubic-bezier(0.2, 0.8, 0.3, 1);
  }
  .ember-word.on { opacity: 1; }
  .still .ember-core { animation: none; }
  .still .ember, .still .ember-word { transition: none; }
  @keyframes burn {
    0% { transform: scale(0.92); filter: brightness(0.95); }
    40% { transform: scale(1.06); filter: brightness(1.12); }
    70% { transform: scale(0.98); filter: brightness(1.02); }
    100% { transform: scale(1.1); filter: brightness(1.18); }
  }
  @media (prefers-reduced-motion: reduce) {
    .ember-core { animation: none; }
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
  const core = document.createElement('div');
  core.className = 'ember-core';
  ball.appendChild(core);
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
 * Be at the pointer, at ([x], [y]), or settle under [word] where the pointer is on one.
 */
export function emberAt(x: number, y: number, word: DOMRect | null): void {
  build();
  // Into the word it is on, where it goes out as the word lights: resting under the word it
  // sat on the line below, over the words being read there.
  goal = word ? { x: word.left + word.width / 2, y: word.top + word.height / 2 } : { x, y: y + BELOW };
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
