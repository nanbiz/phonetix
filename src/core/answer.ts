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
}

export interface Answer {
  state: AnswerState;
  /** What the reader met, as it is written on the page. */
  spelling: string;
  /** The dictionary form, where that is a different word from the one on the page. */
  lemma: string | null;
  pos: string | null;
  ipa: string[];
  /** The first transcription, symbol by symbol: the card offers each sound on its own and
   *  does not hold a table to look them up in. */
  symbols: IpaSymbol[];
  /** The answer in the reader's own language, best first. More than one is an ambiguity the
   *  card shows rather than resolves. */
  says: string[];
  /** What the word means in English, which anchors an answer a machine guessed. */
  glosses: string[];
  /** The applying sense's example, where the dump had one. Never invented. */
  example: string | null;
  /** Each word this spelling is, where it is more than one. */
  readings: Reading[];
  /** Where the answer came from: a dictionary, a machine, or a synthesised voice. The card
   *  is the surface that most owes a reader that difference. */
  provenance: Provenance | null;
  source: string;
  target: string;
}

/** What this reading answers with, or its English meaning where it answered nothing. */
export function readingHeadline(reading: Reading): string | null {
  return reading.says[0] ?? reading.glosses[0] ?? null;
}

/**
 * The sense that applies, which the card leads with.
 *
 * The word itself where there is no sense at all. The spelling is kept off the headline
 * because the answer takes that place and the page is already showing the word.
 */
export function headline(answer: Answer): string | null {
  return (
    answer.says[0] ??
    answer.glosses[0] ??
    (answer.spelling.trim() !== '' && answer.ipa.length > 0 ? answer.spelling : null)
  );
}

/** Whether anything was found at all, which decides between a card and a message. */
export function found(answer: Answer): boolean {
  return headline(answer) !== null || answer.ipa.length > 0;
}

/** What is known about a word when all that is known is how it is said. */
export function ofTranscription(spelling: string, ipa: string, source: string): Answer {
  return {
    state: 'IpaOnly',
    spelling,
    lemma: null,
    pos: null,
    ipa: ipa.trim() === '' ? [] : [ipa],
    symbols: [],
    says: [],
    glosses: [],
    // Nothing said where it came from, because nothing here knows: this is what the overlay
    // already had, handed to a card as a last resort.
    provenance: null,
    example: null,
    readings: [],
    source,
    target: source,
  };
}
