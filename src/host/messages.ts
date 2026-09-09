// What a page can ask the host, and nothing else.
//
// The host owns the core and the packs; a content script owns a document. Everything that
// crosses between them is here, so the boundary is one file rather than a habit.
import { defineExtensionMessaging } from '@webext-core/messaging';
import type { Answer } from '@/core/answer';
import type { AnnotateOptions, Batch, TextRun } from '@/core/tokens';

export interface HostProtocol {
  /** Open a language's pack, fetching it from the configured host the first time. Returns
   *  the language the pack turned out to be for, or null when there is no pack to be had. */
  openPack(data: { lang: string }): string | null;
  /** Which languages the core can answer for right now. */
  languages(data: Record<string, never>): string[];
  /** What the core says about one word, read from source into target. */
  lookUp(data: { word: string; source: string; target: string }): Answer;
  /** What a batch of runs gets drawn on it: one token per word, and the words the packs
   *  could not answer. */
  annotate(data: {
    runs: TextRun[];
    source: string;
    target: string;
    options: AnnotateOptions;
  }): Batch;
}

export const { sendMessage, onMessage } = defineExtensionMessaging<HostProtocol>();
