// A reading session: one document, one source language, one target.
//
// It scans the page into runs, asks the host what to draw, draws it, and opens a card when the
// reader stops at a word. Every decision about what a word means or whether it is annotated
// belongs to the core; what is here is a document, its events, and the settings the reader
// chose.
import { sendMessage } from '@/host/messages';
import type { Token } from '@/core/tokens';
import type { Answer } from '@/core/answer';
import {
  accentFor, allowed, current, DEFAULTS, translates, watch, type Settings,
} from '@/settings';
import {
  hide, inside, moveTo, paintedIn as cardPaintedIn, passedOver, show, showing,
  type CardActions,
} from './card';
import { open as openAsk } from './ask';
import {
  asWritten,
  drewIt,
  keepOnly,
  originOf,
  paint,
  paintedIn,
  reveal,
  unpaint,
  unreveal,
  WORD,
  wordAt,
  type Layer,
} from './inline';
import inlineCss from '@/ui/inline.css?inline';
import inlineTokens from '@/ui/inline-tokens.css?inline';
import { OURS, readable, scan, type ScannedRun } from './scan';
import { commons } from '@/data/links';

/**
 * What takes the place of the words of a page: how each is said, or what it means.
 *
 * One of the two and never both, so the page still reads as running text. A mode stored as
 * both, from before the choice was one of two, is read as meaning.
 */
function inline(): Layer {
  return settings.layer === 'sound' ? 'sound' : 'meaning';
}

// Until the stored ones are read, which is one await away.
let settings: Settings = DEFAULTS;
/** Words the reader has opened a card for, which stay annotated afterwards. */
const asked: string[] = [];
let opening: ReturnType<typeof setTimeout> | null = null;
/** Whether a pass is under way. One at a time: each one starts from what the last one drew. */
let painting = false;
/** Whether the whole page is to be read again, because something it was drawn by changed. */
let wholeWanted = false;
/** What the page added since it was last read, to be read on its own. */
const arrived = new Set<Node>();
/**
 * How often each word has occurred in everything drawn so far, lowercased.
 *
 * Text a page adds later is read on its own and continues this count, so it gets the words
 * it would have got had it been there from the start, and nothing already on the page changes
 * because something was added under it.
 */
let counts: Record<string, number> = {};
/** How many words are drawn and how many were read, for the state the page carries. */
let drewWords = 0;
let readWords = 0;

/**
 * What watches the page for text arriving after it loaded.
 *
 * Held here so that painting can throw away the mutations it caused itself: annotating a page
 * changes the page, and a paint that came back as page text arriving would be read again,
 * and cause more of it, for as long as the page was open.
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
    if (said.language) {
      worth[at].lang = said.language;
      readAs.set(worth[at].node, said.language);
    }
  });
}

/**
 * What the page's pass decided about every word of each text node, drawn or not.
 *
 * The pass reads each word in its line, with the trained classifier and the words around it,
 * and a card about a word that was not drawn is the same question: asked again of the word
 * alone it was a different answer, and "Die" opening a sentence was "that" on the card where
 * the line had read it as "the".
 */
const read = new WeakMap<Text, Token[]>();

/** What each of the page's text nodes turned out to be in, where it said enough to tell, so a
 *  word pointed at in an English line on a German page is asked about as English. */
const readAs = new WeakMap<Text, string>();

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
 * Read the whole page again and draw what changed.
 *
 * One pass over the whole document rather than one per paragraph: the sprinkle counts a word's
 * occurrences across everything a reader sees, and a page annotated a paragraph at a time
 * would count each one from zero and annotate the same word every time it appeared.
 *
 * What is drawn stays drawn while the pass is under way, and only the words whose reading
 * changed are drawn again: a reader changing a setting or a dictionary arriving sees those
 * words change and nothing else move.
 */
