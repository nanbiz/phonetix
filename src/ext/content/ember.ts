// The ember: a small fire that goes with the pointer while Phonetix is on, and goes into the
// word the pointer is on.
//
// It says where a card would come from before it comes: on a page where every word can be
// asked about, the pointer itself gives no sign of which word that is. It burns at the arrow's
// tip, and over a word its fire flies into that word, which takes a soft light; from word to
// word the light slides rather than jumping, the way the phone's mark moves between words,
// because a mark that jumps is read as several marks appearing. Leaving the word, the fire
// comes off the light and back to the pointer.
//
// The fire is sparks drawn each frame rather than shapes animated by the stylesheet: shapes
// cycling through keyframes read as a few poses taken in turn, where sparks born, rising,
// cooling and dying are never twice the same, and the same sparks are what travel into the
// word and back, so what goes in is the fire itself rather than one thing fading as another
// appears.
//
// Drawn in a shadow root of its own that never takes the pointer, so the page under it is the
// page, and nothing here decides which word is under the pointer: that is the reader's code
// in index.ts, which tells this where to be.
import tokenCss from '@/ui/tokens.css?inline';
import { darkHere } from './inline';
import { THEME, themeOf } from '@/ui/theme';
import { OURS } from './scan';

/** How much of the way to the pointer the fire covers in a sixtieth of a second: a trail, not
 *  a lag. */
const EASE = 0.35;
/** How many sparks a second the fire gives off while it burns at the pointer. */
const SPARKS_PER_SECOND = 40;
/** How many sparks carry the fire into a word, and back out of it. */
const CARRIED = 18;
/** How long the core takes to empty into a word or fill back up, in seconds. */
const CORE_S = 0.16;
/** How long after the fire leaves for a word the word's light comes up, in milliseconds: as
 *  the first sparks arrive rather than before any have left. */
const LIGHT_AFTER_MS = 70;
/** How long the pointer stays still before the fire is drawn at the slower rate. */
const REST_MS = 200;
/** How often a fire at rest is drawn, in milliseconds: thirty times a second. */
const RESTING_FRAME_MS = 30;

const CSS = `
  .ember-frame { position: fixed; inset: 0; pointer-events: none; }
  .ember { position: fixed; left: 0; top: 0; pointer-events: none; }
  .ember-word {
    position: fixed; left: 0; top: 0; border-radius: 6px; opacity: 0;
    background: color-mix(in srgb, var(--color-accent) 16%, transparent);
    box-shadow: 0 0 10px 1px color-mix(in srgb, var(--color-accent) 22%, transparent);
    transition: opacity 130ms ease-out, transform 150ms cubic-bezier(0.2, 0.8, 0.3, 1),
      width 150ms cubic-bezier(0.2, 0.8, 0.3, 1), height 150ms cubic-bezier(0.2, 0.8, 0.3, 1);
  }
  .ember-word.on { opacity: 1; }
  /* On a light page a light amber is barely there, so the light side gets more of it. */
  .mode-light .ember-word {
    background: color-mix(in srgb, var(--color-accent) 28%, transparent);
    box-shadow: 0 0 10px 1px color-mix(in srgb, var(--color-accent) 34%, transparent);
  }
  .still .ember-word { transition: none; }
  @media (prefers-reduced-motion: reduce) {
    .ember-word { transition: none; }
  }
`;

/** One spark: where it is, how it moves, how long it has lived and will live. One that
 *  carries fire flies from where it started to a point, rather than drifting. */
interface Spark {
  x: number;
  y: number;
  vx: number;
  vy: number;
  age: number;
  life: number;
  size: number;
  from?: { x: number; y: number };
  /** Where a carrying spark is going; for one going back, the pointer, wherever it is by then. */
  to?: { x: number; y: number } | 'pointer';
  /** A spark that cools away rather than arriving: off the light, back into the fire. */
  late?: number;
}

