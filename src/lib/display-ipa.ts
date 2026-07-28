// How a transcription is shown inline, given the reader's broad/narrow and stress
// settings. Kept pure and separate from the content script so it can be unit-tested:
// the tooltip always shows the full form, but the page text is stripped to taste.

/** Primary and secondary stress marks. */
export const STRESS_MARKS = /[ˈˌ]/g;

/**
 * Diacritics that mark narrow phonetic detail rather than which sound is meant.
 * "cause" is /kɔːz/ broadly and [kʰoːz̥] narrowly; stripping these turns the second
 * back into the first. Length, nasalization, syllabicity and the non-syllabic mark of
 * a diphthong are left in — they change the sound, not just its shade.
 */
export const NARROW_DETAIL = /[ʰʱ̥̬̝̞̟̠̪̺̻̊̈̚˞ˠ̴̘̙̹̜]/g;

export interface DisplayOpts {
  /** Keep the narrow-detail diacritics (broad form drops them). */
  narrow: boolean;
  /** Drop the stress marks from the inline text (the tooltip still shows them). */
  hideStress: boolean;
}

export function displayIpa(ipa: string, opts: DisplayOpts): string {
  let out = ipa;
  if (!opts.narrow) out = out.replace(NARROW_DETAIL, '');
  if (opts.hideStress) out = out.replace(STRESS_MARKS, '');
  return out;
}
