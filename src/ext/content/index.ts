// A reading session: one document, one source language, one target.
//
// It scans the page into runs, asks the host what to draw, draws it, and opens a card when the
// reader stops at a word. Every decision about what a word means or whether it is annotated
// belongs to the core; what is here is a document, its events, and the settings the reader
// chose.
import { sendMessage } from '@/host/messages';
import type { Token } from '@/core/tokens';
import { accentFor, allowed, current, DEFAULTS, watch, type Settings } from '@/settings';
import { hide, inside, moveTo, show, showing } from './card';
import { isPainted, paint, reveal, unpaint, unreveal, WORD, wordAt } from './inline';
import inlineCss from '@/ui/inline.css?inline';
import inlineTokens from '@/ui/inline-tokens.css?inline';
import { OURS, scan, type ScannedRun } from './scan';
import { commons } from '@/data/links';

// Until the stored ones are read, which is one await away.
let settings: Settings = DEFAULTS;
/** Words the reader has opened a card for, which stay annotated afterwards. */
const asked: string[] = [];
let opening: ReturnType<typeof setTimeout> | null = null;
let painting = false;

/**
 * What watches the page for text arriving after it loaded.
 *
 * Held here so that painting can throw away the mutations it caused itself. Annotating a page
 * changes the page, those changes arrive at the observer after the paint has finished, and
 * without dropping them the observer asks for another paint, which causes more of them: a
 * page that redrew itself twice a second for as long as it was open, with every line moving
 * as the annotations came off and went back on.
 */
let watcher: MutationObserver | null = null;

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

/**
 * What each run is in, where it says enough to tell.
 *
 * A page is not always in one language: an English video title on a German page is English,
 * and transcribing it as German tells a reader a word is said in a way nobody says it. A run
 * that says too little keeps whatever the page turned out to be, since three words are not a
 * language.
 */
