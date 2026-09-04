/**
 * Which words get transcribed, and how the frequency bar maps to that.
 *
 * This module is the single definition of that behaviour. The extension imports it
 * directly; the Android app carries a port of it, and `scripts/gen-sprinkle-vectors.ts`
 * writes the cases in `shared/sprinkle-vectors.json` from the functions here, which the
 * port asserts itself against. A change made here that is not carried across fails that
 * test rather than quietly giving the same setting two different meanings on two devices.
 *
 * Nothing here touches the DOM or any browser API, so it runs the same under node.
 */

/** One word in every N: 2 is every other word, 50 is a rare sprinkle. */
export const DENSITY_MIN = 2;
export const DENSITY_MAX = 50;

/**
 * How far the frequency bar is bent from linear toward geometric.
 *
 * The bar controls a frequency, and frequency is felt in ratios rather than in N: the step
 * from 1-in-2 to 1-in-4 is a world apart, 1-in-40 to 1-in-42 is nothing. A straight linear
 * N spends most of the travel among sparse densities no one can tell apart. Bending it
 * partway keeps the ends honest while giving the dense end the resolution.
 */
const LOG_MIX = 0.6;

/** Deterministic 32-bit hash (FNV-1a), so a word is picked the same way on every pass
 *  and the transcriptions never flicker between renders. */
export function hashStr(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

/** Position 0 (sparse, 1 in 50) to 1 (dense, 1 in 2). */
export function densityForPos(t: number): number {
  const tt = Math.min(1, Math.max(0, t));
  const lin = DENSITY_MAX - tt * (DENSITY_MAX - DENSITY_MIN);
  const geo = DENSITY_MAX * Math.pow(DENSITY_MIN / DENSITY_MAX, tt);
  const n = Math.round((1 - LOG_MIX) * lin + LOG_MIX * geo);
  return Math.min(DENSITY_MAX, Math.max(DENSITY_MIN, n));
}

/** The inverse, by scan, so it stays exactly consistent with the curve above. */
export function posForDensity(d: number, steps = 100): number {
  let best = 0;
  let bestErr = Infinity;
  for (let i = 0; i <= steps; i++) {
    const err = Math.abs(densityForPos(i / steps) - d);
    if (err < bestErr) { bestErr = err; best = i; }
  }
  return best / steps;
}

/**
 * Whether this occurrence of a word is one of the transcribed ones.
 *
 * Two things shape the gate. Rarer words are the ones worth stopping on, so it favours
 * them, with length standing in for rarity — function words are short, and the words a
 * reader wants to learn run longer, so a longer word shrinks N and is picked more often.
 * And the gate keys on the word's occurrence index, so repeats of one word are decided
 * independently and land as a mix down the page rather than all-or-nothing: a word that is
 * always transcribed has nothing left to teach after the first time.
 *
 * `word` is expected lowercased; `occurrence` counts from 0 within one render.
 */
export function picks(word: string, occurrence: number, density: number): boolean {
  const boost = Math.min(2.4, Math.max(0.6, word.length / 5));
  const n = Math.max(1, Math.round(density / boost));
  return hashStr(`${word}#${occurrence}`) % n === 0;
}
