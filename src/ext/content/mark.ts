// The side button: what a finger asks with on a touch screen, where there is no pointer to rest.
//
// The phone's own, drawn on a page (android HoverController, HoverViews and MistView, which this
// follows step for step). It waits at the side the reader keeps it on, low, where a thumb holding
// the phone already is. Dragged, it stays under the thumb that picked it up, and the circle that
// does the looking rides a clear distance further out on a weighted leash, joined to it by a
// thread of light, so the word being asked about is never under the hand asking. A ring settles
// on the word that circle is over and the card answers that word while the finger is down; the
// card goes when it lifts, since nothing on it can be touched by a finger that is dragging.
//
// The other gestures are the phone's too: a press that goes nowhere asks for the translator, a
// press held first and then dragged sweeps a clause, a press held and let go where it started
// opens the settings, and the button dragged onto the target that rises at the foot of the
// screen puts Phonetix away. Kept still on one word for a second, the finger is offered what
// the card would do with that word, around itself: slid onto one and lifted, it is done; lifted
// anywhere else, nothing is.
//
// Drawn in a shadow root of its own. Only the button and, while it is open, the ring of actions
// take the finger; nothing here decides which word is under the circle, which is the reader's
// code in index.ts, asked where the circle is.
import { mount } from 'svelte';
import tokenCss from '@/ui/tokens.css?inline';
import markPng from '../../../android/app/src/main/res/mipmap-xxxhdpi/ic_mark.png?inline';
import logoPng from '../../../android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png?inline';
import PLAY from 'virtual:icons/lucide/play';
import WIKTIONARY from 'virtual:icons/ooui/logo-wiktionary';
import { darkHere } from './inline';
import { THEME, themeOf } from '@/ui/theme';
import { OURS } from './scan';
import { cardBox, seen } from './card';

/** A word the circle is over: where it is, and what tells it from every other word. */
export interface MarkWord {
  rect: DOMRect;
  key: string;
}

/** What one of the actions offered around the finger does. */
export type MarkAction = 'play' | 'article';

/** What the reader's code is told, and asked. */
export interface MarkHands {
  /** The word at ([x], [y]), given the one the circle is already on, which is kept until the
   *  point is plainly elsewhere. Nothing is opened by asking. */
  wordAt(x: number, y: number, current: MarkWord | null): MarkWord | null;
  /** The circle is on this word, or on none: the card answers it, or goes. */
  onWord(word: MarkWord | null): void;
  /** Where the finger is, so a card opens clear of it, or that it has gone. */
  onHand(at: { x: number; y: number } | null): void;
  /** A swept run of words, asked as one while the finger is still down. */
  onRun(words: MarkWord[]): void;
  /** A press that went nowhere: the translator. */
  tapped(): void;
  /** A press held and let go where it started: the settings. */
  held(): void;
  /** Let go on the target at the foot: Phonetix put away. */
  putAway(): void;
  /** One of the actions offered around the finger, chosen for [word]. Called inside the
   *  finger's own lifting, which is what lets a sound play and a page open. */
  chose(action: MarkAction, word: MarkWord): void;
}

/** Where the reader keeps the button, as the settings say. */
export interface MarkPlace {
  /** "right", "left", or "free" for wherever it was let go. */
  side: string;
  /** Whether it waits at [restY] rather than down by the hand. */
  pin: boolean;
  /** How far down its side it waits when pinned, as a share of the window. */
  restY: number;
}

/** The button's width. */
const MARK = 40;
/** How far in from the edge it rests. */
const EDGE = 8;
/** How much of the foot of the window the system's own bar takes. */
const FOOT = 24;
/** Where it waits, as a share of the window's height: down by the hand, without being in the
 *  corner the hand comes in at. */
const HOME_DOWN = 0.8;
/** How far from where it waits the carry reaches its full length, as a share of the window. */
const GROWS_WITHIN = 0.2;
/** What a fingertip covers when the screen will not say: a finger pad is around 11mm across. */
const FINGER = 64;
/** How far above the hand the circle rides, as a multiple of its own width, on top of half the
 *  fingertip and its own radius. */
const CLEARANCE = 1.1;
/** How hard the thread pulls the ball towards where the hand holds it, how quickly the swing
 *  dies away, and the weight on the end of it, in pixels a second a second. */
const STIFFNESS = 260;
const DAMPING = 18;
const GRAVITY = 900;
/** How far behind the ball may fall before the thread is simply taut. */
const LEASH = 120;
/** How quickly the ring glides onto a word: the time to close all but a third of the way. */
const GLIDE_S = 0.045;
/** The room the ring leaves round a word, and its smallest and largest there as shares of its
 *  radius over no word. */
const RING_ROOM = 4;
const RING_LEAST = 0.8;
const RING_MOST = 1.6;
/** How far a finger moves before a press is a drag. */
const SLOP = 8;
/** How long the button is held before a drag becomes a sweep: the platform's long press. */
const HOLD_MS = 500;
/** How long a swept run has to stop growing before it is asked about. */
const RUN_SETTLES_MS = 250;
/** How long the circle stays on one word before the actions open round the finger. */
const DWELL_MS = 1000;
/** How far from the finger the actions open, how big each is, and how near the finger has to
 *  come to one to have chosen it. */
const MENU_REACH = 76;
const MENU_SIZE = 52;
const MENU_TAKES = 40;
/** How long the button takes to travel back to its side, and the thread to draw back in. */
const PARK_MS = 260;
const FORM_MS = 340;
const DISSOLVE_MS = 260;
/** The shortest gap between two ticks under the thumb. */
const TICK_APART_MS = 90;
/** The target at the foot: its radius, how far above the foot it sits, how much of the window
 *  from the foot brings it up, how far below where the button waited the finger has to go for
 *  it to rise, and how near its middle the finger takes it and how far lets it go again. */
