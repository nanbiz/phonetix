/**
 * Reduce a raw dictionary value to the one pronunciation a reader should see.
 *
 * Wiktionary-derived entries are not always a single clean transcription: an
 * entry can list several variants ("ðə, ði"), mark an optional sound with
 * parentheses ("ˈɑr(ə)m"), or carry leftovers of the source markup. Rendering
 * that raw puts a comma or a stray bracket into the middle of the page.
 *
 * Every rule here is structural, derived from the entry itself:
 *   - variants are separated by punctuation that IPA never uses
 *   - an optional segment is spelled with parentheses
 *   - a value may hold no more space-separated parts than its key does, so a
 *     one-word key whose value has a space is a variant list, not a phrase
 */

/** Punctuation that IPA itself never contains, so it can only separate variants.
 *  The word "or" counts only when it stands alone: \bor\b also matches inside a
 *  transcription such as ʔor.kestraː, where ː is a non-word character. */
const VARIANT_SEPARATOR = /\s*[,;~/]\s*|\s+or\s+/;

/** An optional sound: the parenthesised part may be dropped. */
const OPTIONAL_SEGMENT = /\([^)]*\)/g;

/** Markup leftovers that carry no phonetic meaning. */
const STRAY = /[[\]{}<>|"'`]/g;

/** How many space-separated parts a key stands for (a compound key spells its
 *  parts out, so its value is allowed the same number of parts). */
function keyParts(key: string): number {
  return key.split(/[\s\-–—_]+/).filter(Boolean).length;
}

/**
 * The single pronunciation for `key`, or '' when the entry holds nothing usable
 * (the caller then falls back to synthesis).
 */
export function normalizeIpa(key: string, raw: string): string {
  if (!raw) return '';

  let ipa = raw.trim();
  // A value delimited like a transcription (/…/ or […]) keeps only its inside.
  ipa = ipa.replace(/^[/[]+/, '').replace(/[/\]]+$/, '');

  // First variant only.
  ipa = ipa.split(VARIANT_SEPARATOR)[0] ?? '';

  ipa = ipa.replace(OPTIONAL_SEGMENT, '').replace(STRAY, '');
  ipa = ipa.replace(/\s+/g, ' ').trim();
  if (!ipa) return '';

  // More parts than the key spells out means the separator was lost and the
  // remainder is another variant, not a continuation of this one.
  const parts = ipa.split(' ');
  if (parts.length > keyParts(key)) return parts[0];

  return ipa;
}

/** A pronunciation fit to render: non-empty and free of the markup above. */
export function isCleanIpa(key: string, ipa: string): boolean {
  return ipa !== '' && ipa === normalizeIpa(key, ipa);
}
