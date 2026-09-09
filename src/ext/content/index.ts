// A reading session: one document, one source language, one target.
//
// It scans the page into runs, asks the host what to draw, draws it, and opens a card when the
// reader stops at a word. Every decision about what a word means or whether it is annotated
// belongs to the core; what is here is a document, its events, and the settings the reader
// chose.
import { sendMessage } from '@/host/messages';
import type { Token } from '@/core/tokens';
import { allowed, current, DEFAULTS, watch, type Settings } from '@/settings';
import { hide, inside, show, showing } from './card';
import { isPainted, paint, unpaint, wordAt } from './inline';
import inlineCss from '@/ui/inline.css?inline';
import inlineTokens from '@/ui/inline-tokens.css?inline';
import { scan, type ScannedRun } from './scan';

/** How long the cursor rests on a word before its card opens, in milliseconds. */
const REST = 200;

// Until the stored ones are read, which is one await away.
let settings: Settings = DEFAULTS;
/** Words the reader has opened a card for, which stay annotated afterwards. */
const asked: string[] = [];
let opening: ReturnType<typeof setTimeout> | null = null;
let painting = false;

/**
 * What the session is doing, written where anything can read it.
 *
 * An extension's own console is not reachable from the page it is annotating, so without this
 * a page that came back blank looks the same whether nothing was scanned, the host never
 * answered, or the reader has it switched off. The three are different problems.
 */
function state(said: string): void {
  document.documentElement.dataset.phonetix = said;
}

/** What the reader said this page is, what the page says it is, or nothing yet. */
function declaredLanguage(): string {
  if (settings.source) return settings.source;
  const declared = document.documentElement.lang || document.body.lang;
  return declared.trim().split('-')[0].toLowerCase();
}

/** What the page turned out to be in, once the core has read some of it. */
let detected = '';

/**
 * Which language this page is in.
 *
 * A page that declares one is taken at its word, because a page's author knows. A page that
 * declares nothing is read: the alternative is assuming English, which is how a German page
 * ends up covered in English pronunciations.
 */
function pageLanguage(): string {
  return declaredLanguage() || detected || 'en';
}

/**
 * Ask the core what the page is in, from as much of it as says anything.
 *
 * The whole visible text rather than one line: a line on its own says too little, and the
 * detector says so rather than guessing, so a screen of labels would come back as nothing.
 */
async function readLanguage(runs: ScannedRun[]): Promise<void> {
  if (declaredLanguage() || detected) return;
  const sample = runs
    .map((run) => run.text.trim())
    .filter((text) => text.length > 0)
    .join(' ')
    .slice(0, 1000);
  // How much is enough is the core's rule, not a number chosen here: the phone reads a screen
  // by the same one.
  const read = await sendMessage('readScreen', { text: sample }).catch(() => null);
  if (read?.language) detected = read.language;
}

/** The stylesheet the annotations are drawn by, put in the page once. */
function styles(): void {
  if (document.getElementById('phonetix-inline-style')) return;
  const style = document.createElement('style');
  style.id = 'phonetix-inline-style';
  // The tokens first, scoped to the extension's own elements: a page with its own --space-4
  // must not have it redefined under it by the thing annotating it.
  style.textContent = `${inlineTokens}\n${inlineCss}`;
  document.head.appendChild(style);
}

/**
 * Annotate what is on the page now.
 *
 * One pass over the whole document rather than one per paragraph: the sprinkle counts a word's
 * occurrences across everything a reader sees, and a page annotated a paragraph at a time
 * would count each one from zero and annotate the same word every time it appeared.
 */
async function draw(): Promise<void> {
  if (painting) return;
  painting = true;
  try {
    unpaint();
    // A site the reader switched off is a site this does nothing on, which is not the same
    // as the extension being off everywhere.
    if (!allowed(settings, location.hostname) || settings.layer === 'off') {
      state('off');
      return;
    }
    styles();
    const runs = scan();
    if (runs.length === 0) {
      state('nothing to read');
      return;
    }
    await readLanguage(runs);
    const source = pageLanguage();
    state(`asking about ${runs.length} runs`);
    const batch = await sendMessage('annotate', {
      runs: runs.map((run) => ({ id: run.id, text: run.text, lang: run.lang })),
      source,
      target: settings.target || source,
      options: {
        mode: settings.layer,
        density: settings.density,
        narrow: settings.narrow,
        hideStress: settings.hideStress,
        seen: asked,
      },
    });
    const byRun = new Map<number, Token[]>();
    for (const token of batch.tokens) {
      const held = byRun.get(token.run);
      if (held) held.push(token);
      else byRun.set(token.run, [token]);
    }
    let drew = 0;
    for (const run of runs) {
      const tokens = byRun.get(run.id);
      if (!tokens) continue;
      paint(run, tokens, settings.layer);
      drew += tokens.filter((token) => token.inline).length;
    }
    state(`drew ${drew} of ${batch.tokens.length}`);
  } catch (e) {
    state(`failed: ${e}`);
    throw e;
  } finally {
    painting = false;
  }
}