const DROP_RADIUS = 28;
const DROP_BOTTOM = 36;
const FOOT_ZONE = 0.3;
const HEADING_DOWN = 48;
const TAKE = 64;
const LEAVE = 88;
const DROP_GROWS = 0.22;
/** The thread: how many strands, sampled how finely, how far each sways at a full reach and
 *  what reach that is, how fast its wave and its light travel, how far it bows, how fast it
 *  breathes, and how far out of the button's middle it starts. */
const STRANDS = 7;
const SAMPLES = 26;
const SWAY_MIN = 4;
const SWAY_MAX = 13;
const REFERENCE_SPAN = 190;
const DRIFT_MIN = 1.6;
const DRIFT_MAX = 3.2;
const PULSE_MIN = 0.45;
const BOW = 0.13;
const BREATH = 2.4;
const ICON_EDGE = 0.9;
/** The ring's colour, and the lights along the thread over a dark page and a bright one. */
const RING = [0x4c, 0x9a, 0xff];
const SPARK_LIGHT = '#ffffff';
const PIP_LIGHT = '#fff7e8';
const SPARK_DARK = '#10161d';
/** The word's own mark, and a swept run's. */
const WORD_FILL = 'rgba(31, 111, 235, 0.35)';
const RUN_FILL = 'rgba(31, 111, 235, 0.2)';

const TAU = Math.PI * 2;

const CSS = `
  .mark-frame { position: fixed; inset: 0; pointer-events: none; }
  .mark-layer { position: fixed; inset: 0; width: 100vw; height: 100vh; pointer-events: none; }
  .mark {
    position: fixed; left: 0; top: 0; width: ${MARK}px; height: ${MARK}px;
    pointer-events: auto; touch-action: none; -webkit-user-select: none; user-select: none;
    -webkit-touch-callout: none; -webkit-tap-highlight-color: transparent;
    background: var(--mark-ink); opacity: var(--mark-alpha);
    -webkit-mask: url("${markPng}") center / contain no-repeat;
    mask: url("${markPng}") center / contain no-repeat;
  }
  .mark.parking { transition: transform ${PARK_MS}ms cubic-bezier(0.2, 0.7, 0.3, 1); }
  .mark-menu { position: fixed; left: 0; top: 0; pointer-events: none; }
  .mark-act {
    position: fixed; left: 0; top: 0; width: ${MENU_SIZE}px; height: ${MENU_SIZE}px;
    margin: -${MENU_SIZE / 2}px 0 0 -${MENU_SIZE / 2}px; border-radius: 50%;
    display: grid; place-items: center; box-sizing: border-box;
    background: var(--color-surface); color: var(--color-ink);
    border: var(--border-width) solid var(--color-border); box-shadow: var(--shadow-card);
    transform: translate(var(--x), var(--y)) scale(0.4); opacity: 0;
    transition: transform 160ms cubic-bezier(0.2, 0.8, 0.3, 1.2), opacity 120ms ease-out,
      background 100ms, color 100ms;
  }
  .mark-act svg { width: 24px; height: 24px; }
  .mark-menu.open .mark-act { transform: translate(var(--x), var(--y)) scale(1); opacity: 1; }
  .mark-menu.open .mark-act.chosen {
    transform: translate(var(--x), var(--y)) scale(1.18);
    background: var(--color-accent); color: var(--color-accent-ink); border-color: var(--color-accent);
  }
  .still .mark.parking, .still .mark-act { transition: none; }
  @media (prefers-reduced-motion: reduce) { .mark.parking, .mark-act { transition: none; } }
`;

let host: HTMLElement | null = null;
let frame: HTMLElement | null = null;
let button: HTMLElement | null = null;
let canvas: HTMLCanvasElement | null = null;
let menu: HTMLElement | null = null;
let theme = THEME;
let still = false;
let hands: MarkHands | null = null;
let where: MarkPlace = { side: 'right', pin: false, restY: HOME_DOWN };

/** Where the button sits, as its top left, and where it returns to; -1 before it is placed. */
let markX = -1;
let markY = -1;

/** The colours the thread is drawn in, taken from the app's own icon. */
let strandColours: number[][] = [RING];

function ease(x: number): number {
  const u = Math.min(1, Math.max(0, x));
  return u * u * (3 - 2 * u);
}

/** 0 at the ends, 1 in the middle: a wave anchored where the thread is tied. */
function bell(x: number): number {
  return Math.sin(Math.min(1, Math.max(0, x)) * Math.PI);
}

function quad(a: number, b: number, c: number, t: number): number {
  const u = 1 - t;
  return u * u * a + 2 * u * t * b + t * t * c;
}

function rgba(colour: number[], alpha: number): string {
  return `rgba(${colour[0]}, ${colour[1]}, ${colour[2]}, ${Math.min(1, Math.max(0, alpha))})`;
}

function blend(from: number[], to: number[], f: number): number[] {
  const u = Math.min(1, Math.max(0, f));
  return from.map((c, i) => Math.round(c + (to[i] - c) * u));
}

/** The window as the finger has it: the part of the page that can be seen, above an open
 *  keyboard and, zoomed in, the part zoomed to, in the coordinates fixed boxes are placed in. */
const screen = seen;

/** Whether the page under the button is bright, which picks the ink it is drawn in. */
function onLight(): boolean {
  return !darkHere();
}

/**
 * Where the button waits, as the top left of it.
 *
 * On the side the reader keeps it, above whatever covers the foot of the window. How far down
 * is the reader's only where they pinned it; unpinned, coming to rest is the shortest way to
 * the side, straight across from wherever it already is, and before it was ever placed it
 * starts low on the side, where a thumb holding the phone already is.
 */
