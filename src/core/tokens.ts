// What the core says about a page: one token per word, and what it could not answer.
//
// The shape is the core's, written in core/src/json.rs. Nothing here decides anything: which
// words are annotated, what they mean and how they are said are all settled before this
// crosses the boundary, so a browser and a phone cannot annotate the same sentence
// differently.
import type { AnswerState } from './answer';

/** A run of text as the host found it: a text node in a page, a line of a screen. */
export interface TextRun {
  id: number;
  text: string;
  /** The language of this run where the host knows it differs from the page's. */
  lang?: string;
}

/** Where an answer came from, which the interface shows and never hides. */
export type Provenance =
  | { kind: 'dictionary'; pack: string }
  | { kind: 'guess'; engine: string }
  | { kind: 'synthesised' };

/** One word of a run, with everything needed to draw it and nothing needing a second ask. */
export interface Token {
  run: number;
  /** UTF-16 offsets into the run's own text, which is how a host indexes it. */
  start: number;
  end: number;
  spelling: string;
  lang: string;
  state: AnswerState;
  /** The headline gloss, already cut to what an inline annotation can carry. */
  gloss: string | null;
  ipa: string | null;
  /** Whether the sprinkle chose this word for an inline annotation. */
  inline: boolean;
  provenance: Provenance | null;
}

/** What a word the core could not answer needs from the host's engines. */
export interface Miss {
  token: number;
  need: 'Gloss' | 'Ipa' | 'Both';
}

/** One pass over a batch of runs. */
export interface Batch {
  batch: number;
  tokens: Token[];
  misses: Miss[];
}

/** What the reader asked the inline layer to show. */
export type InlineMode = 'off' | 'gloss' | 'gloss+ipa' | 'ipa' | 'replace';

/** How a batch is asked for: the reader's settings, not the platform's habits. */
export interface AnnotateOptions {
  mode: InlineMode;
  /** One word in every N, from the reader's frequency bar. */
  density: number;
  /** Narrow transcriptions rather than broad ones. */
  narrow?: boolean;
  /** Leave the stress marks off the line over a word. The card always shows them. */
  hideStress?: boolean;
  /** The accent the reader wants, where its difference from the standard is a rule. */
  accent?: string;
  /** Spellings the reader has opened a card for, which stay annotated afterwards. */
  seen?: string[];
}
