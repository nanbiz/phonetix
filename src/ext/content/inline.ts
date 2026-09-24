// Drawing what the core decided onto the page.
//
// The core says which words are annotated, what each says and how it is said; this puts that
// over the words without disturbing the text underneath. Every change is remembered exactly,
// because a reader who switches the extension off is owed the page they had, character for
// character, and a page restored approximately is a page quietly rewritten.
//
// The class names and the rules that style them are generated from the design page, so the
// annotation over a word in a browser and the one over a word on a phone are the same design.
import type { Token } from '@/core/tokens';
import type { ScannedRun } from './scan';
import { THEME, themeOf } from '@/ui/theme';

/** What the reader asked to see over a word. */
export type Layer = 'off' | 'meaning' | 'sound' | 'both';

/** What one annotated word is wrapped in. Named once: the session recognises its own work by it. */
export const WORD = 'px-w';

/** One text node that was painted, and what it held before. */
interface Painted {
  /** The elements that took the text node's place, in order. */
  drawn: Node[];
  /** Exactly what the node said, so it can be put back as it was. */
  was: string;
  parent: Node;
}

const painted: Painted[] = [];

/**
 * Make room above the line for what will be drawn there.
 *
 * The annotation is positioned over the word rather than in the line, so the space it needs
 * has to be asked for: without it the gloss sits on the line above and the transcription lands
 * on the word itself. One line of annotation or two is the reader's choice, and the two
 * heights are the design page's own.
 */
function room(layer: Layer): void {
  // Nothing to make room for: the answer takes the word's place rather than sitting above the
  // line, so the page keeps its own layout. The page used to be given a taller line height for
  // a ruby line that no longer exists.
  if (layer !== 'off') document.documentElement.classList.add('px');
}

/** The palette the reader chose, which the words are drawn in as much as the card. */
let theme = THEME;
/** Which side of it: the page's own by default, or the one the reader insisted on. */
let side = 'system';

/** Draw in this palette from now on. The page is repainted by whoever changed the setting. */
export function paintedIn(chosen: string, lightOrDark = 'system'): void {
  theme = chosen;
  side = lightOrDark;
}

/**
 * Whether to draw the dark side of the palette over this page.
 *
 * The page's own by default, because an annotation is read against the text it replaces and a
 * dark card on a white page is a hole in it. A reader who has said light or dark means it
 * everywhere, so their answer outranks the page's.
 */
export function darkHere(): boolean {
  return side === 'system' ? darkPage() : side === 'dark';
}

/** The words on screen, so a gesture can ask about the one under the cursor. */
const words = new WeakMap<Element, Token>();

/**
 * The word before each annotated one, as the page has it.
 *
 * A spelling that is several words is decided by what surrounds it, and the card asks the core
 * the same question the inline layer already answered. Kept per element rather than looked up
 * again from the page, because the page's text is what was painted over and the run it came
 * from is right here.
 */
const neighbours = new WeakMap<Element, string>();

/** What a painted word is about, or nothing when the element is not one of ours. */
export function wordAt(
  target: EventTarget | null
): { element: HTMLElement; token: Token; before: string } | null {
  let node = target as Node | null;
  while (node && node !== document.body) {
    if (node instanceof HTMLElement) {
      const token = words.get(node);
      if (token) return { element: node, token, before: neighbours.get(node) ?? '' };
    }
    node = node.parentNode;
  }
  return null;
}

/**
 * Whether the annotation is drawn for a dark page or a light one.
 *
 * From the page's own background rather than from the browser's setting: an annotation is
 * read against the page it is drawn on, and a reader with a dark system theme on a white page
 * would otherwise get pale ink on white. The colour is looked for upwards, because an element
 * with no background of its own shows its parent's.
 */
export function darkPage(): boolean {
  let element: Element | null = document.body;
  while (element) {
    const colour = getComputedStyle(element).backgroundColor;
    const parts = colour.match(/[\d.]+/g);
    if (parts && parts.length >= 3 && (parts.length < 4 || Number(parts[3]) > 0.5)) {
      const [red, green, blue] = parts.map(Number);
      // The usual weighting of what the eye takes as brightness.
      return (red * 0.299 + green * 0.587 + blue * 0.114) / 255 < 0.5;
    }
    element = element.parentElement;
  }
  // Nothing declared a background, so the browser paints the canvas: white, unless the page
  // asked for a dark one. The reader's own system setting is deliberately not consulted, since
  // a dark system on a page that stayed white is exactly the case that put pale ink on white.
  const scheme = getComputedStyle(document.documentElement).colorScheme;
  if (scheme.includes('dark') && !scheme.includes('light')) return true;
  // And where the page says nothing at all, its own text says which way round it is.
  const ink = getComputedStyle(document.body).color.match(/[\d.]+/g);
  if (ink && ink.length >= 3) {
    const [red, green, blue] = ink.map(Number);
    return (red * 0.299 + green * 0.587 + blue * 0.114) / 255 > 0.6;
  }
  return false;
}

/** Decided once per pass: a page does not change colour between two words. */
let onDark = false;

function span(className: string, text?: string): HTMLSpanElement {
  const element = document.createElement('span');
  element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}

/**
 * The annotation over one word: what it means, how it is said, or both.
 *
 * Absolutely positioned above the word rather than in the line, so a page's own line height
 * is not pushed apart by an annotation the page never planned for.
 */
/**
 * What takes the word's place: what it means, how it is said, or both.
 *
 * Always in place of the word rather than above it. A line over a word is something a reader
 * has to be pointing at to read, and half the readers of this have no pointer at all; the word
 * itself is where the answer goes, and the word comes back where they ask for it.
 */