let frame: HTMLElement | null = null;
let canvas: HTMLCanvasElement | null = null;
let pen: CanvasRenderingContext2D | null = null;
let light: HTMLElement | null = null;
let theme = THEME;
let dark = false;
/** Whether it is drawn still: the reader's own setting, or the device's. */
let still = matchMedia('(prefers-reduced-motion: reduce)').matches;
/** Where the fire burns, and where the pointer is. */
let at: { x: number; y: number } | null = null;
let goal: { x: number; y: number } | null = null;
/** How much fire is in the core: all of it at the pointer, none while it is in a word. */
let core = 0;
/** Whether the fire is at the pointer, in a word, or put away. */
let where: 'pointer' | 'word' | 'away' = 'away';
let sparks: Spark[] = [];
/** Sparks owed to the frame, as a fraction: a second's worth spread over its frames. */
let owed = 0;
let frameAsked = 0;
let last = 0;
/** When the pointer last moved. */
let moved = 0;
/** Frames drawn, which the checks read to tell a fire that moves from one that does not. */
let drawn = 0;
/** The word it last lit, so the same word is not lit again every frame. */
let lit: DOMRect | null = null;
let lighting = 0;

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
  canvas = document.createElement('canvas');
  canvas.className = 'ember';
  pen = canvas.getContext('2d');
  frame.append(light, canvas);
  paint();
}

/**
 * The canvas over just the box the fire is drawn in this frame, at the screen's own density,
 * and the pen set to draw in the window's own coordinates. Drawn over the whole window every
 * frame, the fire at rest cost the page half a core; over the box it is in, a small fraction.
 * Placed in steps, so that sparks drifting a pixel do not move it every frame.
 */
function cover(left: number, top: number, right: number, bottom: number, settled: boolean): void {
  if (!canvas || !pen) return;
  const step = 32;
  const x = Math.floor(left / step) * step;
  const y = Math.floor(top / step) * step;
  const density = Math.min(devicePixelRatio || 1, 2);
  // Grown when the fire needs more room and kept at that size while it burns: a canvas sized
  // afresh is a canvas allocated afresh.
  // Back to the fire's own size once nothing is travelling to or from a word.
  const needed = [Math.ceil((right - x) / step) * step, Math.ceil((bottom - y) / step) * step];
  const roomy = canvas.width / density > needed[0] * 2 || canvas.height / density > needed[1] * 2;
  const keep = !(settled && roomy);
  const width = keep ? Math.max(needed[0], canvas.width / density) : needed[0];
  const height = keep ? Math.max(needed[1], canvas.height / density) : needed[1];
  if (canvas.width !== width * density || canvas.height !== height * density) {
    canvas.width = width * density;
    canvas.height = height * density;
    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;
  }
  canvas.style.transform = `translate(${x}px, ${y}px)`;
  pen.setTransform(density, 0, 0, density, -x * density, -y * density);
  pen.clearRect(x, y, width, height);
}

/** Nothing to draw: the canvas shrinks to nothing rather than holding the last frame. */
function uncover(): void {
  if (!canvas) return;
  canvas.width = canvas.height = 0;
  canvas.style.width = canvas.style.height = '0';
}

function paint(): void {
  dark = darkHere();
  if (frame) frame.className = `ember-frame ${themeOf(dark, theme)}${still ? ' still' : ''}`;
}

/** Draw in this palette, and still where the reader asked for no animation. */
export function emberPaintedIn(chosen: string, moving: boolean): void {
  theme = chosen || THEME;
  still = !moving || matchMedia('(prefers-reduced-motion: reduce)').matches;
  paint();
}

/**
 * Burn at the pointer's tip, at ([x], [y]), or be in [word] where the pointer is on one.
 */