async function readEachRun(runs: ScannedRun[]): Promise<void> {
  const worth = runs.filter((run) => !run.lang && run.text.trim().length >= 24);
  if (worth.length === 0) return;
  const read = await sendMessage('readRuns', { texts: worth.map((run) => run.text) })
    .catch(() => null);
  if (!read) return;
  read.forEach((said, at) => {
    if (said.language) worth[at].lang = said.language;
  });
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
    await readEachRun(runs);
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
        accent: accentFor(settings, source),
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
    // Our own mutations, dropped before anything can act on them. Ordered before the guard
    // comes down, since a record delivered after it is a record that starts this again.
    watcher?.takeRecords();
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
  await play(await sendMessage('speak', { word, lang, accent: accentFor(settings, lang) }));
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
async function open(element: HTMLElement, token: Token, before = ''): Promise<void> {
  const source = token.lang || pageLanguage();
  const answer = await sendMessage('lookUp', {
    word: token.spelling,
    source,
    target: settings.target || source,
    accent: accentFor(settings, source),
    // What the inline layer already knew: a spelling that is several words is decided by the
    // one before it, and the card must not ask a question the page has answered.
    before,
  });
  // The reader may have moved on while the host was answering; the card belongs to the word
  // they are on now, not the one they were on.
  if (!element.isConnected) return;
  const key = token.spelling.toLowerCase();
  if (!asked.includes(key)) asked.push(key);
  // What a person recorded, where Wiktionary has one: a recording is what a reader trusts,
  // and a machine reading a transcription is not the same thing.
  const said = await sendMessage('enrich', { word: token.spelling, lang: source })
    .catch(() => null);
  const recording = said?.audio[0];
  if (!element.isConnected) return;
  // A transcription a person wrote, where the pack had none.
  const shown = answer.ipa.length === 0 && said?.ipa.length
    ? { ...answer, ipa: [said.ipa[0]], symbols: await sendMessage('symbols', { ipa: said.ipa[0] })
        .catch(() => answer.symbols) }
    : answer;
  show(shown, element.getBoundingClientRect(), {
    recorded: Boolean(recording),
    accent: accentFor(settings, source),
    eased: settings.animations,
    onPlay: () => {
      if (recording) void recorded(commons(recording));
      // The accent's own voice where the reader chose one, since a synthesised word is
      // said by whichever voice is asked for.
      else void speak(token.spelling, source);
    },
    // A recording of one sound is a file somebody made, not a voice: it is fetched by the
    // host, because the page's own policy would refuse the load.
    onPlayUrl: (url) => void recorded(url),
    diagram: (file) => sendMessage('diagram', { file, width: DIAGRAM_WIDTH }),
  });
}

/**
 * Several words a reader selected, answered as one.
 *
 * A distinct gesture, and deliberately so: resting on a word asks about that word, and
 * dragging across a clause asks about the clause. No dictionary holds a phrase, so what
 * answers it is the engine and the card says so.
 *
 * Only a real selection of more than one word, inside text the page is showing rather than
 * inside our own card: a stray click leaves an empty selection, and every click would
 * otherwise close the card a reader had just opened.
 */
async function selected(): Promise<void> {
  const selection = document.getSelection();
  const text = selection?.toString().trim() ?? '';
  if (!selection || selection.isCollapsed || text.split(/\s+/).length < 2) return;
  if (text.length > PHRASE_LIMIT) return;
  const at = selection.anchorNode;
  if (at && inside(at instanceof Element ? at : (at.parentElement as Node))) return;
  const source = pageLanguage();
  const target = settings.target || source;
  if (!target || target === source) return;
  const answer = await sendMessage('phrase', { text, source, target }).catch(() => null);
  if (!answer) return;
  const range = selection.getRangeAt(0).getBoundingClientRect();
  show(answer, range, { recorded: false });
}

/** How much text a phrase card will answer. Past this a reader is selecting a page, not a clause. */
const PHRASE_LIMIT = 240;

/** How long a card stays after the cursor leaves the word, so it can be walked into. */
const GRACE = 220;

/** The word the card on screen is about, so it can be put back where that word is now. */
let anchored: HTMLElement | null = null;
let closing: ReturnType<typeof setTimeout> | null = null;
/**
 * Whether the reader has taken hold of the card.
 *
 * A card is a thing to read and select out of, and the moment a reader presses inside one it
 * stops being something the pointer leaving a word may take away.
 */
let grabbed = false;
/** Whether the last press was a finger. On a touch screen a tap fires a hover, and treating
 *  that as a rest opened a card on every tap, including taps meant to follow a link. */
let touched = false;

/**
 * Where the pointer is, as the reader last moved it.
 *
 * A scroll moves the words while the cursor stands still, and the browser reports that as the
 * cursor leaving the word - before it reports the scroll, so no amount of noticing a scroll
 * afterwards is in time. What settles it is asking where the pointer is when the card is about
 * to be taken down: still on the word is still on the word, however the word got there.
 */
let pointer = { x: -1, y: -1 };

/** What is under the pointer now: a word of ours, the card, or the page. */
function under(): Element | null {
  if (pointer.x < 0) return null;
  return document.elementFromPoint(pointer.x, pointer.y);
}

/** Stop the card from closing, because the cursor is somewhere that keeps it. */
function keep(): void {
  if (closing) clearTimeout(closing);
  closing = null;
}

/** Close it after the grace period, unless something keeps it first. */
function letGo(): void {
  keep();
  if (grabbed) return;
  closing = setTimeout(() => {
    closing = null;
    if (grabbed) return;
    // Where the pointer is now, rather than what it was doing when the timer started: a page
    // that scrolled under a still cursor reports the word leaving, and the reader is looking
    // at exactly what they were looking at.
    const at = under();
    if (at && (inside(at) || wordAt(at))) return;
    hide();
    anchored = null;
  }, GRACE);
}

function gestures(): void {
  document.addEventListener(
    'pointerdown',
    (event) => {
      touched = event.pointerType === 'touch';
      // Pressing inside the card is taking hold of it: selecting a translation out of a card
      // that closes when the pointer wanders is a card that cannot be copied from.
      grabbed = inside(event.target);
    },
    { capture: true }
  );

  // A drag across several words asks about all of them at once.
  document.addEventListener('mouseup', () => {
    // After the browser has settled the selection, which it has not when mouseup fires.
    setTimeout(() => void selected(), 0);
  });

  // A rest rather than a hover: the cursor crosses a dozen words on its way anywhere, and a
  // card for each of them is a page nobody can read. How long a rest is is the reader's.
  // Where the reader has the pointer, which is what decides whether a card is still wanted.
  document.addEventListener(
    'mousemove',
    (event) => {
      pointer = { x: event.clientX, y: event.clientY };
    },
    { passive: true }
  );

  document.addEventListener('mouseover', (event) => {
    if (inside(event.target)) {
      keep();
      return;
    }
    const found = wordAt(event.target);
    if (!found) return;
    if (touched) return;
    // What the swap covered, back for as long as the cursor is on it. Immediately, because
    // it is the word itself rather than a card about it.
    reveal(found.element);
    keep();
    if (opening) clearTimeout(opening);
    opening = setTimeout(() => {
      anchored = found.element;
      void open(found.element, found.token, found.before);
    }, Math.max(0, settings.delay));
  });
  document.addEventListener('mouseout', (event) => {
    if (inside(event.target)) return;
    if (!wordAt(event.target)) return;
    if (opening) clearTimeout(opening);
    unreveal();
    // Not straight away: the card sits under the word, and the pointer has to cross the gap
    // between them to reach it.
    if (showing()) letGo();
  });

  // A touch opens it outright: there is no resting on a phone, and the annotation is small
  // enough that a tap on it is deliberate.
  document.addEventListener('click', (event) => {
    if (inside(event.target)) return;
    const found = wordAt(event.target);
    if (found) {
      event.preventDefault();
      grabbed = false;
      if (touched) reveal(found.element);
      anchored = found.element;
      void open(found.element, found.token, found.before);
      return;
    }
    grabbed = false;
    unreveal();
    if (showing()) {
      hide();
      anchored = null;
    }
  });

  // A card anchored to a word that has moved is a card pointing at nothing, so it goes with
  // the word rather than closing: a reader who scrolls a line to read it has not asked for
  // the answer to disappear. It closes only once the word it is about is off the screen.
  window.addEventListener(
    'scroll',
    () => {
      if (!showing()) return;
      const word = anchored;
      if (!word?.isConnected) {
        hide();
        anchored = null;
        return;
      }
      const box = word.getBoundingClientRect();
      if (box.bottom < 0 || box.top > window.innerHeight) {
        hide();
        anchored = null;
        return;
      }
      moveTo(box);
    },
    { passive: true }
  );
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && showing()) {
      grabbed = false;
      hide();
      anchored = null;
      unreveal();
    }
  });
}

