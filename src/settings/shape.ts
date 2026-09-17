// What the reader has chosen: the shape of it, and the questions a settings value alone
// answers.
//
// Nothing here touches storage, because both surfaces draw the same view from
// this and only one of them has a browser's. Each setting is one watched key where there is
// one. The defaults are here rather than at every reader of them,
// because a default repeated is a default that will be two different values the first time one
// of them is edited.
//
// The names are the same on both platforms; how they are stored is not, which is why this file
// is browser-side and the phone has its own store of the same keys.
import type { Layer } from '@/ext/content/inline';

export interface Settings {
  /** Whether the extension annotates at all. */
  on: boolean;
  /** What a word is replaced by: what it means, how it is said, or both. */
  layer: Layer;
  /** One word in every N, from the reader's frequency bar. */
  density: number;
  /** The language the reader is reading into. Empty until they choose one, and asked for
   *  only by the modes that turn a word into another language: see [readInto]. */
  target: string;
  /** The language of the page, when the reader overrides what the page declares. */
  source: string;
  /**
   * The language the reader is learning, which is what a word they are looking for comes
   * back in.
   *
   * Set where it is asked - in the panel the mark opens, and on the say screen - because that
   * is the one place a reader is thinking about it. Empty until they say, and then it is the
   * page in front of them or a language they keep a dictionary for.
   */
  learning: string;
  /**
   * The languages asked in lately, most recent first.
   *
   * The list a word can be asked for in is every language there is, and a reader asks in two
   * or three of them: kept so those are at the top of it rather than wherever the alphabet
   * puts them.
   */
  recent: string[];
  /** Narrow transcriptions rather than broad ones. The card always shows the full form. */
  narrow: boolean;
  /** Leave the stress marks off the line over a word. */
  hideStress: boolean;
  /**
   * Which accent to read each language in, as language tag to accent tag: en → en-us,
   * es → es-419.
   *
   * Per language rather than one for everything, because an accent is only meaningful
   * relative to a language: a page in German has nothing to do with the reader's choice
   * between British and American English, and one field for both meant choosing an accent
   * for a page threw away the choice made for every other. A language with no entry is read
   * the way its dictionary lists it, which is what most readers want.
   */
  accents: Record<string, string>;
  /** How long the cursor rests on a word before its card opens, in milliseconds. A reader who
   *  reads with the pointer wants it slow; one who looks words up wants it instant. */
  delay: number;
  /** Whether the card eases in and the reveal fades. Off is instant, which is what a reader
   *  who finds movement distracting wants and what a slow machine wants. */
  animations: boolean;
  /** Where the dictionaries come from. The reader's own, and nowhere in the source: an
   *  extension that went looking on its own would be an extension deciding who to talk to. */
  host: string;
  /** The palette everything of ours is drawn in: the card, the settings, the words on the
   *  page. The product's own unless the reader picks another. */
  theme: string;
  /** Which side of that palette: "system", "light" or "dark". Every palette has both, and
   *  following the device is only the default - a reader who keeps one app light on a dark
   *  phone is choosing for a reason. */
  dark: string;
  /** Sites the reader has switched off, by hostname. Everywhere else is on: a reader who
   *  wants this on the web does not want to name every site it should work on. */
  off: string[];
  /**
   * The mark at the edge of the screen, dragged over a word to be told what it means.
   *
   * The phone's, because a phone has no pointer to rest on a word. Declared here with the
   * rest because there is one settings shape for the product and one view drawn from it; a
   * surface that cannot do a thing does not draw its row.
   */
  lens: boolean;
  /** Which side of the screen the mark rests on: "right" or "left". The hand is taken to come
   *  onto the screen from that corner, and the circle is carried away from it. The phone's. */
  side: string;
  /** Whether the transcriptions themselves take touches, which costs every swipe that begins
   *  on one. The phone's. */
  touchWords: boolean;
  /** Which apps are read, when not all of them. The phone's. */
  apps: string[];
  /** Every app rather than the chosen ones. The phone's. */
  allApps: boolean;
}

export const DEFAULTS: Settings = {
  on: true,
  layer: 'meaning',
  density: 12,
  target: '',
  source: '',
  learning: '',
  recent: [],
  narrow: false,
  hideStress: true,
  accents: {},
  delay: 200,
  animations: false,
  host: '',
  theme: 'phonetix',
  dark: 'system',
  off: [],
  lens: true,
  side: 'right',
  touchWords: false,
  apps: [],
  allApps: true,
};


/**
 * The language to read into, which is nothing at all where the mode does not translate.
 *
 * Asked for here rather than read off `target` directly, because the two are one question:
 * what this does to a word, and what language that leaves it in.
 */
export function readInto(settings: Settings): string {
  // The mode is the answer: two of the three turn a word into another language and one does
  // not. A switch beside it saying the same thing again was a second way to say no, and a
  // reader who set one and not the other got a screen that did nothing.
  return settings.layer === 'meaning' || settings.layer === 'both' ? settings.target : '';
}

/** Whether the dark side of the palette is the one to draw, given what the device says. */
export function darkSide(settings: Settings, device: boolean): boolean {
  return settings.dark === 'system' ? device : settings.dark === 'dark';
}

/** Which accent this language is read in, or none, which is how its dictionary lists it. */
export function accentFor(settings: Settings, lang: string): string {
  return settings.accents[lang] ?? '';
}

/** Read one language in one accent, leaving the choice made for every other alone. */
export function setAccent(settings: Settings, lang: string, accent: string): Record<string, string> {
  const next = { ...settings.accents };
  if (accent === '') delete next[lang];
  else next[lang] = accent;
  return next;
}

/** Whether this site is one the reader switched off. */
export function allowed(settings: Settings, host: string): boolean {
  return settings.on && !settings.off.includes(host);
}

/** How many languages asked in lately are kept. Enough for the two or three a reader works
 *  in, few enough that the top of the list is still the top of the list. */
export const RECENT = 5;

/** The list of languages asked in lately, once one has been asked in again. */
export function asked(recent: string[], language: string): string[] {
  return [language, ...recent]
    .filter((lang) => lang !== '')
    .filter((lang, at, all) => all.indexOf(lang) === at)
    .slice(0, RECENT);
}

/**
 * The languages a word can be asked for in, in the order worth offering them.
 *
 * The one being asked in, then the ones asked in lately, then the ones a dictionary is held
 * for, and the rest of them behind those - a reader asking for the word for something usually
 * has no dictionary for it, which is why they are asking, so every language is on offer.
 */
export function offering(
  learning: string,
  recent: string[],
  held: string[],
  all: string[]
): string[] {
  return [...new Set([...(learning ? [learning] : []), ...recent, ...held, ...all])].filter(
    (lang) => lang !== ''
  );
}
