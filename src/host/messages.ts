// What a page can ask the host, and nothing else.
//
// The host owns the core and the packs; a content script owns a document. Everything that
// crosses between them is here, so the boundary is one file rather than a habit.
//
// It is written on chrome.runtime directly. The boundary is four messages and a reply, and a
// library in the middle of it was one more place for a message to disappear between a page and
// a service worker with nothing to show for it.
import type { Answer, IpaSymbol } from '@/core/answer';
import type { AnnotateOptions, Batch, TextRun } from '@/core/tokens';
import type { Offered } from './packs';

export interface HostProtocol {
  /** Open a language's pack, fetching it from the configured host the first time. Returns
   *  the language the pack turned out to be for, or null when there is no pack to be had. */
  openPack: { data: { lang: string }; reply: string | null };
  /** Which languages the core can answer for right now. */
  languages: { data: Record<string, never>; reply: string[] };
  /** What the core says about one word, read from source into target. */
  lookUp: { data: { word: string; source: string; target: string }; reply: Answer };
  /** What a batch of runs gets drawn on it: one token per word, and the words the packs
   *  could not answer. */
  annotate: {
    data: { runs: TextRun[]; source: string; target: string; options: AnnotateOptions };
    reply: Batch;
  };
  /** What each position of the reader's frequency bar means, as one word in every N. */
  curve: { data: Record<string, never>; reply: number[] };
  /** Which languages have a pack on this machine, which of them are open, and what the
   *  reader's host has to offer. */
  packs: {
    data: Record<string, never>;
    reply: { held: string[]; open: string[]; offered: Offered[] };
  };
  /** Fetch a language's pack and open it, or give one up. */
  getPack: { data: { lang: string }; reply: string | null };
  forgetPack: { data: { lang: string }; reply: boolean };
  /** A picture of the mouth making a sound, as a data URL: a page's own policy would refuse
   *  the load, and the host has no such policy. */
  diagram: { data: { file: string; width: number }; reply: string };
  /** A transcription, symbol by symbol, for a surface with no answer to read them off. */
  symbols: { data: { ipa: string }; reply: IpaSymbol[] };
  /** Any sound from the network, as bytes: a recording of a sound made by a person. */
  fetch: { data: { url: string }; reply: number[] };
  /** Say one word: WAV bytes, because a page's own media policy can block an audio element
   *  loading a URL and cannot block Web Audio playing bytes. */
  speak: { data: { word: string; lang: string }; reply: number[] };
}

type Named = keyof HostProtocol;

/** What travels: the name of the question and its data, and nothing else. */
interface Asked<K extends Named = Named> {
  phonetix: K;
  data: HostProtocol[K]['data'];
}

/** What comes back, so a thrown error on the far side arrives as one here. */
type Answered<K extends Named> = { ok: HostProtocol[K]['reply'] } | { failed: string };

function isAsked(message: unknown): message is Asked {
  return typeof message === 'object' && message !== null && 'phonetix' in message;
}

/** Ask the host something. */
export async function sendMessage<K extends Named>(
  name: K,
  data: HostProtocol[K]['data']
): Promise<HostProtocol[K]['reply']> {
  const asked: Asked<K> = { phonetix: name, data };
  const answered = (await chrome.runtime.sendMessage(asked)) as Answered<K> | undefined;
  if (!answered) throw new Error(`the host did not answer ${name}`);
  if ('failed' in answered) throw new Error(answered.failed);
  return answered.ok;
}

const handlers = new Map<Named, (data: unknown) => Promise<unknown>>();
let listening = false;

/**
 * Answer one kind of question.
 *
 * One listener for all of them, because a listener per message type is a listener per type
 * that has to decide whether a message is its own, and the ones that decide wrongly are the
 * ones that answer twice.
 */
export function onMessage<K extends Named>(
  name: K,
  handle: (message: { data: HostProtocol[K]['data'] }) => Promise<HostProtocol[K]['reply']>
): void {
  handlers.set(name, (data) => handle({ data: data as HostProtocol[K]['data'] }));
  if (listening) return;
  listening = true;
  chrome.runtime.onMessage.addListener((message, _sender, respond) => {
    if (!isAsked(message)) return false;
    const handler = handlers.get(message.phonetix);
    if (!handler) return false;
    handler(message.data)
      .then((ok) => respond({ ok }))
      .catch((e) => respond({ failed: String(e) }));
    // The reply comes later, and a listener that does not say so has its channel closed
    // before the answer is ready.
    return true;
  });
}
