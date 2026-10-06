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

/**
 * One text node that was painted.
 *
 * The node itself is kept, detached, rather than what it said: putting it back is then the
 * page's own node in the page's own place, and a scan after that finds the same node it found
 * before, so a word that reads the same is a word left alone.
 */
interface Painted {
  node: Text;
  /** The elements that took the text node's place, in order. */
  drawn: Node[];
  /** What was drawn over it, so a pass that would draw the same leaves it as it is. */
  said: string;
}

/** Every painted node, by the node, in the order they were painted. */
const painted = new Map<Text, Painted>();

/** The page's text between painted words, which this cut out of the page's own node, each with
 *  the node it was cut from: the page's words, but not a node the page made, and not one to be
 *  read again as new. */
const cut = new WeakMap<Node, { node: Text; at: number }>();

/** The page's own node a piece of text was cut from and where in it the piece starts, or the
 *  node itself where it was not cut. */
export function originOf(node: Text): { node: Text; at: number } {
  return cut.get(node) ?? { node, at: 0 };
}

/** Whether a node is one this put in the page, a painted word or the text between them. */
export function drewIt(node: Node): boolean {
  if (cut.has(node)) return true;
  const element = node instanceof Element ? node : node.parentElement;
  return element?.closest(`.${WORD}`) != null;
}
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

/** What one run has drawn over it, written so two passes that would draw the same compare equal. */
function saying(tokens: Token[], layer: Layer): string {
  return JSON.stringify([layer, theme, side, tokens.map((token) => [
    token.start, token.end, token.ipa, token.gloss, token.glossIpa, token.state,
    token.provenance?.kind,
  ])]);
}

/**
 * Paint one run's tokens over its text node.
 *
 * The node is replaced by the pieces of itself: the text between the annotated words as it
 * was, and each annotated word inside a box carrying its annotation. A node already painted
 * with exactly this is left as it is, and one painted differently is put back and painted
 * again in the same step, so a reader never sees a word go back to the page's spelling on its
 * way to a new reading.
 */
export function paint(run: ScannedRun, tokens: Token[], layer: Layer): void {
  const drawn = layer === 'off'
    ? []
    : tokens.filter((token) => token.inline && token.run === run.id);
  const said = saying(drawn, layer);
  const was = painted.get(run.node);
  if (was?.said === said) return;
  if (was) restore(was);
  if (drawn.length === 0) return;
  room(layer);
  if (painted.size === 0) onDark = darkHere();
  const parent = run.node.parentNode;
  if (!parent) return;

  const text = run.node.nodeValue ?? '';
  const pieces: Node[] = [];
  let at = 0;
  // The word before the one being painted, within this run. A run is a line the page drew, so
  // the first word of one has no neighbour rather than borrowing the last word of another.
  let before = '';
  for (const token of drawn) {
    if (token.start < at || token.end > text.length) continue;   // the node moved under us
    if (token.start > at) pieces.push(between(text.slice(at, token.start), run.node, at));
    const spelling = text.slice(token.start, token.end);
    // The theme the tokens are keyed by, on the box itself: every colour is defined inside
    // one, so a box naming no theme would have none of them.
    const box = span(`${WORD} ${themeOf(onDark, theme)}`);
    const swapped = instead(token, layer);
    if (swapped) {
      // The word repainted as the answer, with the word itself kept beside it for a tap to
      // show. No title: the card names the word, and the browser's tooltip drew the same
      // word a second time beside it.
      box.appendChild(swapped);
      box.appendChild(span('px-was', spelling));
    } else {
      box.appendChild(document.createTextNode(spelling));
    }
    words.set(box, token);
    neighbours.set(box, before);
    before = spelling;
    pieces.push(box);
    at = token.end;
  }
  if (pieces.length === 0) return;
  if (at < text.length) pieces.push(between(text.slice(at), run.node, at));

  const fragment = document.createDocumentFragment();
  for (const piece of pieces) fragment.appendChild(piece);
  parent.replaceChild(fragment, run.node);
  painted.set(run.node, { node: run.node, drawn: pieces, said });
}

/** A stretch of the page's text between two painted words, cut from the page's node where
 *  it starts at `at`. */
function between(text: string, from: Text, at: number): Text {
  const node = document.createTextNode(text);
  cut.set(node, { node: from, at });
  return node;
}

/** Put one painted node back where its pieces are. */
function restore(record: Painted): void {
  painted.delete(record.node);
  const first = record.drawn.find((piece) => piece.parentNode);
  // The page took the pieces out itself: there is nowhere left to put the node back.
  if (!first?.parentNode) return;
  first.parentNode.insertBefore(record.node, first);
  for (const piece of record.drawn) piece.parentNode?.removeChild(piece);
}

/**
 * Run something over the page as the page wrote it, and put every painted word back after.
 *
 * In one step, so nothing is drawn in between: a scan has to see the page's own text nodes,
 * and a reader must not see the page lose its annotations for the length of a pass.
 */
export function asWritten<T>(work: () => T): T {
  const held = [...painted.values()].filter((record) =>
    record.drawn.some((piece) => piece.parentNode)
  );
  for (const record of [...held].reverse()) {
    const first = record.drawn.find((piece) => piece.parentNode);
    first?.parentNode?.insertBefore(record.node, first);
    for (const piece of record.drawn) piece.parentNode?.removeChild(piece);
  }
  try {
    return work();
  } finally {
    for (const record of held) {
      const parent = record.node.parentNode;
      if (!parent) continue;
      const fragment = document.createDocumentFragment();
      for (const piece of record.drawn) fragment.appendChild(piece);
      parent.replaceChild(fragment, record.node);
    }
  }
}

/** Put back every painted node that is not one of these, which a pass no longer draws on. */
export function keepOnly(nodes: Set<Text>): void {
  for (const record of [...painted.values()].reverse()) {
    if (!nodes.has(record.node)) restore(record);
  }
  if (painted.size === 0) document.documentElement.classList.remove('px');
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
  unreveal();
  keepOnly(new Set());
}