export function emberAt(x: number, y: number, word: DOMRect | null): void {
  build();
  if (!goal || goal.x !== x || goal.y !== y) moved = performance.now();
  goal = { x, y };
  if (!at || where === 'away') at = { x, y };
  const was = where;
  where = word ? 'word' : 'pointer';
  if (!still && was === 'pointer' && word) carryInto(word);
  // Out of a word the fire gathers where the pointer is now, not where it was before the word.
  if (was === 'word' && !word) at = { x, y };
  if (!still && was === 'word' && !word && lit) carryOutOf(lit);
  if (was === 'away' && !word) core = still ? 1 : core;
  lightWord(word, !still && was === 'pointer');
  report();
  if (!frameAsked) frameAsked = requestAnimationFrame(step);
}

/** Put it away: the pointer has left the page, or is on something of ours. */
export function emberAway(): void {
  where = 'away';
  lightWord(null, false);
  report();
  if (canvas && !frameAsked) frameAsked = requestAnimationFrame(step);
}

/** What the checks read: where it is and what it is doing. */
function report(): void {
  if (!canvas) return;
  canvas.className = where === 'away' ? 'ember' : where === 'word' ? 'ember on in' : 'ember on';
  canvas.dataset.at = at ? `${at.x.toFixed(1)},${at.y.toFixed(1)}` : '';
  canvas.dataset.drawn = String(drawn);
}

function lightWord(word: DOMRect | null, after: boolean): void {
  if (!light) return;
  // The same word again, as every pointer move over it says: nothing changes, and a light on
  // its way up is left to come up.
  if (word && lit && lit.left === word.left && lit.top === word.top && lit.width === word.width) {
    return;
  }
  clearTimeout(lighting);
  if (!word) {
    light.classList.remove('on');
    lit = null;
    return;
  }
  // Grown a little past the word, so the light reads as being around it rather than on it.
  const pad = 3;
  light.style.width = `${Math.round(word.width + pad * 2)}px`;
  light.style.height = `${Math.round(word.height + pad * 2)}px`;
  light.style.transform = `translate(${Math.round(word.left - pad)}px, ${Math.round(word.top - pad)}px)`;
  const shown = light;
  if (after && !lit) lighting = window.setTimeout(() => shown.classList.add('on'), LIGHT_AFTER_MS);
  else light.classList.add('on');
  lit = word;
}

/** The fire leaves the pointer for points spread over the word, and settles there. */
function carryInto(word: DOMRect): void {
  if (!at) return;
  for (let i = 0; i < CARRIED; i++) {
    const from = { x: at.x + spread(4), y: at.y + spread(4) };
    sparks.push({
      ...from, vx: 0, vy: 0, age: 0, life: 0.2 + Math.random() * 0.16,
      size: 1.8 + Math.random() * 1.8, from,
      to: { x: word.left + Math.random() * word.width, y: word.top + Math.random() * word.height },
    });
  }
}

/** The fire comes off the word's light and back to the pointer, wherever it has got to. */
function carryOutOf(word: DOMRect): void {
  for (let i = 0; i < CARRIED; i++) {
    const from = { x: word.left + Math.random() * word.width, y: word.top + Math.random() * word.height };
    sparks.push({
      ...from, vx: 0, vy: 0, age: 0, life: 0.2 + Math.random() * 0.14,
      size: 1.5 + Math.random() * 1.8, from, to: 'pointer', late: Math.random() * 0.06,
    });
  }
}

function spread(by: number): number {
  return (Math.random() * 2 - 1) * by;
}

/** Smooth motion that never comes round again: sines at rates no two of which divide. */
function wander(t: number, seed: number): number {
  return (Math.sin(t * 1.73 + seed) * 0.5 + Math.sin(t * 2.91 + seed * 1.7) * 0.3 +
    Math.sin(t * 4.37 + seed * 2.3) * 0.2);
}

/** The colour of fire this hot, from 1 at its hottest to 0 as it dies: white, yellow, amber,
 *  orange, a deep red. */