function restingAt(): { x: number; y: number } {
  const edges = screen();
  const loose = where.side === 'free';
  const x = loose && markX >= 0
    ? Math.min(Math.max(edges.left, markX), Math.max(edges.left, edges.right - MARK))
    : where.side === 'left' ? edges.left + EDGE : edges.right - MARK - EDGE;
  const floor = edges.bottom - FOOT - MARK - EDGE;
  const y = where.pin && !loose
    ? edges.top + Math.round(where.restY * edges.height) - MARK / 2
    : markY >= 0 ? markY : edges.top + Math.round(HOME_DOWN * edges.height) - MARK / 2;
  const ceiling = edges.top + EDGE;
  return { x, y: Math.min(Math.max(ceiling, y), Math.max(ceiling, floor)) };
}

function putButton(x: number, y: number): void {
  markX = Math.round(x);
  markY = Math.round(y);
  if (button) button.style.transform = `translate(${markX}px, ${markY}px)`;
}

function paint(): void {
  if (!frame) return;
  frame.className = `mark-frame ${themeOf(darkHere(), theme)}${still ? ' still' : ''}`;
  frame.style.setProperty('--mark-ink', onLight() ? '#10161d' : '#ffffff');
  frame.style.setProperty('--mark-alpha', String((onLight() ? 150 : 130) / 255));
}

/** The colours the icon is made of, a few distinct ones, so the light is the mark's own. */
function sampleColours(): void {
  const image = new Image();
  image.onload = () => {
    const n = 24;
    const sample = document.createElement('canvas');
    sample.width = n;
    sample.height = n;
    const g = sample.getContext('2d', { willReadFrequently: true });
    if (!g) return;
    g.drawImage(image, -n / 2, -n / 2, n * 2, n * 2);
    const px = g.getImageData(0, 0, n, n).data;
    const found: number[][] = [];
    for (let i = 0; i < px.length && found.length < 6; i += 4) {
      if (px[i + 3] < 120) continue;
      const c = [px[i], px[i + 1], px[i + 2]];
      const near = found.some((f) =>
        Math.abs(f[0] - c[0]) + Math.abs(f[1] - c[1]) + Math.abs(f[2] - c[2]) < 90);
      if (!near) found.push(c);
    }
    if (found.length > 0) strandColours = found;
  };
  image.src = logoPng;
}

// ── the gesture ────────────────────────────────────────────────────────────────────────

/** One strand of the thread, seeded when the thread forms. */
interface Strand {
  end: number;
  sway: number;
  waves: number;
  drift: number;
  phase: number;
  hue: number[];
  weight: number;
  riding: number;
}

type Phase = 'gone' | 'forming' | 'live' | 'dissolving';

/** Everything one finger on the button is doing. */
const hold = {
  down: false,
  dragging: false,
  formed: false,
  held: false,
  sweeping: false,
  downX: 0,
  downY: 0,
  homeX: 0,
  homeY: 0,
  fingerX: 0,
  fingerY: 0,
  covered: FINGER,
  wantX: 0,
  wantY: 0,
  ballX: 0,
  ballY: 0,
  ballVx: 0,
  ballVy: 0,
  ringX: 0,
  ringY: 0,
  ringRadius: MARK / 2,
  hovered: null as MarkWord | null,
  swept: [] as MarkWord[],
  /** When the circle came onto the word it is on, and which word, for the actions round the
   *  finger. */
  since: 0,
  dwelling: '',
  /** The actions round the finger, once they are open, and which is under it. */
  menuAt: null as { x: number; y: number }[] | null,
  menuWord: null as MarkWord | null,
  chosen: -1,
  asked: false,
  lastTick: 0,
  lastFrame: 0,
  holdTimer: 0,
  runTimer: 0,
};

/** The thread, which outlives the finger for as long as it takes to draw back in. */
const thread = {
  phase: 'gone' as Phase,
  started: 0,
  presence: 0,
  age: 0,
  homeward: 0,
  letGoX: 0,
  letGoY: 0,
  homeX: 0,
  homeY: 0,
  strands: [] as Strand[],
};

/** The word's mark: where it is drawn, sliding from the last word to this one. */
const shown = {
  from: null as DOMRect | null,
  to: null as DOMRect | null,
  at: 0,
  presence: 0,
  fading: 0,
};

/** The target at the foot. */
const drop = {
  present: false,
  presence: 0,
  holding: false,
  pull: 0,
  swallowed: 0,
  swallowing: 0,
};

let frameRequest = 0;

function seed(): void {
  thread.strands = Array.from({ length: STRANDS }, (_, i) => ({
    end: Math.PI / 2 + (i - (STRANDS - 1) / 2) * (TAU / (STRANDS * 4)) + (Math.random() - 0.5) * 0.12,
    sway: SWAY_MIN + Math.random() * (SWAY_MAX - SWAY_MIN),
    waves: 0.35 + Math.random() * 0.4,
    drift: DRIFT_MIN + Math.random() * (DRIFT_MAX - DRIFT_MIN),
    phase: i * (TAU / STRANDS) + Math.random() * 0.4,
    hue: strandColours[i % strandColours.length],
    weight: 0.55 + Math.random() * 0.75,
    riding: Math.random(),
  }));
}

function tick(): void {
  const now = performance.now();
  if (now - hold.lastTick < TICK_APART_MS) return;
  hold.lastTick = now;
  navigator.vibrate?.(8);
}

/** Where the target's middle is when it is all the way up. */
function dropCentre(): { x: number; y: number } {
  const edges = screen();
  return { x: edges.left + edges.width / 2, y: edges.bottom - FOOT - DROP_BOTTOM - DROP_RADIUS };
}

/** Run the frames while anything is moving. */
function animate(): void {
  if (!frameRequest) frameRequest = requestAnimationFrame(frameStep);
}