async function drawWhole(): Promise<void> {
  // A site the reader switched off is a site this does nothing on, which is not the same
  // as the extension being off everywhere.
  if (!allowed(settings, location.hostname)) {
    unpaint();
    state('off');
    return;
  }
  const runs = asWritten(() => scan());
  watcher?.takeRecords();
  if (runs.length === 0) {
    unpaint();
    state('nothing to read');
    return;
  }
  // What the page and each line of it is in, which the card needs whether or not anything is
  // drawn over it: it decides which words are translated at all.
  await readLanguage(runs);
  await readEachRun(runs);
  if (settings.layer === 'off') {
    unpaint();
    watcher?.takeRecords();
    state('nothing drawn');
    return;
  }
  styles();
  state(`asking about ${runs.length} runs`);
  counts = {};
  drewWords = 0;
  readWords = 0;
  await drawRuns(runs, () => keepOnly(new Set(runs.map((run) => run.node))));
}

/**
 * Read what the page added and draw it, leaving everything already drawn as it is.
 *
 * A page that adds a preview under the pointer, a comment, the next screen of a feed, gets
 * that read; a page redrawn whole for each of those was a page whose every line went back to
 * its own spelling and came back seconds later, over and over, for as long as it was open.
 */
async function drawAdded(roots: Node[]): Promise<void> {
  if (!allowed(settings, location.hostname)) return;
  const fresh = roots.filter(
    (root) =>
      root.isConnected &&
      !ours(root) &&
      !roots.some((other) => other !== root && other.contains(root))
  );
  let id = 0;
  const runs = fresh.flatMap((root) => {
    // Without the text between painted words, which a page that moved a painted paragraph
    // brings back as added: it is a line already read.
    const found = scan(root, id).filter((run) => !drewIt(run.node));
    id += found.length;
    return found;
  });
  if (runs.length === 0) return;
  await readEachRun(runs);
  if (settings.layer === 'off') return;
  styles();
  await drawRuns(runs);
}

/**
 * How much text one request carries.
 *
 * The host reads a request in one go, and a card asked for while it reads waits behind it: a
 * long article sent whole held every card for three seconds. In pieces of this size a card
 * waits for one piece at most, and the page is drawn top first as the pieces come back.
 */
const PIECE = 12000;

/** Runs cut into pieces of about PIECE characters, in the order they are read. */
function pieces(runs: ScannedRun[]): ScannedRun[][] {
  const out: ScannedRun[][] = [];
  let piece: ScannedRun[] = [];
  let size = 0;
  for (const run of runs) {
    piece.push(run);
    size += run.text.length;
    if (size >= PIECE) {
      out.push(piece);
      piece = [];
      size = 0;
    }
  }
  if (piece.length > 0) out.push(piece);
  return out;
}

/**
 * Ask what to draw on these runs and draw it, a piece at a time.
 *
 * Each piece continues the count of the one before, so the page gets the words it would have
 * got asked about whole. `settle` runs in the same step as the last piece is drawn, for
 * whatever else that step has to put right.
 */
async function drawRuns(runs: ScannedRun[], settle?: () => void): Promise<void> {
  const source = pageLanguage();
  for (const piece of pieces(runs)) {
    const batch = await sendMessage('annotate', {
      runs: piece.map((run) => ({ id: run.id, text: run.text, lang: run.lang })),
      source,
      target: settings.target || source,
      options: {
        mode: inline(),
        density: settings.density,
        narrow: settings.narrow,
        hideStress: settings.hideStress,
        accent: accentFor(settings, source),
        seen: asked,
        counts,
      },
    });
    const byRun = new Map<number, Token[]>();
    for (const token of batch.tokens) {
      const key = token.spelling.toLowerCase();
      counts[key] = (counts[key] ?? 0) + 1;
      const held = byRun.get(token.run);
      if (held) held.push(token);
      else byRun.set(token.run, [token]);
    }
    for (const run of piece) {
      const tokens = byRun.get(run.id) ?? [];
      read.set(run.node, tokens);
      // A word in a language the reader reads is left as it is where words are replaced by
      // what they mean: translating it is translating what they never asked to have translated.
      const shown = inline() === 'meaning'
        ? tokens.map((token) =>
          translates(settings, token.lang || run.lang || source) ? token : { ...token, inline: false })
        : tokens;
      paint(run, shown, inline());
      drewWords += shown.filter((token) => token.inline).length;
    }
    readWords += batch.tokens.length;
    // Our own changes, dropped before the observer can take them for the page's.
    watcher?.takeRecords();
  }
  settle?.();
  watcher?.takeRecords();
  state(`drew ${drewWords} of ${readWords}`);
}

