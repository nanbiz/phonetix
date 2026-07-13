// ─── Regional IPA transforms ─────────────────────────────────────────
// Ported from PolyPhoneme's LanguageRegions. Dictionary IPA is the standard
// (Castilian Spanish, European Portuguese); these rewrite it to a regional
// accent when the user selects one. Keyed by espeak voice/accent id.

// Only accents whose difference is a rule that holds for every word. An accent
// the dictionary has real data for is served from that data (see accents.ts);
// rewriting it here as well would apply the change twice.
const REGIONS: Record<string, [string, string][]> = {
  // Latin-American Spanish: seseo (θ→s), yeísmo (ʎ→ʝ). Wiktionary tags almost no
  // Spanish word for accent, while these two shifts hold across the vocabulary.
  'es-419': [['θ', 's'], ['ʎ', 'ʝ']],
};

/** Rewrite standard IPA to the accent's regional variant. No-op for standard accents. */
export function applyRegion(ipa: string, voice: string): string {
  const rules = REGIONS[voice];
  if (!rules || !ipa) return ipa;
  let out = ipa;
  for (const [from, to] of rules) out = out.split(from).join(to);
  return out;
}