function heat(h: number): [number, number, number] {
  const stops: [number, [number, number, number]][] = [
    [1, [255, 250, 225]], [0.8, [255, 214, 90]], [0.6, [255, 160, 30]],
    [0.35, [240, 90, 10]], [0, [170, 30, 5]],
  ];
  for (let i = 1; i < stops.length; i++) {
    const [top, hot] = stops[i - 1];
    const [bottom, cool] = stops[i];
    if (h >= bottom) {
      const k = (h - bottom) / (top - bottom);
      return [0, 1, 2].map((c) => Math.round(cool[c] + (hot[c] - cool[c]) * k)) as [number, number, number];
    }
  }
  return stops[stops.length - 1][1];
}

function step(now: number): void {
  frameAsked = 0;
  if (!canvas || !pen || !goal) return;
  // A fire left burning where the pointer rests is drawn every other frame: it changes as
  // much to the eye, and costs half. Following the pointer, or carrying fire to or from a
  // word, every frame.
  const resting = where === 'pointer' && now - moved > REST_MS && !sparks.some((spark) => spark.to);
  if (resting && last && now - last < RESTING_FRAME_MS) {
    frameAsked = requestAnimationFrame(step);
    return;
  }
  const dt = last ? Math.min((now - last) / 1000, 0.05) : 1 / 60;
  last = now;
  const t = now / 1000;
  // The fire trails the pointer, eased by how long the frame took rather than per frame.
  if (at && where === 'pointer') {
    const k = still ? 1 : 1 - Math.pow(1 - EASE, dt * 60);
    at = { x: at.x + (goal.x - at.x) * k, y: at.y + (goal.y - at.y) * k };
  }
  // The core empties while its fire is in a word, and fills as it comes back.
  const full = where === 'pointer' ? 1 : 0;
  const filling = sparks.some((spark) => spark.to === 'pointer');
  if (still) core = full;
  else if (!filling || full === 0) core += Math.sign(full - core) * Math.min(Math.abs(full - core), dt / CORE_S);
  if (!still && where === 'pointer' && at && core > 0.3) {
    owed += SPARKS_PER_SECOND * dt * core;
    while (owed >= 1) {
      owed -= 1;
      const angle = Math.random() * Math.PI * 2;
      const speed = 6 + Math.random() * 18;
      sparks.push({
        x: at.x + spread(3), y: at.y + spread(3),
        vx: Math.cos(angle) * speed, vy: Math.sin(angle) * speed - 14,
        age: 0, life: 0.28 + Math.random() * 0.42, size: 1.2 + Math.random() * 1.9,
      });
    }
  }
  for (const spark of sparks) {
    spark.age += dt;
    if (spark.to && spark.from) {
      const to = spark.to === 'pointer' ? at ?? goal : spark.to;
      const k = Math.max(0, Math.min(1, (spark.age - (spark.late ?? 0)) / spark.life));
      // Fast away and slowing into place, with a sway across the way it flies.
      const eased = 1 - Math.pow(1 - k, 3);
      const sway = Math.sin(k * Math.PI) * wander(t * 3, spark.size * 7) * 6;
      spark.x = spark.from.x + (to.x - spark.from.x) * eased + sway;
      spark.y = spark.from.y + (to.y - spark.from.y) * eased - Math.sin(k * Math.PI) * 4;
      // Arriving back is the core filling.
      if (spark.to === 'pointer' && k >= 1) core = Math.min(1, core + 1 / CARRIED);
    } else {
      // Fire rises, and slows as it cools.
      spark.vy -= 30 * dt;
      spark.vx *= 1 - 1.5 * dt;
      spark.x += spark.vx * dt;
      spark.y += spark.vy * dt;
    }
  }
  sparks = sparks.filter((spark) => spark.age < spark.life + (spark.late ?? 0));

  // The box this frame draws in: the core and every spark, each with its glow.
  let left = Infinity;
  let top = Infinity;
  let right = -Infinity;
  let bottom = -Infinity;
  const take = (x: number, y: number, reach: number) => {
    left = Math.min(left, x - reach);
    top = Math.min(top, y - reach);
    right = Math.max(right, x + reach);
    bottom = Math.max(bottom, y + reach);
  };
  if (at && core > 0.01) take(at.x, at.y, 18);
  for (const spark of sparks) take(spark.x, spark.y, spark.size * 2 + 2);
  if (left === Infinity) uncover();
  else cover(left, top, right, bottom, !sparks.some((spark) => spark.to));
  // Light on a dark page adds up the way fire does; on a light one it would add up to white.
  pen.globalCompositeOperation = dark ? 'lighter' : 'source-over';
  // Added up, many sparks burn out to white, so on a dark page each gives less and runs a
  // little cooler; and white-hot does not show on white, so a light page is shown it cooler
  // still.
  const cooler = dark ? 0.85 : 0.72;
  const gain = dark ? 0.3 : 0.6;
  for (const spark of sparks) {
    const k = Math.min(1, spark.age / spark.life);
    const carried = Boolean(spark.to);
    const hot = (carried ? 0.85 - k * 0.35 : 1 - k) * cooler;
    const alpha = carried ? 0.9 * (spark.to === 'pointer' ? 1 - k * 0.5 : 1 - k * k) : (1 - k) * 0.85;
    stamp(spark.x, spark.y, spark.size * (carried ? 1 : 1 - k * 0.5), hot, alpha * gain);
  }
  // The core: a few soft blobs, each wandering round the middle and swelling on its own, so
  // their sum has no outline that holds.
  if (at && core > 0.01) {
    const blobs: [number, number, number][] = [[0.15, 5.4, 1.4], [0.4, 4.2, 2.6], [0.7, 3.3, 3.9], [1, 2.2, 5.3]];
    for (const [hot, radius, seed] of blobs) {
      const reach = still ? 0 : 1.5;
      const x = at.x + wander(t * 2.2, seed) * reach;
      const y = at.y + wander(t * 2.6, seed + 11) * reach - (1 - hot) * 1.5;
      const swell = still ? 1 : 0.85 + 0.3 * (wander(t * 3.1, seed + 23) * 0.5 + 0.5);
      stamp(x, y, radius * swell * (0.4 + 0.6 * core), hot * (dark ? 0.92 : 1),
        0.6 * core * (dark ? 0.7 : 1));
    }
  }
  pen.globalCompositeOperation = 'source-over';
  drawn++;
  report();
  // Burning while it is at the pointer; until the last spark is gone otherwise.
  if (!still && (where === 'pointer' || sparks.length > 0 || core > 0.01)) {
    frameAsked = requestAnimationFrame(step);
  } else {
    last = 0;
    if (where !== 'pointer') uncover();
  }
}

