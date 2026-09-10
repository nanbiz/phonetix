// Where the things a card points at actually live.
//
// One place, because these addresses are built on both sides of the extension and on the
// phone: the card links to an entry, the content script fetches a recording, the host fetches
// a diagram. Written differently in three places, they are three chances for one of them to
// be quietly wrong about a word with an apostrophe in it.

/** A file Wikimedia Commons holds, by its name. */
export function commons(file: string): string {
  return `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(file)}`;
}

/**
 * The same file rastered to a width.
 *
 * Commons serves the sagittal sections as SVG, and a page cannot be handed an SVG it did not
 * fetch itself, so it is asked for at the size it will be drawn at.
 */
export function commonsAt(file: string, width: number): string {
  return `${commons(file)}?width=${width}`;
}

/** A word's own entry, which is where a reader who wants the whole thing goes. */
export function wiktionary(word: string): string {
  return `https://en.wiktionary.org/wiki/${encodeURIComponent(word)}`;
}

/** What is known about one sound, on the article the symbol table names. */
export function wikipedia(article: string): string {
  return `https://en.wikipedia.org/wiki/${article}`;
}

/** A word's Wiktionary page as its own source, which is what the core reads for a recording. */
export function wiktionarySource(word: string): string {
  return `https://en.wiktionary.org/w/index.php?action=raw&title=${encodeURIComponent(word)}`;
}

/** Films of a real mouth saying it. */
export function seeingSpeech(path: string): string {
  return `https://seeingspeech.ac.uk/${path}`;
}