function frameStep(now: number): void {
  frameRequest = 0;
  const dt = hold.lastFrame === 0 ? 0.016 : Math.min(0.05, Math.max(0.001, (now - hold.lastFrame) / 1000));
  hold.lastFrame = now;
  if (hold.down && hold.formed) step(dt, now);
  advanceThread(dt, now);
  advanceDrop(dt);
  draw(now);
  const busy = (hold.down && hold.formed) || thread.phase !== 'gone' || drop.presence > 0 ||
    drop.swallowing > 0 || shown.presence > 0;
  if (busy) animate();
  else hold.lastFrame = 0;
}

/** One frame of the leash: pulled towards where it is wanted, and heavy. */
function step(dt: number, now: number): void {
  const ax = (hold.wantX - hold.ballX) * STIFFNESS - hold.ballVx * DAMPING;
  const ay = (hold.wantY - hold.ballY) * STIFFNESS - hold.ballVy * DAMPING + GRAVITY;
  hold.ballVx += ax * dt;
  hold.ballVy += ay * dt;
  hold.ballX += hold.ballVx * dt;
  hold.ballY += hold.ballVy * dt;
  // Never so far behind that it is somewhere else entirely.
  const dx = hold.ballX - hold.wantX;
  const dy = hold.ballY - hold.wantY;
  const far = Math.hypot(dx, dy);
  if (far > LEASH) {
    hold.ballX = hold.wantX + (dx / far) * LEASH;
    hold.ballY = hold.wantY + (dy / far) * LEASH;
  }
  // What is under the ball is asked every other frame: a word is a hundred times the distance
  // the ball travels in one, and asking the page is not free. Not while the target holds the
  // button, nor once the actions are open, which are about the word the circle was on.
  hold.asked = !hold.asked;
  if (hold.asked && !drop.holding && !hold.menuAt) hoverAt(hold.ballX, hold.ballY, now);
  if (!hold.menuAt && !hold.sweeping && hold.hovered && hold.dragging &&
      now - hold.since >= DWELL_MS) {
    openMenu();
  }
  settleRing(dt);
}

/** What the circle is over now, told once per word rather than once per frame. */
function hoverAt(x: number, y: number, now: number): void {
  if (!hands) return;
  const found = hands.wordAt(x, y, hold.hovered);
  const was = hold.hovered;
  if (found?.key === was?.key && (found === null) === (was === null)) {
    hold.hovered = found;
    if (found && !hold.sweeping) hands.onWord(found);
    return;
  }
  hold.hovered = found;
  // Counted from the circle coming onto another word: a frame over the gap at the word's edge,
  // which a resting hand makes as often as not, is still staying on it.
  if (found && found.key !== hold.dwelling) {
    hold.dwelling = found.key;
    hold.since = now;
  }
  markWord(found?.rect ?? null, now);
  if (found) {
    tick();
    if (hold.sweeping && !hold.swept.some((w) => w.key === found.key)) {
      hold.swept.push(found);
      // Asked while the finger is still down, a moment after the run stops growing: the card
      // goes when the button is let go, so a run asked about once finished would never be seen.
      if (hold.swept.length > 1) {
        clearTimeout(hold.runTimer);
        hold.runTimer = window.setTimeout(() => {
          if (hold.down && hold.sweeping && hold.swept.length > 1) hands?.onRun([...hold.swept]);
        }, RUN_SETTLES_MS);
      }
    }
  }
  // A sweep is one question, asked when it settles: a card for each word along the way would
  // answer the wrong thing and cover the words still to be swept.
  if (!hold.sweeping) hands.onWord(found);
}

/** One frame of the ring: onto the middle of the word the ball is over, as wide as that word,
 *  or onto the ball itself over none. */
function settleRing(dt: number): void {
  const radius = MARK / 2;
  const on = hold.hovered?.rect;
  const toX = on ? on.left + on.width / 2 : hold.ballX;
  const toY = on ? on.top + on.height / 2 : hold.ballY;
  const toRadius = on
    ? Math.min(radius * RING_MOST, Math.max(radius * RING_LEAST,
      Math.max(on.width, on.height) / 2 + RING_ROOM))
    : radius;
  const k = 1 - Math.exp(-dt / GLIDE_S);
  hold.ringX += (toX - hold.ringX) * k;
  hold.ringY += (toY - hold.ringY) * k;
  hold.ringRadius += (toRadius - hold.ringRadius) * k;
}

/** The word under the circle, or none: the mark slides from the last one, or fades. */
function markWord(rect: DOMRect | null, now: number): void {
  if (!rect) {
    shown.to = null;
    shown.fading = now;
    return;
  }
  shown.from = shown.presence > 0 && shown.to ? currentWordBox(now) : rect;
  shown.to = rect;
  shown.at = now;
  if (shown.presence <= 0) shown.presence = 0.01;
}

function currentWordBox(now: number): DOMRect | null {
  if (!shown.to) return null;
  if (!shown.from) return shown.to;
  const f = ease(Math.min(1, (now - shown.at) / 150));
  const a = shown.from;
  const b = shown.to;
  return new DOMRect(a.left + (b.left - a.left) * f, a.top + (b.top - a.top) * f,
    a.width + (b.width - a.width) * f, a.height + (b.height - a.height) * f);
}

function advanceThread(dt: number, now: number): void {
  if (thread.phase === 'forming') {
    const p = Math.min(1, (now - thread.started) / FORM_MS);
    thread.presence = ease(p);
    if (p >= 1) thread.phase = 'live';
  } else if (thread.phase === 'live') {
    thread.presence = 1;
  } else if (thread.phase === 'dissolving') {
    const p = Math.min(1, (now - thread.started) / DISSOLVE_MS);
    thread.homeward = ease(p);
    thread.presence = 1 - ease(p);
    if (p >= 1) thread.phase = 'gone';
  }
  if (thread.phase === 'gone') return;
  thread.age += dt;
  for (const [i, s] of thread.strands.entries()) {
    s.riding += dt * (PULSE_MIN + (i % 3) * 0.18);
    if (s.riding > 1.35) s.riding -= 1.6;
  }
}

