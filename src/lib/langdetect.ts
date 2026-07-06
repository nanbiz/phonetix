// ─── Per-block language decision ─────────────────────────────────────
// A block's language is the candidate whose dictionary best explains its words.
// Candidates come from eld's ranked guesses (which cover short text where its
// single top guess is unreliable) plus the page language. Dictionary coverage is
// the decisive, data-driven signal; eld only proposes candidates and breaks ties
// when coverage is weak everywhere (proper-noun titles, very short text).

/** Minimum coverage to switch away from the page language, and the margin the
 *  winner must beat the page language by (stability against noise). */
const MIN_COVERAGE = 0.4;
const SWITCH_MARGIN = 0.15;
/** Below this, no dictionary really explains the block; defer to a reliable eld. */
const WEAK_COVERAGE = 0.3;

/**
 * Choose a block's language.
 * @param pageLang    the page's language (the default / prior)
 * @param candidates  languages to weigh — the pool of every language eld proposed
 *                    anywhere on the page, so a short title's language is a
 *                    candidate even when its own few words don't trigger eld
 * @param eldReliable eld's reliability flag for this block
 * @param eldTop      eld's top guess for this block (tie-break when coverage is weak)
 * @param cov         coverage(lang) → fraction of the block's words in that dict
 */
export function pickLanguage(
  pageLang: string,
  candidates: string[],
  eldReliable: boolean,
  eldTop: string | undefined,
  cov: (lang: string) => number,
): string {
  const pool = new Set<string>([pageLang, ...candidates]);
  let best = pageLang;
  let bestCov = cov(pageLang);

  for (const c of pool) {
    if (c === pageLang) continue;
    const cv = cov(c);
    if (cv >= MIN_COVERAGE && cv > bestCov + SWITCH_MARGIN) {
      best = c;
      bestCov = cv;
    }
  }

  // No dictionary explains the block (names, non-Latin the page dict lacks):
  // trust eld's top guess if it was confident, otherwise keep the page language.
  if (bestCov < WEAK_COVERAGE && eldReliable && eldTop) {
    return eldTop;
  }
  return best;
}
