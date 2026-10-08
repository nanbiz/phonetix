// What the core says about one word, as the extension reads it.
//
// The shape is the core's, written by hand in core/src/json.rs and mirrored here and in
// Answer.kt. Nothing in this file interprets: a field that is empty is empty because the
// cascade found nothing, and the card says so rather than filling it in.

import type { Provenance } from './tokens';

/** How far a word got through the cascade, which is what the card draws from. */
export type AnswerState =
  | 'Entry'
  | 'Form'
  | 'Homograph'
  | 'Mono'
  | 'Guess'
  | 'Phrase'
  | 'IpaOnly'
  | 'None'
  | 'NoPack'
  | 'UnknownLang'
  | 'Loading';

/** One symbol of a transcription, with what is known about that sound. */
export interface IpaSymbol {
  token: string;
  name: string;
  /** Vowel, consonant, suprasegmental or diacritic. */
  kind: string;
  /** A word it is heard in, empty where the table has none. */
  example: string;
  /** A recording, an article, a diagram, a film of a mouth. Empty where there is none: a row
   *  that leads nowhere is worse than no row. */
  audio: string;
  wiki: string;
  diagram: string;
  seeing: string;
}

/** One of the words a spelling is. */
export interface Reading {
  pos: string | null;
  ipa: string[];
  says: string[];
  glosses: string[];
  /** What the dictionary marks each sense as, in the order of the glosses. */
  marks?: string[][];
  /** Each sense's example, where the dictionary keeps one, in the order of the glosses. */
  examples?: (string | null)[];
}

export interface Answer {
  state: AnswerState;
  /** The word the card leads with, which is the word the page draws over this one: "the" for
   *  German "die", where the dictionary's own first line is a note about its grammar. Absent
   *  where nothing was found to lead with. */
  lead?: string | null;
  /** What the reader met, as it is written on the page. */
  spelling: string;
  /** The dictionary form, where that is a different word from the one on the page. */
  lemma: string | null;
  /** What form the spelling is, where the dump named it: "plural", "past participle". The
   *  lemma alone does not say, and the relation is what a reader is trying to learn. */
  form: string | null;
  pos: string | null;
  ipa: string[];
  /** The first transcription, symbol by symbol: the card offers each sound on its own and
   *  does not hold a table to look them up in. */
  symbols: IpaSymbol[];
  /** The dictionary's narrow transcription of the first, where it gives one that differs,
   *  and its sounds: what a reader who asked for narrow transcriptions is shown. */
  narrow?: string | null;
  narrowSymbols?: IpaSymbol[];
  /** Which transcription [ipa] leads with: the narrow one is written between brackets. */
  detail?: 'broad' | 'narrow';
  /** The answer in the reader's own language, best first. More than one is an ambiguity the
   *  card shows rather than resolves. */
  says: string[];
  /** What the word means in English, which anchors an answer a machine guessed. */
  glosses: string[];
  /** What the dump marks each of those senses as, in the same order: "colloquial",
   *  "archaic", "Latin America". Worth knowing before a reader uses the word. */
  marks: string[][];
  /** The applying sense's example, where the dump had one. Never invented. */
  example: string | null;
  /** Every sense's example, where the dictionary keeps one, in the order of the glosses. */
  examples?: (string | null)[];
  /** Each word this spelling is, where it is more than one. */
  readings: Reading[];
  /** Where the answer came from: a dictionary, a machine, or a synthesised voice. The card
   *  is the surface that most owes a reader that difference. */
  provenance: Provenance | null;
  source: string;
  target: string;
  /** Which form of its lemma the word is, by grammatical category, and the word in every other
   *  value of each of those categories. Absent for a word the pack has no table for. */
  paradigm?: Paradigm | null;
}

/** One grammatical value of a place in a table: "preterite" of the category "tense". */
export interface PlaceValue {
  value: string;
  category: string;
}

/** The word in one place of its table. */
export interface ParadigmForm {
  place: PlaceValue[];
  spelling: string;
  /** Whether this is the place of the word the card is about. */
  here: boolean;
  /** What the word means said in this place, where the reader reads English: "he will walk". */
  said: string | null;
}

/** One category of a form, and the word in each of its values with every other value kept.
 *  "person" moves person and number together, which a grammar lays out as one grid. */
export interface ParadigmAlong {
  category: string;
  forms: ParadigmForm[];
}

/** Which form of its lemma a word is, as core/src/paradigm.rs decides it. */
export interface Paradigm {
  /** The word's values, in the order a card names them. */
  place: PlaceValue[];
  /** Where the word's own ending starts, in characters: what comes before it is shared with
   *  the lemma. */
  endingAt: number;
  /** What the word means said in this place: "he walked". */
  said: string | null;
  along: ParadigmAlong[];
}

/** What this reading answers with, or its English meaning where it answered nothing. */
export function readingHeadline(reading: Reading): string | null {
  return reading.says[0] ?? reading.glosses[0] ?? null;
}

/** The sense that applies, which the card leads with. */
export function headline(answer: Answer): string | null {
  // The word itself is never the headline. The row above the card already names it and the
  // transcription under it is the same word again, so leading with it showed the reader the
  // same word three times over; a card with no sense to lead with leads with nothing.
  return answer.lead || answer.says[0] || answer.glosses[0] || null;
}

/**
 * Whether anything was found at all, which decides between a card and a message.
 *
 * Not the spelling: that is the word the reader is already pointing at, and counting it made
 * this true of every answer there is, so a lookup that reached nothing stood in front of the
 * transcription that was already in hand.
 */
export function found(answer: Answer): boolean {
  return headline(answer) !== null || answer.ipa.length > 0 || answer.state === 'Phrase';
}

/** What is known about a word when all that is known is how it is said. */
export function ofTranscription(spelling: string, ipa: string, source: string): Answer {
  return {
    state: 'IpaOnly',
    spelling,
    lemma: null,
    form: null,
    pos: null,
    ipa: ipa.trim() === '' ? [] : [ipa],
    symbols: [],
    says: [],
    glosses: [],
    marks: [],
    // Nothing said where it came from, because nothing here knows: this is what the overlay
    // already had, handed to a card as a last resort.
    provenance: null,
    example: null,
    readings: [],
    source,
    target: source,
  };
}

/** The answer at the detail the reader asked for: led by the dictionary's narrow transcription
 *  where they asked for narrow ones and it gives one, and by the broad one otherwise. */
export function atDetail(answer: Answer, narrow: boolean): Answer {
  if (!narrow || !answer.narrow) return { ...answer, detail: 'broad' };
  return {
    ...answer,
    ipa: [answer.narrow, ...answer.ipa.filter((ipa) => ipa !== answer.narrow)],
    symbols: answer.narrowSymbols ?? [],
    detail: 'narrow',
  };
}