function advanceDrop(dt: number): void {
  const toward = (value: number, goal: number, ms: number) => {
    const stepBy = (dt * 1000) / ms;
    return value < goal ? Math.min(goal, value + stepBy) : Math.max(goal, value - stepBy);
  };
  drop.presence = toward(drop.presence, drop.present ? 1 : 0, drop.present ? 280 : 180);
  drop.pull = toward(drop.pull, drop.holding ? 1 : 0, 200);
  if (drop.swallowing > 0) {
    drop.swallowed = Math.min(1, drop.swallowed + (dt * 1000) / PARK_MS);
    if (drop.swallowed >= 1) {
      drop.swallowing = 0;
      drop.swallowed = 0;
      drop.presence = 0;
      drop.present = false;
      drop.holding = false;
      drop.pull = 0;
      hands?.putAway();
    }
  }
}

// ── drawing ────────────────────────────────────────────────────────────────────────────

function draw(now: number): void {
  if (!canvas) return;
  const ratio = devicePixelRatio || 1;
  const width = innerWidth;
  const height = innerHeight;
  if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
  }
  const g = canvas.getContext('2d');
  if (!g) return;
  g.setTransform(ratio, 0, 0, ratio, 0, 0);
  g.clearRect(0, 0, width, height);
  drawDrop(g);
  // The run first, so the word the circle is on still reads as the one it is on.
  if (hold.sweeping) {
    g.fillStyle = RUN_FILL;
    for (const w of hold.swept) roundRect(g, w.rect, 6);
  }
  drawWordMark(g, now);
  if (thread.phase !== 'gone' && thread.presence > 0) drawThread(g);
}

function roundRect(g: CanvasRenderingContext2D, r: DOMRect, radius: number): void {
  g.beginPath();
  g.roundRect(r.left, r.top, r.width, r.height, radius);
  g.fill();
}

function drawWordMark(g: CanvasRenderingContext2D, now: number): void {
  if (shown.to) {
    shown.presence = Math.min(1, shown.presence + 0.016 * 1000 / 130);
  } else if (shown.presence > 0) {
    shown.presence = Math.max(0, 1 - (now - shown.fading) / 130);
  }
  const box = shown.to ? currentWordBox(now) : shown.from;
  if (!box || shown.presence <= 0) return;
  g.globalAlpha = shown.presence;
  g.fillStyle = WORD_FILL;
  roundRect(g, box, 6);
  g.globalAlpha = 1;
}

function drawThread(g: CanvasRenderingContext2D): void {
  const light = onLight();
  let fromX = hold.fingerX;
  let fromY = hold.fingerY;
  if (thread.phase === 'dissolving') {
    fromX = thread.letGoX + (thread.homeX - thread.letGoX) * thread.homeward;
    fromY = thread.letGoY + (thread.homeY - thread.letGoY) * thread.homeward;
  }
  const ringX = hold.ringX;
  const ringY = hold.ringY;
  const ringRadius = hold.ringRadius;
  // Out of the button's edge rather than its middle: the button stays in sight under the
  // finger, and the light is drawn coming out of it.
  const reachX = ringX - fromX;
  const reachY = ringY - fromY;
  const far = Math.max(1, Math.hypot(reachX, reachY));
  const out = thread.phase === 'dissolving' ? 0 : (MARK / 2) * ICON_EDGE;
  const startX = fromX + (reachX / far) * out;
  const startY = fromY + (reachY / far) * out;
  const spanX = ringX - startX;
  const spanY = ringY - startY;
  const span = Math.max(1, Math.hypot(spanX, spanY));
  const px = -spanY / span;
  const py = spanX / span;
  const facing = Math.atan2(startY - ringY, startX - ringX);
  const scale = Math.min(1.15, Math.max(0.3, span / REFERENCE_SPAN));
  const reach = thread.phase === 'forming' ? thread.presence : 1;
  g.lineCap = 'round';
  g.lineJoin = 'round';
  for (const [i, s] of thread.strands.entries()) {
    const a = facing + (s.end - Math.PI / 2);
    const toX = ringX + Math.cos(a) * ringRadius;
    const toY = ringY + Math.sin(a) * ringRadius;
    const bow = ((i - (STRANDS - 1) / 2) / STRANDS) * 2;
    const cx = (startX + toX) / 2 + px * span * BOW * bow;
    const cy = (startY + toY) / 2 + py * span * BOW * bow;
    const point = (u: number) => {
      const wave = Math.sin(s.phase + u * s.waves * TAU - thread.age * s.drift) * s.sway * scale * bell(u);
      return [quad(startX, cx, toX, u) + px * wave, quad(startY, cy, toY, u) + py * wave];
    };
    g.beginPath();
    for (let k = 0; k <= SAMPLES; k++) {
      const [x, y] = point((k / SAMPLES) * reach);
      if (k === 0) g.moveTo(x, y);
      else g.lineTo(x, y);
    }
    const breath = 0.72 + 0.28 * Math.sin(thread.age * BREATH + s.phase);
    const alpha = Math.min(235, thread.presence * s.weight * breath * 210) / 255;
    // From the mark's own colour at the hand to the ring's at the word.
    const gradient = g.createLinearGradient(startX, startY, toX, toY);
    gradient.addColorStop(0.15, rgba(s.hue, 1));
    gradient.addColorStop(0.95, rgba(RING, 1));
    g.strokeStyle = gradient;
    g.globalAlpha = alpha / 6;
    g.lineWidth = s.weight * 6;
    g.stroke();
    g.lineWidth = s.weight * 3;
    g.stroke();
    g.globalAlpha = alpha;
    g.lineWidth = s.weight * 1.5;
    g.stroke();
    g.globalAlpha = 1;
    const r = s.riding;
    if (r >= 0 && r <= 1 && r <= reach) {
      const [x, y] = point(r);
      const fade = bell(r) * 0.7 + 0.3;
      const pa = Math.min(235, thread.presence * fade * 235) / 255;
      const size = 1.6 + s.weight;
      g.fillStyle = rgba(blend(s.hue, RING, r), pa / 4);
      g.beginPath();
      g.arc(x, y, size * 2.4, 0, TAU);
      g.fill();
      g.beginPath();
      g.arc(x, y, size * 1.4, 0, TAU);
      g.fill();
      g.fillStyle = light ? SPARK_DARK : SPARK_LIGHT;
      g.globalAlpha = pa;
      g.beginPath();
      g.arc(x, y, size * 0.7, 0, TAU);
      g.fill();
      g.globalAlpha = 1;
    }
  }
  // The ring.
  const rr = ringRadius * (0.55 + thread.presence * 0.45);
  const a = thread.presence;
  g.strokeStyle = rgba(RING, a / 6);
  g.lineWidth = 11;
  g.beginPath();
  g.arc(ringX, ringY, rr, 0, TAU);
  g.stroke();
  g.lineWidth = 6;
  g.stroke();
  g.fillStyle = rgba(RING, (a * 46) / 255);
  g.beginPath();
  g.arc(ringX, ringY, Math.max(0, rr - 3), 0, TAU);
  g.fill();
  g.strokeStyle = rgba(RING, a);
  g.lineWidth = 3;
  g.stroke();
  g.fillStyle = light ? SPARK_DARK : PIP_LIGHT;
  g.globalAlpha = a;
  g.beginPath();
  g.arc(ringX, ringY, 3, 0, TAU);
  g.fill();
  g.globalAlpha = 1;
}

