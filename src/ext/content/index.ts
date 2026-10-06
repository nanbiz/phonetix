// A reading session: one document, one source language, one target.
//
// It scans the page into runs, asks the host what to draw, draws it, and opens a card when the
// reader stops at a word. Every decision about what a word means or whether it is annotated
// belongs to the core; what is here is a document, its events, and the settings the reader
// chose.
import { sendMessage } from '@/host/messages';
import type { Token } from '@/core/tokens';
import {
  accentFor, allowed, current, DEFAULTS, watch, type Settings,
} from '@/settings';
import {
  hide, inside, moveTo, paintedIn as cardPaintedIn, show, showing, type CardActions,
} from './card';
import { open as openAsk } from './ask';
import {
  isPainted,
  paint,
  paintedIn,
  reveal,
  unpaint,
  unreveal,
  WORD,
  wordAt,
} from './inline';
import inlineCss from '@/ui/inline.css?inline';
import inlineTokens from '@/ui/inline-tokens.css?inline';
import { OURS, readable, scan, type ScannedRun } from './scan';
import { commons } from '@/data/links';

/** What is drawn over the words of a page: how each is said. */
const INLINE = 'sound';

// Until the stored ones are read, which is one await away.
let settings: Settings = DEFAULTS;
/** Words the reader has opened a card for, which stay annotated afterwards. */
const asked: string[] = [];
let opening: ReturnType<typeof setTimeout> | null = null;
let painting = false;
let drawAgain = false;

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
  // Asked for again while a draw is under way - a dictionary arrived, the lines came back
  // translated - and drawn again once it is done. Dropped, as it was, the page kept what the
  // first draw had before either of those, and nothing asked again.
  if (painting) {
    drawAgain = true;
    return;
  }
  painting = true;
  try {
    unpaint();
    // A site the reader switched off is a site this does nothing on, which is not the same
    // as the extension being off everywhere.
    if (!allowed(settings, location.hostname)) {
      state('off');
      return;
    }
    const runs = scan();
    if (runs.length === 0) {
      state('nothing to read');
      return;
    }
    // What the page is in, which the card needs whether or not anything is drawn over it.
    await readLanguage(runs);
    if (settings.layer === 'off') {
      state('nothing drawn');
      return;
    }
    styles();
    await readEachRun(runs);
    const source = pageLanguage();
    state(`asking about ${runs.length} runs`);
    // What goes over a word on the page is how it is said, and only that: what a word means is
    // the card's, which opens on the word a reader points at.
    const batch = await sendMessage('annotate', {
      runs: runs.map((run) => ({ id: run.id, text: run.text, lang: run.lang })),
      source,
      target: settings.target || source,
      options: {
        mode: INLINE,
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
      paint(run, tokens, INLINE);
      drew += tokens.filter((token) => token.inline).length;
    }
    state(`drew ${drew} of ${batch.tokens.length}`);
  } catch (e) {
    state(`failed: ${String(e)}`);
    throw e;
  } finally {
    // Our own mutations, dropped before anything can act on them. Ordered before the guard
    // comes down, since a record delivered after it is a record that starts this again.
    watcher?.takeRecords();
    painting = false;
    if (drawAgain) {
      drawAgain = false;
      redraw();
    }
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

/**
 * What a card is pinned to: a word the page drew, or a stretch of the page's own text.
 *
 * The card follows it on a scroll, goes when it leaves the page, and stays while the pointer is
 * on it, whichever of the two it is.
 */
interface Anchor {
  box(): DOMRect;
  alive(): boolean;
  holds(x: number, y: number): boolean;
}

function onElement(element: HTMLElement): Anchor {
  return {
    box: () => element.getBoundingClientRect(),
    alive: () => element.isConnected,
    holds: (x, y) => {
      const at = document.elementFromPoint(x, y);
      return at !== null && element.contains(at);
    },
  };
}

function onRange(range: Range): Anchor {
  return {
    box: () => range.getBoundingClientRect(),
    alive: () => range.startContainer.isConnected,
    holds: (x, y) => within(range, x, y),
  };
}

/** Whether a point is on the text a range covers, line by line. */
function within(range: Range, x: number, y: number): boolean {
  return [...range.getClientRects()].some(
    (box) => x >= box.left && x <= box.right && y >= box.top && y <= box.bottom
  );
}

/** One word to open a card for: how the page spells it, what it is in, and what it drew. */
interface Asked {
  spelling: string;
  lang: string;
  before: string;
  /** What the page drew over it, where it had decided which word it is. */
  drawn: string;
}

function askedOf(token: Token, before: string): Asked {
  return {
    spelling: token.spelling,
    lang: token.lang,
    before,
    drawn: token.state !== 'Homograph' ? (token.gloss ?? '') : '',
  };
}

/**
 * The word of the page's own text under a point, where there is one.
 *
 * Every word a reader can point at is a word they can ask about, not only the ones the page
 * drew a transcription over: the sprinkle chooses one word in so many, and a card that opened
 * on those alone answered one word in twelve.
 */
function wordUnder(x: number, y: number): { range: Range; asked: Asked } | null {
  const caret = caretAt(x, y);
  if (!caret || !(caret.node instanceof Text)) return null;
  const node = caret.node;
  const parent = node.parentElement;
  if (!parent || ours(node) || !readable(parent)) return null;
  const text = node.nodeValue ?? '';
  const lang = languageOf(node);
  let before = '';
  for (const part of new Intl.Segmenter(lang, { granularity: 'word' }).segment(text)) {
    if (part.index > caret.offset) break;
    const end = part.index + part.segment.length;
    if (part.isWordLike && caret.offset <= end) {
      const range = document.createRange();
      range.setStart(node, part.index);
      range.setEnd(node, end);
      // The caret is the nearest place to the point, which is a word even where the pointer is
      // in the margin beside a line: only a word actually under the pointer is asked about.
      if (within(range, x, y)) {
        return { range, asked: { spelling: part.segment, lang, before, drawn: '' } };
      }
    }
    if (part.isWordLike) before = part.segment;
  }
  return null;
}

/** Where in the text a point falls, by whichever of the two names the engine gives it. */
function caretAt(x: number, y: number): { node: Node; offset: number } | null {
  const engine = document as Document & {
    caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
  };
  if (engine.caretPositionFromPoint) {
    const at = engine.caretPositionFromPoint(x, y);
    return at ? { node: at.offsetNode, offset: at.offset } : null;
  }
  const range = document.caretRangeFromPoint?.(x, y);
  return range ? { node: range.startContainer, offset: range.startOffset } : null;
}

/** The language a stretch of text is in: what its own element declares, or the page's. */
function languageOf(node: Text): string {
  const declared = node.parentElement?.closest('[lang]');
  if (declared && declared !== document.documentElement && declared !== document.body) {
    const lang = declared.getAttribute('lang')?.trim().split('-')[0].toLowerCase();
    if (lang) return lang;
  }
  return pageLanguage();
}

/** Open the card for a word the reader stopped at. */
async function open(anchor: Anchor, word: Asked): Promise<void> {
  const source = word.lang || pageLanguage();
  const answer = await sendMessage('lookUp', {
    word: word.spelling,
    source,
    // Into the reader's own language, which for a word already in it is the word itself: the
    // card then says how it is said and nothing is translated.
    target: settings.target || source,
    accent: accentFor(settings, source),
    // What the inline layer already knew: a spelling that is several words is decided by the
    // one before it, and the card must not ask a question the page has answered.
    before: word.before,
    // And what it drew, where it had decided which word this is.
    drawn: word.drawn,
  });
  // The reader may have moved on while the host was answering; the card belongs to the word
  // they are on now, not the one they were on.
  if (!anchor.alive() || anchored !== anchor) return;
  const key = word.spelling.toLowerCase();
  if (!asked.includes(key)) asked.push(key);
  // What a person recorded, where Wiktionary has one: a recording is what a reader trusts,
  // and a machine reading a transcription is not the same thing.
  const said = await sendMessage('enrich', { word: word.spelling, lang: source })
    .catch(() => null);
  const recording = said?.audio[0];
  if (!anchor.alive() || anchored !== anchor) return;
  // A transcription a person wrote, where the pack had none.
  const shown = answer.ipa.length === 0 && said?.ipa.length
    ? { ...answer, ipa: [said.ipa[0]], symbols: await sendMessage('symbols', { ipa: said.ipa[0] })
        .catch(() => answer.symbols) }
    : answer;
  const actions: CardActions = {
    recorded: Boolean(recording),
    accent: accentFor(settings, source),
    eased: settings.animations,
    onPlay: () => {
      if (recording) void recorded(commons(recording));
      // The accent's own voice where the reader chose one, since a synthesised word is
      // said by whichever voice is asked for.
      else void speak(word.spelling, source);
    },
    // A recording of one sound is a file somebody made, not a voice: it is fetched by the
    // host, because the page's own policy would refuse the load.
    onPlayUrl: (url) => void recorded(url),
    diagram: (file) => sendMessage('diagram', { file, width: DIAGRAM_WIDTH }),
  };
  show(shown, anchor.box(), actions);
  // A word no dictionary means anything by, in another language than the reader's: the card
  // is up with how it is said, and what the engine makes of it follows, marked as its guess.
  const target = settings.target || source;
  if (shown.says.length > 0 || shown.glosses.length > 0 || target === source) return;
  const guess = await sendMessage('guess', { word: word.spelling, source, target })
    .catch(() => '');
  if (!guess || !anchor.alive() || anchored !== anchor) return;
  show(
    { ...shown, state: 'Guess', says: [guess], provenance: { kind: 'guess', engine: 'bergamot' } },
    anchor.box(),
    actions
  );
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
  if (at && inside(at instanceof Element ? at : (at.parentElement))) return;
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
let anchored: Anchor | null = null;
/** The wait for the pointer to come to rest over the page's own text. */
let resting: ReturnType<typeof setTimeout> | null = null;
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
    if (anchored?.holds(pointer.x, pointer.y)) return;
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
      if (resting) clearTimeout(resting);
      resting = null;
      // A word the page drew is answered by its own mouseover, and the card by itself; a
      // button held down is a selection being made rather than a word being pointed at.
      if (touched || event.buttons !== 0 || inside(event.target) || wordAt(event.target)) return;
      if (showing() && anchored && !anchored.holds(pointer.x, pointer.y)) letGo();
      if (!allowed(settings, location.hostname)) return;
      resting = setTimeout(() => {
        resting = null;
        if (anchored?.holds(pointer.x, pointer.y)) return;
        const found = wordUnder(pointer.x, pointer.y);
        if (!found) return;
        anchored = onRange(found.range);
        void open(anchored, found.asked);
      }, Math.max(0, settings.delay));
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
      anchored = onElement(found.element);
      void open(anchored, askedOf(found.token, found.before));
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
      anchored = onElement(found.element);
      void open(anchored, askedOf(found.token, found.before));
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
      if (!word?.alive()) {
        hide();
        anchored = null;
        return;
      }
      const box = word.box();
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
    if (asked?.phonetix === 'askForAWord') {
      askedForAWord();
      respond({ ok: true });
      return true;
    }
    if (asked?.phonetix !== 'pageLanguage') return false;
    respond({ ok: pageLanguage() });
    return true;
  });
}

/** The panel the keyboard shortcut opens, which is the other direction: everything else on
 *  this page is about a word somebody else wrote. */
function askedForAWord(): void {
  void openAsk(pageLanguage());
}

/** Start reading this document. */
export async function session(): Promise<void> {
  settings = await current();
  paintedIn(settings.theme, settings.dark);
  cardPaintedIn(settings.theme, settings.dark);
  answerAsked();
  gestures();
  follow();
  watch((fresh) => {
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
      was.theme !== fresh.theme ||
      was.dark !== fresh.dark ||
      was.off.join() !== fresh.off.join()
    ) {
      paintedIn(fresh.theme, fresh.dark);
      cardPaintedIn(fresh.theme, fresh.dark);
      if (!allowed(fresh, location.hostname) && isPainted()) unpaint();
      else redraw();
    }
  });
  // And when a dictionary arrives or is given up, which is not a setting and used to leave
  // the page exactly as it was: a reader fetched the dictionary for the page they were
  // looking at and nothing on it changed.
  // And when lines of it have come back translated, which decides which word or which sense
  // some of its words are: what was drawn before was the dictionary's first.
  browser.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local' || !('heldPacks' in changes || 'linesTranslated' in changes)) return;
    redraw();
  });
  await draw();
}

/**
 * Draw the page again, from something that cannot wait for it: a setting changing, a
 * dictionary arriving. A failure is already written down where the page's state is kept, so
 * what is left is to keep it from becoming a rejection nobody is listening for.
 */
function redraw(): void {
  draw().catch(() => undefined);
}