/** How wide a picture of a mouth is asked for, which is what the sheet gives it. */
const DIAGRAM_WIDTH = 192;

/** Play a sound somebody recorded, fetched by the host for the same reason as everything
 *  else that comes from the network. */
async function recorded(url: string): Promise<void> {
  const bytes = await sendMessage('fetch', { url }).catch(() => [] as number[]);
  await play(bytes);
}

/**
 * Say a word out loud.
 *
 * The bytes are made in the host, where the engine is, and played here, where there is a
 * page: a page's own media policy can refuse an element loading a sound and cannot refuse
 * Web Audio playing bytes it was handed.
 */
async function speak(word: string, lang: string): Promise<void> {
  await play(await sendMessage('speak', { word, lang }));
}

/** Play bytes through Web Audio, which is what a page's media policy cannot refuse. */
async function play(bytes: number[]): Promise<void> {
  if (bytes.length === 0) return;
  const context = new AudioContext();
  const sound = await context.decodeAudioData(new Uint8Array(bytes).buffer);
  const source = context.createBufferSource();
  source.buffer = sound;
  source.connect(context.destination);
  source.start();
}

/** Open the card for a word the reader stopped at. */
async function open(element: HTMLElement, token: Token): Promise<void> {
  const source = token.lang || pageLanguage();
  const answer = await sendMessage('lookUp', {
    word: token.spelling,
    source,
    target: settings.target || source,
  });
  // The reader may have moved on while the host was answering; the card belongs to the word
  // they are on now, not the one they were on.
  if (!element.isConnected) return;
  const key = token.spelling.toLowerCase();
  if (!asked.includes(key)) asked.push(key);
  show(answer, element.getBoundingClientRect(), {
    onPlay: () => void speak(token.spelling, source),
    // A recording of one sound is a file somebody made, not a voice: it is fetched by the
    // host, because the page's own policy would refuse the load.
    onPlayUrl: (url) => void recorded(url),
    diagram: (file) => sendMessage('diagram', { file, width: DIAGRAM_WIDTH }),
  });
}

function gestures(): void {
  // A rest rather than a hover: the cursor crosses a dozen words on its way anywhere, and a
  // card for each of them is a page nobody can read.
  document.addEventListener('mouseover', (event) => {
    const found = wordAt(event.target);
    if (!found) return;
    if (opening) clearTimeout(opening);
    opening = setTimeout(() => open(found.element, found.token), REST);
  });
  document.addEventListener('mouseout', (event) => {
    if (!wordAt(event.target)) return;
    if (opening) clearTimeout(opening);
  });

  // A touch opens it outright: there is no resting on a phone, and the annotation is small
  // enough that a tap on it is deliberate.
  document.addEventListener('click', (event) => {
    if (inside(event.target)) return;
    const found = wordAt(event.target);
    if (found) {
      event.preventDefault();
      open(found.element, found.token);
      return;
    }
    if (showing()) hide();
  });

  // A card anchored to a word that has moved is a card pointing at nothing.
  window.addEventListener('scroll', () => showing() && hide(), { passive: true });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && showing()) hide();
  });
}

/**
 * Watch the page for text that arrives after it loaded.
 *
 * Redrawn rather than patched, because the sprinkle is decided across everything on screen:
 * annotating only what arrived would count its words from zero and annotate every one of them.
 */
function follow(): void {
  let soon: ReturnType<typeof setTimeout> | null = null;
  const observer = new MutationObserver((changes) => {
    if (painting) return;
    const real = changes.some((change) =>
      [...change.addedNodes].some(
        (node) => node.nodeType === Node.TEXT_NODE || node instanceof HTMLElement
      )
    );
    if (!real) return;
    if (soon) clearTimeout(soon);
    soon = setTimeout(() => void draw(), 500);
  });
  observer.observe(document.body, { childList: true, subtree: true });
}

/** Start reading this document. */
export async function session(): Promise<void> {
  settings = await current();
  gestures();
  follow();
  watch(async (fresh) => {
    const was = settings;
    settings = fresh;
    // Only what changes the page redraws it: a reader dragging the frequency bar changes it
    // on every step, and a redraw per step is a page rebuilt fifty times.
    if (
      was.on !== fresh.on ||
      was.layer !== fresh.layer ||
      was.density !== fresh.density ||
      was.target !== fresh.target ||
      was.source !== fresh.source ||
      was.narrow !== fresh.narrow ||
      was.hideStress !== fresh.hideStress ||
      was.off.join() !== fresh.off.join()
    ) {
      if (!allowed(fresh, location.hostname) && isPainted()) unpaint();
      else await draw();
    }
  });
  await draw();
}