/** Run whatever passes are wanted, one after another, until none is. */
async function pump(): Promise<void> {
  if (painting) return;
  painting = true;
  try {
    while (wholeWanted || arrived.size > 0) {
      if (wholeWanted) {
        wholeWanted = false;
        arrived.clear();
        await drawWhole();
      } else {
        const roots = [...arrived];
        arrived.clear();
        await drawAdded(roots);
      }
    }
  } catch (e) {
    state(`failed: ${String(e)}`);
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

/** One word to open a card for: how the page spells it, how it is read, what it is in, and
 *  the word before it. */
interface Asked {
  /** As the word is read, which is what is looked up. */
  spelling: string;
  /** As the page writes it, which is what the card shows. */
  written: string;
  lang: string;
  before: string;
}

/**
 * The question the page's own pass asked about a word, asked again for its card.
 *
 * The same spelling, read the same way, after the same word: the card then reads the word the
 * page read. Handing the core the word the page drew instead, as though it were the line
 * translated, sent "the" looking for itself among the readings of "die" and found it in "the
 * one, him".
 */
function askedOf(token: Token, before: string): Asked {
  return {
    spelling: token.reading || token.spelling,
    written: token.spelling,
    lang: token.lang,
    before,
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
  // The text between painted words is the page's own and is asked about like any other; a
  // painted word answers through its own box.
  if (!parent || parent.closest(`.${WORD}, #${OURS}`) || !readable(parent)) return null;
  const text = node.nodeValue ?? '';
  const lang = languageOf(node);
  const origin = originOf(node);
  const decided = read.get(origin.node) ?? [];
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
        // The page's own reading of this word where its pass reached it.
        const at = origin.at + part.index;
        const token = decided.find((it) => it.start <= at && at < it.end);
        return {
          range,
          asked: token
            ? { ...askedOf(token, before), lang: token.lang || lang }
            : { spelling: part.segment, written: part.segment, lang, before },
        };
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

/** The language a stretch of text is in: what its own element declares, what it was read as,
 *  or the page's. */
function languageOf(node: Text): string {
  const said = readAs.get(originOf(node).node);
  if (said) return said;
  const declared = node.parentElement?.closest('[lang]');
  if (declared && declared !== document.documentElement && declared !== document.body) {
    const lang = declared.getAttribute('lang')?.trim().split('-')[0].toLowerCase();
    if (lang) return lang;
  }
  return pageLanguage();
}

/** How far each dictionary on its way has got, as the host last said. */
let coming: Record<string, number> = {};
/** What the card on screen does when that changes. */
let following: (() => void) | null = null;

/** What is known about a word with what it means left off: how it is said, and which form of
 *  which word it is. */
function said(answer: Answer): Answer {
  return {
    ...answer,
    state: answer.state === 'Homograph' ? 'Entry' : answer.state,
    lead: null,
    says: [],
    glosses: [],
    marks: [],
    example: null,
    readings: [],
    provenance: answer.provenance?.kind === 'guess' ? null : answer.provenance,
  };
}

/** Open the card for a word the reader stopped at, or tapped, which is asking for it outright. */
async function open(anchor: Anchor, word: Asked, tapped = false): Promise<void> {
  const source = word.lang || pageLanguage();
  // Into the reader's own language, unless this is one they read as it is: then the card says
  // how the word is said and nothing else, since what it means is the one thing they did not
  // ask for. A dictionary's English definition of a German word is a translation all the same.
  const translating = translates(settings, source);
  const target = translating ? settings.target : source;
  const looked = await sendMessage('lookUp', {
    word: word.spelling,
    source,
    target,
    accent: accentFor(settings, source),
    // What the inline layer already knew: a spelling that is several words is decided by the
    // one before it, and the card must not ask a question the page has answered.
    before: word.before,
    drawn: '',
  });
  // Shown as the page writes it, whichever way it was read.
  const written = { ...looked, spelling: word.written };
  const answer = translating ? written : said(written);
  // The reader may have moved on while the host was answering; the card belongs to the word
  // they are on now, not the one they were on.
  const current = () => anchor.alive() && anchored === anchor;
  if (!current()) return;
  const key = word.spelling.toLowerCase();
  if (!asked.includes(key)) asked.push(key);
  let recording = '';
  const actions: CardActions = {
    recorded: false,
    arriving: coming[source] ?? null,
    accent: accentFor(settings, source),
    eased: settings.animations,
    entered: tapped,
    onPlay: () => {
      if (recording) void recorded(commons(recording));
      // The accent's own voice where the reader chose one, since a synthesised word is
      // said by whichever voice is asked for.
      else void speak(word.spelling, source);
    },
    // The lemma's entry and the word's other forms, asked the way the word itself was, with
    // nothing before them: they are not words of the page's sentence.
    lookUp: async (other) => {
      const found = await sendMessage('lookUp', {
        word: other, source, target, accent: accentFor(settings, source), before: '', drawn: '',
      }).catch(() => null);
      return found && (translating ? found : said(found));
    },
    // A recording of one sound is a file somebody made, not a voice: it is fetched by the
    // host, because the page's own policy would refuse the load.
    onPlayUrl: (url) => void recorded(url),
    diagram: (file) => sendMessage('diagram', { file, width: DIAGRAM_WIDTH }),
  };
  // Up with what the dictionary said, at once. What the network and the engine add arrives
  // after and fills the same card: a card that waited on Wiktionary waited a second or more
  // on every word.
  let shown = answer;
  show(shown, anchor.box(), actions);
  // Told again as the dictionary for its language comes in, and asked again once it is here.
  following = () => {
    if (!current()) return;
    const share = coming[source] ?? null;
    if (share === null && actions.arriving !== null) {
      void open(anchor, word, tapped);
      return;
    }
    actions.arriving = share;
    show(shown, anchor.box(), actions);
  };
  const enriched = sendMessage('enrich', { word: word.spelling, lang: source })
    .then(async (found) => {
      if (!found || !current()) return;
      recording = found.audio[0] ?? '';
      actions.recorded = Boolean(recording);
      // A transcription a person wrote, where the pack had none.
      if (shown.ipa.length === 0 && found.ipa.length > 0) {
        const symbols = await sendMessage('symbols', { ipa: found.ipa[0] })
          .catch(() => shown.symbols);
        shown = { ...shown, ipa: [found.ipa[0]], symbols };
      }
      if (current()) show(shown, anchor.box(), actions);
    })
    .catch(() => undefined);
  // A word no dictionary means anything by, in another language than the reader's: what the
  // engine makes of it follows, marked as its guess.
  if (answer.says.length > 0 || answer.glosses.length > 0 || !translating) return;
  const guess = await sendMessage('guess', { word: word.spelling, source, target })
    .catch(() => '');
  await enriched;
  if (!guess || !current()) return;
  shown = {
    ...shown, state: 'Guess', lead: null, says: [guess],
    provenance: { kind: 'guess', engine: 'bergamot' },
  };
  show(shown, anchor.box(), actions);
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
  const source = at instanceof Text ? languageOf(at) : pageLanguage();
  if (!translates(settings, source)) return;
  const target = settings.target;
  const answer = await sendMessage('phrase', { text, source, target }).catch(() => null);
  if (!answer) return;
  const range = selection.getRangeAt(0).getBoundingClientRect();
  // Asked for by selecting, so there is no word to come in from and nothing to pass through.
  show(answer, range, { recorded: false, entered: true });
}

/** How much text a phrase card will answer. Past this a reader is selecting a page, not a clause. */
const PHRASE_LIMIT = 240;

/**
 * How long a card stays after the cursor leaves its word for the open page.
 *
 * The way into the card is its arrow, whose point is narrower than most words, so a pointer
 * leaving the word near the arrow's side crosses a few pixels of page before it is on the
 * arrow. A pointer that comes onto the card anywhere else takes it down at once.
 */
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
      const was = pointer;
      pointer = { x: event.clientX, y: event.clientY };
      if (resting) clearTimeout(resting);
      resting = null;
      // A page that scrolls under a still cursor is reported as a move to where the cursor
      // already was. That is the page moving rather than the reader, and the card goes with its
      // word rather than closing, even where the scroll has brought the card under the cursor.
      const moved = was.x !== pointer.x || was.y !== pointer.y;
      // On the card without having come in by its arrow: the reader is moving on to what the
      // card covers, which the card lets the pointer through to and must not hide.
      if (moved && passedOver(was, pointer) && !anchored?.holds(pointer.x, pointer.y)) {
        keep();
        grabbed = false;
        hide();
        anchored = null;
      }
      // A word the page drew is answered by its own mouseover, and the card by itself; a
      // button held down is a selection being made rather than a word being pointed at.
      if (touched || event.buttons !== 0 || inside(event.target) || wordAt(event.target)) return;
      if (moved && showing() && anchored && !anchored.holds(pointer.x, pointer.y)) letGo();
      if (!allowed(settings, location.hostname)) return;
      resting = setTimeout(() => {
        resting = null;
        if (anchored?.holds(pointer.x, pointer.y)) return;
        const found = wordUnder(pointer.x, pointer.y);
        // A word in a language the reader reads is left to them: its card would only say how
        // it is said, which is what the words drawn over the page are for.
        if (!found || !translates(settings, found.asked.lang)) return;
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
    // The word stays as it is drawn: the card names what the page wrote, and flipping each
    // word back to its spelling as the pointer crossed it set the line jumping under a reader
    // moving across it.
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
    // Not straight away: the pointer may be on its way onto the arrow, which is narrower at
    // its point than the word it leaves. And not at all where the word left a still cursor,
    // which is a scroll: the browser reports the way out before the move that causes it, so a
    // reader leaving is reported from somewhere other than where the pointer last was.
    if (showing() && (event.clientX !== pointer.x || event.clientY !== pointer.y)) letGo();
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
      void open(anchored, askedOf(found.token, found.before), touched);
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
  if (drewIt(node)) return true;
  const element = node instanceof Element ? node : node.parentElement;
  return element !== null && element.closest(`#${OURS}`) !== null;
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
    // Text the page brought, not text we drew. An annotation is an element of ours holding
    // the word it annotates, so a change inside one is our own work coming back to us.
    for (const change of changes) {
      if (ours(change.target)) continue;
      for (const node of change.addedNodes) {
        if (ours(node)) continue;
        if (node.nodeType === Node.TEXT_NODE || node instanceof HTMLElement) arrived.add(node);
      }
    }
    if (arrived.size === 0) return;
    if (soon) clearTimeout(soon);
    soon = setTimeout(() => void pump(), 300);
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
      redraw();
    }
  });
  // And when a dictionary arrives or is given up, which is not a setting and used to leave
  // the page exactly as it was: a reader fetched the dictionary for the page they were
  // looking at and nothing on it changed.
  // And when lines of it have come back translated, which decides which word or which sense
  // some of its words are: what was drawn before was the dictionary's first.
  browser.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local') return;
    if ('arriving' in changes) {
      coming = (changes.arriving.newValue as Record<string, number> | undefined) ?? {};
      if (showing()) following?.();
    }
    if (!('heldPacks' in changes || 'linesTranslated' in changes)) return;
    redraw();
  });
  coming = ((await browser.storage.local.get('arriving')).arriving as Record<string, number>) ?? {};
  wholeWanted = true;
  await pump();
}

/** Draw the whole page again, once whatever pass is under way has finished. */
function redraw(): void {
  wholeWanted = true;
  void pump();
}