/** Whether a node is something this extension drew rather than something the page brought. */
function ours(node: Node): boolean {
  const element = node instanceof Element ? node : node.parentElement;
  return element !== null && element.closest(`.${WORD}, #${OURS}`) !== null;
}

/**
 * Watch the page for text that arrives after it loaded.
 *
 * Redrawn rather than patched, because the sprinkle is decided across everything on screen:
 * annotating only what arrived would count its words from zero and annotate every one of them.
 */
function follow(): void {
  let soon: ReturnType<typeof setTimeout> | null = null;
  watcher = new MutationObserver((changes) => {
    if (painting) return;
    // Text the page brought, not text we drew. An annotation is an element of ours holding
    // the word it annotates, so a change inside one is our own work coming back to us.
    const real = changes.some((change) =>
      !ours(change.target) &&
      [...change.addedNodes].some(
        (node) =>
          !ours(node) && (node.nodeType === Node.TEXT_NODE || node instanceof HTMLElement)
      )
    );
    if (!real) return;
    if (soon) clearTimeout(soon);
    soon = setTimeout(() => void draw(), 500);
  });
  watcher.observe(document.body, { childList: true, subtree: true });
}

/**
 * What the page turned out to be in, for a settings view asking.
 *
 * The page is the one that read itself, and a view that guessed again could offer accents
 * for a language nothing on screen is in.
 */
function answerAsked(): void {
  chrome.runtime.onMessage.addListener((message: unknown, _sender, respond) => {
    const asked = message as { phonetix?: string };
    if (asked?.phonetix !== 'pageLanguage') return false;
    respond({ ok: pageLanguage() });
    return true;
  });
}

/** Start reading this document. */
export async function session(): Promise<void> {
  settings = await current();
  answerAsked();
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
      JSON.stringify(was.accents) !== JSON.stringify(fresh.accents) ||
      was.hideStress !== fresh.hideStress ||
      was.off.join() !== fresh.off.join()
    ) {
      if (!allowed(fresh, location.hostname) && isPainted()) unpaint();
      else await draw();
    }
  });
  await draw();
}