function drawDrop(g: CanvasRenderingContext2D): void {
  const shownBy = Math.min(1, Math.max(0, drop.presence));
  if (shownBy <= 0 && drop.swallowed <= 0) return;
  const fade = Math.min(1, Math.max(0, 1 - drop.swallowed));
  const edges = screen();
  const shade = 200;
  const depth = (0.28 + 0.14 * drop.pull) * shownBy * fade;
  const gradient = g.createLinearGradient(0, edges.bottom - shade, 0, edges.bottom);
  gradient.addColorStop(0, 'rgba(0, 0, 0, 0)');
  gradient.addColorStop(1, `rgba(0, 0, 0, ${depth})`);
  g.fillStyle = gradient;
  g.fillRect(edges.left, edges.bottom - shade, edges.width, shade);
  const radius = DROP_RADIUS * (1 + DROP_GROWS * drop.pull) * (1 - drop.swallowed * 0.9);
  if (radius <= 0) return;
  const centre = dropCentre();
  // Risen from below the window's edge, so it comes up into view rather than appearing.
  const below = (FOOT + DROP_BOTTOM + DROP_RADIUS * 2) * (1 - shownBy);
  const cy = centre.y + below;
  const style = frame ? getComputedStyle(frame) : null;
  const token = (name: string, fallback: string) => style?.getPropertyValue(name).trim() || fallback;
  g.globalAlpha = shownBy * fade;
  g.shadowColor = 'rgba(0, 0, 0, 0.28)';
  g.shadowBlur = 10;
  g.shadowOffsetY = 3;
  g.fillStyle = drop.pull > 0.5 ? token('--color-danger', '#c0392b') : token('--color-surface-raised', '#fff');
  g.beginPath();
  g.arc(centre.x, cy, radius, 0, TAU);
  g.fill();
  g.shadowColor = 'transparent';
  g.strokeStyle = token('--color-border', '#ccc');
  g.globalAlpha = shownBy * fade * (1 - drop.pull);
  g.lineWidth = 1.5;
  g.stroke();
  g.globalAlpha = shownBy * fade;
  g.strokeStyle = drop.pull > 0.5 ? '#ffffff' : token('--color-ink', '#222');
  g.lineWidth = 2.5;
  g.lineCap = 'round';
  const arm = radius * 0.34;
  g.beginPath();
  g.moveTo(centre.x - arm, cy - arm);
  g.lineTo(centre.x + arm, cy + arm);
  g.moveTo(centre.x + arm, cy - arm);
  g.lineTo(centre.x - arm, cy + arm);
  g.stroke();
  g.globalAlpha = 1;
}

// ── the actions round the finger ───────────────────────────────────────────────────────

const ACTIONS: MarkAction[] = ['play', 'article'];
const icons: ReturnType<typeof mount>[] = [];

/**
 * Open the actions round the finger, on whichever side of it the window has room: either side
 * of it, or both on one side where it is near an edge, or both above it.
 */
function openMenu(): void {
  if (!menu || !hold.hovered) return;
  const edges = screen();
  const x = hold.fingerX;
  const y = hold.fingerY;
  const at = (degrees: number) => ({
    x: x + Math.cos((degrees * Math.PI) / 180) * MENU_REACH,
    y: y + Math.sin((degrees * Math.PI) / 180) * MENU_REACH,
  });
  const half = MENU_SIZE / 2;
  const card = cardBox();
  const fits = (p: { x: number; y: number }) => p.x - half >= edges.left + EDGE &&
    p.x + half <= edges.right - EDGE && p.y - half >= edges.top + EDGE &&
    p.y + half <= edges.bottom - EDGE;
  // How much of an action the card would cover, which is an action that cannot be seen.
  const under = (p: { x: number; y: number }) => !card ? 0
    : Math.max(0, Math.min(p.x + half, card.right) - Math.max(p.x - half, card.left)) *
      Math.max(0, Math.min(p.y + half, card.bottom) - Math.max(p.y - half, card.top));
  // Either side of the finger, both on one side by an edge, both below it, both above it.
  const fans = [[180, 0], [205, 155], [-25, 25], [125, 55], [-125, -55], [150, 30], [-150, -30]]
    .map((pair) => pair.map(at));
  const cost = (pair: { x: number; y: number }[]) =>
    pair.reduce((sum, p) => sum + under(p) + (fits(p) ? 0 : MENU_SIZE * MENU_SIZE * 4), 0);
  const fan = fans.find((pair) => cost(pair) === 0) ??
    fans.reduce((best, pair) => (cost(pair) < cost(best) ? pair : best));
  hold.menuAt = fan;
  hold.menuWord = hold.hovered;
  hold.chosen = -1;
  for (const [i, act] of [...menu.children].entries()) {
    const spot = fan[i];
    (act as HTMLElement).style.setProperty('--x', `${Math.round(spot.x)}px`);
    (act as HTMLElement).style.setProperty('--y', `${Math.round(spot.y)}px`);
    act.classList.remove('chosen');
  }
  menu.classList.add('open');
  tick();
}