function instead(token: Token, layer: Layer): HTMLElement | null {
  if (layer === 'sound') {
    if (!token.ipa) return null;
    const said = span('px-rep');
    said.appendChild(span('px-ph', token.ipa));
    return said;
  }
  // With both, how the translation is said, which is what replaces the word: the word is
  // always replaced by one thing, and drawn beside the translation the transcription was the
  // part a reader who asked for pronunciation could not find.
  if (layer === 'both' && token.glossIpa) {
    const said = span('px-rep');
    said.appendChild(span('px-ph', token.glossIpa));
    return said;
  }
  if (!token.gloss) return null;
  const swapped = span('px-rep');
  // Marked for what each piece is: a meaning and a pronunciation are drawn differently and
  // read differently, and anything looking at the page - the styles, a check - asks which it
  // is rather than reading the text of both at once.
  const meaning = span('px-gl', token.gloss);
  // A machine's answer is marked as one, in the page as much as on the card.
  if (token.provenance?.kind === 'guess') meaning.classList.add('px-guess');
  swapped.appendChild(meaning);
  return swapped;
}

/**
 * Paint one run's tokens over its text node.
 *
 * The node is replaced by the pieces of itself: the text between the annotated words as it
 * was, and each annotated word inside a box carrying its annotation. The pieces are kept so
 * that putting the node back is a single replacement rather than an unpicking.
 */
export function paint(run: ScannedRun, tokens: Token[], layer: Layer): void {
  if (layer === 'off') return;
  room(layer);
  if (painted.length === 0) onDark = darkHere();
  const drawn = tokens.filter((token) => token.inline && token.run === run.id);
  if (drawn.length === 0) return;
  const parent = run.node.parentNode;
  if (!parent) return;

  const text = run.node.nodeValue ?? '';
  const pieces: Node[] = [];
  let at = 0;
  // The word before the one being painted, within this run. A run is a line the page drew, so
  // the first word of one has no neighbour rather than borrowing the last word of another.
  let said = '';
  for (const token of drawn) {
    if (token.start < at || token.end > text.length) continue;   // the node moved under us
    if (token.start > at) pieces.push(document.createTextNode(text.slice(at, token.start)));
    const spelling = text.slice(token.start, token.end);
    // The theme the tokens are keyed by, on the box itself: every colour is defined inside
    // one, so a box naming no theme would have none of them.
    const box = span(`${WORD} ${themeOf(onDark, theme)}`);
    const swapped = instead(token, layer);
    if (swapped) {
      // The word repainted as the answer, with the word itself kept beside it: a reader who
      // wants to know what was there asks for it - a rest, a tap - rather than hunting for a
      // tooltip the browser draws whenever it feels like it.
      swapped.title = spelling;
      box.appendChild(swapped);
      box.appendChild(span('px-was', spelling));
    } else {
      box.appendChild(document.createTextNode(spelling));
    }
    words.set(box, token);
    neighbours.set(box, said);
    said = spelling;
    pieces.push(box);
    at = token.end;
  }
  if (pieces.length === 0) return;
  if (at < text.length) pieces.push(document.createTextNode(text.slice(at)));

  const fragment = document.createDocumentFragment();
  for (const piece of pieces) fragment.appendChild(piece);
  parent.replaceChild(fragment, run.node);
  painted.push({ drawn: pieces, was: text, parent });
}

/** The word whose original is being shown, so the last one comes down when the next goes up. */
let revealed: HTMLElement | null = null;

/**
 * Show what a swapped word said, in place, over the swap.
 *
 * The layer needs a ground of its own or both forms show through each other, and the only
 * ground that is right is the one the word sits on: the nearest background up the page, since
 * an element with none of its own shows its parent's.
 */
export function reveal(box: HTMLElement): void {
  if (!box.querySelector('.px-was')) return;
  if (revealed && revealed !== box) revealed.classList.remove('px-showing');
  box.style.setProperty('--px-page-bg', behind(box));
  box.classList.add('px-showing');
  revealed = box;
}

/** Put the swap back. */
export function unreveal(): void {
  revealed?.classList.remove('px-showing');
  revealed = null;
}

/** The nearest solid background behind an element, walking up to the page. */
function behind(element: HTMLElement | null): string {
  for (let at: HTMLElement | null = element; at; at = at.parentElement) {
    const colour = getComputedStyle(at).backgroundColor;
    if (colour && colour !== 'transparent' && !colour.startsWith('rgba(0, 0, 0, 0)')) {
      return colour;
    }
  }
  const body = getComputedStyle(document.body).backgroundColor;
  return body && !body.startsWith('rgba(0, 0, 0, 0)') ? body : '#fff';
}

/**
 * Put every painted node back exactly as it was.
 *
 * In reverse, because a later paint may sit inside an earlier one's parent, and restoring
 * outwards puts a node back into a parent that is about to be replaced itself.
 */
export function unpaint(): void {
  document.documentElement.classList.remove('px');
  revealed = null;
  for (let i = painted.length - 1; i >= 0; i--) {
    const { drawn, was, parent } = painted[i];
    const first = drawn[0];
    if (!first.parentNode) continue;   // the page removed it before we could
    const restored = document.createTextNode(was);
    first.parentNode.insertBefore(restored, first);
    for (const piece of drawn) piece.parentNode?.removeChild(piece);
    void parent;
  }
  painted.length = 0;
}

/** Whether anything is painted, so a caller can tell a fresh page from a painted one. */
export function isPainted(): boolean {
  return painted.length > 0;
}
