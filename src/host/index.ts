// The host: one core, the packs it has open, and the answers it gives.
//
// One instance and one place, because a pack is tens of megabytes and a copy per tab would
// be a copy per tab. Nothing here decides what a word means; that is the core's, compiled
// once and run on both platforms.
import { annotate, lookUp, openLanguages } from '@/core';
import { ofTranscription } from '@/core/answer';
import { onMessage } from './messages';
import { open } from './packs';

/** Start answering. Called once, by the background entry point. */
export function host(): void {
  onMessage('openPack', async ({ data }) => {
    try {
      return await open(data.lang);
    } catch (e) {
      console.warn(`[Phonetix] No pack for ${data.lang}:`, e);
      return null;
    }
  });

  onMessage('languages', async () => {
    try {
      return await openLanguages();
    } catch (e) {
      console.warn('[Phonetix] The core did not start:', e);
      return [];
    }
  });

  onMessage('annotate', async ({ data }) => {
    // A page in a language with no pack is still annotated: the states say what each word
    // reached, and a host that got no tokens could not tell a missing pack from a blank page.
    await open(data.source).catch(() => null);
    return annotate(data.runs, data.source, data.target, data.options);
  });

  onMessage('lookUp', async ({ data }) => {
    try {
      return await lookUp(data.word, data.source, data.target);
    } catch (e) {
      console.warn(`[Phonetix] Lookup failed for ${data.word}:`, e);
      // A cascade that could not run is not a cascade that found nothing. The state says
      // which, so the card can offer the pack rather than claim the word does not exist.
      return { ...ofTranscription(data.word, '', data.source), state: 'NoPack' as const };
    }
  });
}