function closeMenu(): void {
  hold.menuAt = null;
  hold.menuWord = null;
  hold.chosen = -1;
  menu?.classList.remove('open');
}

/** Which action the finger is on, if any. */
function chooseUnder(x: number, y: number): void {
  if (!hold.menuAt || !menu) return;
  let best = -1;
  let nearest = MENU_TAKES;
  for (const [i, spot] of hold.menuAt.entries()) {
    const off = Math.hypot(spot.x - x, spot.y - y);
    if (off <= nearest) {
      nearest = off;
      best = i;
    }
  }
  if (best !== hold.chosen) {
    hold.chosen = best;
    for (const [i, act] of [...menu.children].entries()) act.classList.toggle('chosen', i === best);
    if (best >= 0) tick();
  }
}

// ── the finger ─────────────────────────────────────────────────────────────────────────

function pressed(event: PointerEvent): void {
  event.preventDefault();
  event.stopPropagation();
  button!.setPointerCapture(event.pointerId);
  button!.classList.remove('parking');
  hold.down = true;
  hold.dragging = false;
  hold.formed = false;
  hold.held = false;
  hold.sweeping = false;
  hold.swept = [];
  hold.dwelling = '';
  hold.downX = event.clientX;
  hold.downY = event.clientY;
  hold.covered = event.height >= 20 ? event.height : FINGER;
  const waits = restingAt();
  hold.homeX = waits.x + MARK / 2;
  hold.homeY = waits.y + MARK / 2;
  frame!.style.setProperty('--mark-alpha', String(210 / 255));
  clearTimeout(hold.holdTimer);
  // Held first, then dragged, is a sweep: a drag that starts straight away asks about the words
  // it passes one at a time, which is what the button is mostly for.
  hold.holdTimer = window.setTimeout(() => {
    if (hold.dragging || !hold.down) return;
    hold.held = true;
    hold.sweeping = true;
    hold.swept = [];
    tick();
  }, HOLD_MS);
}

function moved(event: PointerEvent): void {
  if (!hold.down) return;
  event.preventDefault();
  if (!hold.dragging && Math.hypot(event.clientX - hold.downX, event.clientY - hold.downY) > SLOP) {
    hold.dragging = true;
    // Moved before the hold was up: the other gesture, which the hold must not turn into a
    // sweep halfway through.
    if (!hold.sweeping) clearTimeout(hold.holdTimer);
  }
  if (!hold.dragging) return;
  follow(event.clientX, event.clientY);
}

/**
 * The button under the finger, and the circle carried away from where the hand comes onto the
 * screen: further out along the line from where the button waits, so everything the thumb
 * covers stays between that place and what it is pointing at. The carry grows from nothing at
 * the button's own place to its full length a fifth of a window away, so every edge is in
 * reach.
 */
function follow(x: number, y: number): void {
  putButton(x - MARK / 2, y - MARK / 2);
  hold.fingerX = x;
  hold.fingerY = y;
  if (hold.menuAt) {
    chooseUnder(x, y);
    hands?.onHand({ x, y });
    return;
  }
  const radius = MARK / 2;
  const lift = hold.covered / 2 + radius + MARK * CLEARANCE;
  const edges = screen();
  const awayX = x - hold.homeX;
  const awayY = y - hold.homeY;
  const away = Math.hypot(awayX, awayY);
  const grows = Math.min(edges.width, edges.height) * GROWS_WITHIN;
  const carry = lift * Math.min(1, Math.max(0, away / grows));
  hold.wantX = Math.min(edges.right, Math.max(edges.left, away > 0 ? x + (awayX / away) * carry : x));
  hold.wantY = Math.min(edges.bottom, Math.max(edges.top, away > 0 ? y + (awayY / away) * carry : y));
  nearTheFoot(x, y);
  hands?.onHand({ x, y });
  if (!hold.formed) {
    hold.formed = true;
    // It appears where it is wanted, above the finger, rather than at the finger and
    // springing up.
    hold.ballX = hold.wantX;
    hold.ballY = hold.wantY;
    hold.ballVx = 0;
    hold.ballVy = 0;
    hold.ringX = hold.ballX;
    hold.ringY = hold.ballY;
    hold.ringRadius = radius;
    hold.lastFrame = 0;
    seed();
    thread.phase = 'forming';
    thread.started = performance.now();
    thread.age = 0;
    thread.presence = 0;
  }
  animate();
}

/** The target at the foot: up while the finger is low, holding the button while it is on it. */
function nearTheFoot(x: number, y: number): void {
  const edges = screen();
  const low = y > edges.bottom - edges.height * FOOT_ZONE || y > hold.homeY + HEADING_DOWN;
  const centre = dropCentre();
  const off = Math.hypot(x - centre.x, y - centre.y);
  // Taken nearer than it is let go, so a finger resting at its edge does not make it snatch
  // and drop the button over and over.
  const over = drop.holding ? off < LEAVE : off < TAKE;
  drop.present = low || over;
  if (over !== drop.holding) {
    drop.holding = over;
    tick();
    if (over) {
      hold.hovered = null;
      markWord(null, performance.now());
      hands?.onWord(null);
    }
  }
  if (over) {
    putButton(centre.x - MARK / 2, centre.y - MARK / 2);
    hold.wantX = centre.x;
    hold.wantY = centre.y;
  }
}

