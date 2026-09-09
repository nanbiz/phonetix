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

/** What the reader asked to see over a word. */
export type Layer = 'off' | 'gloss' | 'gloss+ipa' | 'ipa' | 'replace';

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
  const root = document.documentElement.classList;
  root.remove('px-ruby', 'px-ruby-2');
  if (layer === 'gloss' || layer === 'ipa') root.add('px', 'px-ruby');
  if (layer === 'gloss+ipa') root.add('px', 'px-ruby-2');
}

/** The words on screen, so a gesture can ask about the one under the cursor. */
const words = new WeakMap<Element, Token>();

/** What a painted word is about, or nothing when the element is not one of ours. */
export function wordAt(target: EventTarget | null): { element: HTMLElement; token: Token } | null {
  let node = target as Node | null;
  while (node && node !== document.body) {
    if (node instanceof HTMLElement) {
      const token = words.get(node);
      if (token) return { element: node, token };
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
function annotation(token: Token, layer: Layer): HTMLElement | null {
  const stack = span('px-rb');
  if ((layer === 'gloss' || layer === 'gloss+ipa') && token.gloss) {
    const gloss = span('px-gl', token.gloss);
    // A machine's answer is marked as one, in the inline layer as much as on the card.
    if (token.provenance?.kind === 'guess') gloss.classList.add('px-guess');
    stack.appendChild(gloss);
  }
  if ((layer === 'ipa' || layer === 'gloss+ipa') && token.ipa) {
    stack.appendChild(span('px-ph', token.ipa));
  }
  return stack.childElementCount > 0 ? stack : null;
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
  if (painted.length === 0) onDark = darkPage();
  const drawn = tokens.filter((token) => token.inline && token.run === run.id);
  if (drawn.length === 0) return;
  const parent = run.node.parentNode;
  if (!parent) return;

  const text = run.node.nodeValue ?? '';
  const pieces: Node[] = [];
  let at = 0;
  for (const token of drawn) {
    if (token.start < at || token.end > text.length) continue;   // the node moved under us
    if (token.start > at) pieces.push(document.createTextNode(text.slice(at, token.start)));
    const spelling = text.slice(token.start, token.end);
    // The theme the tokens are keyed by, on the box itself: every colour is defined inside
    // one, so a box naming no theme would have none of them.
    const box = span(`px-w theme-paper mode-${onDark ? 'dark' : 'light'}`);
    const mark = annotation(token, layer);
    if (layer === 'replace' && token.gloss) {
      // The word repainted as what it means, with the cue that it was swapped. The original
      // stays in the box's title so a reader can always see what was there.
      const swapped = span('px-rep', token.gloss);
      swapped.title = spelling;
      box.appendChild(swapped);
    } else {
      if (mark) box.appendChild(mark);
      box.appendChild(document.createTextNode(spelling));
    }
    words.set(box, token);
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

/**
 * Put every painted node back exactly as it was.
 *
 * In reverse, because a later paint may sit inside an earlier one's parent, and restoring
 * outwards puts a node back into a parent that is about to be replaced itself.
 */
export function unpaint(): void {
  document.documentElement.classList.remove('px', 'px-ruby', 'px-ruby-2');
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