/** Sparks drawn once per heat, in as many steps as the eye tells apart. */
const HEATS = 32;
const sprites: (HTMLCanvasElement | null)[] = Array(HEATS + 1).fill(null);

/** The soft round spark for fire this hot, drawn the first time it is asked for. */
function sprite(hot: number): HTMLCanvasElement {
  const at = Math.max(0, Math.min(HEATS, Math.round(hot * HEATS)));
  const had = sprites[at];
  if (had) return had;
  const made = document.createElement('canvas');
  made.width = made.height = 64;
  const ink = made.getContext('2d');
  if (ink) {
    const [r, g, b] = heat(at / HEATS);
    const glow = ink.createRadialGradient(32, 32, 0, 32, 32, 32);
    glow.addColorStop(0, `rgba(${r},${g},${b},1)`);
    glow.addColorStop(0.35, `rgba(${r},${g},${b},0.75)`);
    glow.addColorStop(1, `rgba(${r},${g},${b},0)`);
    ink.fillStyle = glow;
    ink.fillRect(0, 0, 64, 64);
  }
  sprites[at] = made;
  return made;
}

/** A soft round spark of [size] radius, this hot. */
function stamp(x: number, y: number, size: number, hot: number, alpha: number): void {
  if (!pen || alpha <= 0 || size <= 0) return;
  pen.globalAlpha = Math.min(1, alpha);
  pen.drawImage(sprite(hot), x - size * 2, y - size * 2, size * 4, size * 4);
  pen.globalAlpha = 1;
}