function lifted(event: PointerEvent, cancelled: boolean): void {
  if (!hold.down) return;
  event.preventDefault();
  hold.down = false;
  clearTimeout(hold.holdTimer);
  clearTimeout(hold.runTimer);
  frame!.style.setProperty('--mark-alpha', String((onLight() ? 150 : 130) / 255));
  const wasDrag = hold.dragging;
  const wasHeld = hold.held;
  const chose = hold.menuAt && hold.chosen >= 0 && hold.menuWord
    ? { action: ACTIONS[hold.chosen], word: hold.menuWord }
    : null;
  closeMenu();
  // The card goes with the finger: it is read while the button is held, and nothing on it is
  // there to be touched afterwards.
  hold.hovered = null;
  hold.sweeping = false;
  hold.swept = [];
  markWord(null, performance.now());
  hands?.onWord(null);
  hands?.onHand(null);
  if (!cancelled && chose) hands?.chose(chose.action, chose.word);
  if (wasDrag && drop.holding && !cancelled) {
    // Let go on the target: the thread falls into it and the two shrink away together. The
    // button keeps the place it waited before the drag, for when Phonetix is on again.
    thread.letGoX = hold.fingerX;
    thread.letGoY = hold.fingerY;
    const centre = dropCentre();
    thread.homeX = centre.x;
    thread.homeY = centre.y;
    thread.phase = 'dissolving';
    thread.started = performance.now();
    drop.swallowing = 1;
    drop.swallowed = 0;
    const waited = { x: hold.homeX - MARK / 2, y: hold.homeY - MARK / 2 };
    window.setTimeout(() => {
      if (host) host.style.display = 'none';
      putButton(waited.x, waited.y);
    }, PARK_MS);
    animate();
    return;
  }
  drop.present = false;
  drop.holding = false;
  if (wasDrag) {
    // The button goes back to its side, and the thread draws back into it on the way.
    const home = restingAt();
    button!.classList.add('parking');
    putButton(home.x, home.y);
    if (thread.phase !== 'gone') {
      thread.letGoX = hold.fingerX;
      thread.letGoY = hold.fingerY;
      thread.homeX = markX + MARK / 2;
      thread.homeY = markY + MARK / 2;
      thread.phase = 'dissolving';
      thread.started = performance.now();
    }
    animate();
    return;
  }
  thread.phase = 'gone';
  if (cancelled) return;
  // Nothing was dragged: a press that went nowhere asks for the translator; one held long
  // enough to arm a sweep and let go where it started opens the settings.
  if (wasHeld) hands?.held();
  else hands?.tapped();
}

function build(): void {
  if (host) return;
  host = document.createElement('div');
  host.id = `${OURS}-mark`;
  host.style.cssText = 'position:fixed;inset:0 auto auto 0;width:0;height:0;z-index:2147483645;';
  document.documentElement.appendChild(host);
  const shadow = host.attachShadow({ mode: 'open' });
  const style = document.createElement('style');
  style.textContent = `${tokenCss}\n${CSS}`;
  shadow.appendChild(style);
  frame = document.createElement('div');
  shadow.appendChild(frame);
  canvas = document.createElement('canvas');
  canvas.className = 'mark-layer';
  menu = document.createElement('div');
  menu.className = 'mark-menu';
  for (const action of ACTIONS) {
    const act = document.createElement('div');
    act.className = 'mark-act';
    act.dataset.does = action;
    act.setAttribute('aria-label', action === 'play' ? 'Play' : 'Wiktionary');
    icons.push(mount(action === 'play' ? PLAY : WIKTIONARY, { target: act }));
    menu.appendChild(act);
  }
  button = document.createElement('div');
  button.className = 'mark';
  button.setAttribute('role', 'button');
  button.setAttribute('aria-label', 'Phonetix');
  frame.append(canvas, menu, button);
  paint();
  sampleColours();
  const at = restingAt();
  putButton(at.x, at.y);
  button.addEventListener('pointerdown', pressed);
  button.addEventListener('pointermove', moved);
  button.addEventListener('pointerup', (event) => lifted(event, false));
  button.addEventListener('pointercancel', (event) => lifted(event, true));
  // A long press on the button is the button's, not the browser's menu for an image.
  button.addEventListener('contextmenu', (event) => event.preventDefault());
  // The window turned, resized or a keyboard opened: the button waits at its edge, wherever
  // that is now, and never behind the keyboard.
  const settle = () => {
    if (hold.down) return;
    const back = restingAt();
    putButton(back.x, back.y);
  };
  addEventListener('resize', settle);
  visualViewport?.addEventListener('resize', settle);
}

/** Show the button, answering through [to], resting where [place] says. */
export function markShown(to: MarkHands, place: MarkPlace): void {
  hands = to;
  const moved = place.side !== where.side || place.pin !== where.pin || place.restY !== where.restY;
  where = place;
  build();
  if (host) host.style.display = '';
  if (moved && !hold.down) {
    const back = restingAt();
    putButton(back.x, back.y);
  }
}

/** Put the button away: Phonetix off here, or cards off. */
export function markHidden(): void {
  if (host) host.style.display = 'none';
  closeMenu();
  shown.to = null;
  shown.presence = 0;
}

/** Draw in this palette, and move at once rather than easing where the reader asked for no
 *  animation. */
export function markPaintedIn(chosen: string, animated: boolean): void {
  theme = chosen || THEME;
  still = !animated;
  paint();
}

