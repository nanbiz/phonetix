// Where the readable text of a document is.
//
// This is the half of the work that cannot be shared: a page is a tree of nodes with styles
// and editing state, an Android screen is an accessibility tree, and only the browser side
// knows which of its nodes a reader is actually reading. What comes out is runs of plain text,
// which is all the core wants.
//
// Nothing here decides what a word is or whether it gets annotated. It decides what counts as
// text a person is reading.

/** A run of text and the node it came from, so what the core says can be drawn back onto it. */
export interface ScannedRun {
  id: number;
  text: string;
  node: Text;
  /** The language of the closest element that declares one, where it is not the page's. */
  lang?: string;
}

/**
 * Elements whose text is not prose.
 *
 * Code and its relatives are read as characters rather than as words, form controls carry the
 * reader's own typing, and the rest are not text at all. Annotating any of them makes a page
 * worse in a way no density setting can fix.
 */
const NOT_PROSE = new Set([
  'SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'TEXTAREA', 'INPUT', 'SELECT', 'OPTION',
  'CODE', 'PRE', 'KBD', 'SAMP', 'VAR', 'SVG', 'MATH', 'CANVAS',
  'VIDEO', 'AUDIO', 'IFRAME', 'OBJECT', 'EMBED', 'RUBY', 'RT', 'RP',
]);

/**
 * The furniture around the text: navigation, controls, banners.
 *
 * Named by role and by semantic tag rather than by class, because a word list of site-specific
 * class names is a maintenance burden that is wrong on the next site. The body of an article,
 * its headings and its main element are deliberately not here.
 */
const FURNITURE =
  'nav,footer,button,select,summary,' +
  '[role="navigation"],[role="menu"],[role="menubar"],[role="toolbar"],' +
  '[role="tablist"],[role="tab"],[role="banner"],[role="contentinfo"],' +
  '[role="search"],[role="button"],[aria-hidden="true"]';

/** Text that is not language: a URL, an address, a hash, a timestamp. */
const TECHNICAL = /(:\/\/|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|\d{4}-\d{2}-\d{2}T|\w+\.\w{2,}[/?#])/;

/** Our own windows, which must never be scanned into the page they are drawn over. */
export const OURS = 'phonetix-card-host';

/** Whether this element's subtree holds text a reader is reading. */
function readable(element: Element): boolean {
  if (NOT_PROSE.has(element.tagName)) return false;
  if (element.id === OURS) return false;
  if ((element as HTMLElement).isContentEditable) return false;
  if (element.closest(FURNITURE)) return false;
  const style = getComputedStyle(element);
  if (style.display === 'none' || style.visibility === 'hidden') return false;
  return true;
}

/** The language an element declares, or nothing when it inherits the page's. */
function declared(node: Text): string | undefined {
  const element = node.parentElement?.closest('[lang]');
  const lang = element?.getAttribute('lang')?.trim().split('-')[0].toLowerCase();
  return lang || undefined;
}

/**
 * The runs of text under a root, in the order they are read.
 *
 * A run is one text node, not a paragraph: the annotations are drawn back into the same nodes,
 * and a run spanning several would have to be cut apart again at exactly the boundaries the
 * page already has.
 */
export function scan(root: ParentNode = document.body, from = 0): ScannedRun[] {
  const runs: ScannedRun[] = [];
  if (!(root instanceof Element) && !(root instanceof Document)) return runs;
  const walker = document.createTreeWalker(root as Node, NodeFilter.SHOW_TEXT, {
    acceptNode(node: Node) {
      const text = node.nodeValue ?? '';
      if (text.trim().length < 2) return NodeFilter.FILTER_REJECT;
      if (TECHNICAL.test(text)) return NodeFilter.FILTER_REJECT;
      const parent = (node as Text).parentElement;
      if (!parent || !readable(parent)) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    },
  });
  let id = from;
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const text = node as Text;
    runs.push({ id: id++, text: text.nodeValue ?? '', node: text, lang: declared(text) });
  }
  return runs;
}
