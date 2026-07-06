// ─── Regional IPA transforms ─────────────────────────────────────────
// Ported from PolyPhoneme's LanguageRegions. Dictionary IPA is the standard
// (Castilian Spanish, European Portuguese); these rewrite it to a regional
// accent when the user selects one. Keyed by espeak voice/accent id.

const REGIONS: Record<string, [string, string][]> = {
  // Latin-American Spanish: seseo (θ→s), yeísmo (ʎ→ʝ)
  'es-419': [['θ', 's'], ['ʎ', 'ʝ']],
  // Brazilian Portuguese: reduced central vowels open/front
  'pt-br': [['ɐ', 'a'], ['ɨ', 'i']],
};

/** Rewrite standard IPA to the accent's regional variant. No-op for standard accents. */
export function applyRegion(ipa: string, voice: string): string {
  const rules = REGIONS[voice];
  if (!rules || !ipa) return ipa;
  let out = ipa;
  for (const [from, to] of rules) out = out.split(from).join(to);
  return out;
}
