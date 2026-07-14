// ─── Regional IPA transforms ─────────────────────────────────────────
//
// An accent whose difference from the standard is a *rule* is applied here, to
// every word. An accent that instead differs word by word is served from tagged
// dictionary data (see accents.ts); rewriting such an accent here as well would
// apply its shift twice.
//
// Rules earn their place when a source dictionary cannot: Wiktionary tags almost
// no Spanish word for accent, and only a few hundred German words for Switzerland
// or Austria, while the shifts below hold across the whole vocabulary. They also
// reach the words no dictionary knows at all, since they are applied to espeak's
// output too.
//
// Order matters: a rule may feed the next one, so the narrower rule comes first
// (German -ig would otherwise be swallowed by the ich-Laut merger).
//
// A rule about the end of a word anchors on the end of the string. \b cannot do
// it: JavaScript's word boundary is defined on ASCII, so it sees one on either
// side of every IPA symbol, and a rule for final -ig would fire in the middle of
// "richtig".

interface Rule {
  from: RegExp | string;
  to: string;
  /** Apply only to words spelled like this. [ɪç] is both the word "ich" and the
   *  ending -ig, and no rule can tell them apart from the IPA alone. */
  word?: RegExp;
}

const REGIONS: Record<string, Rule[]> = {
  // General American, for words the 27k-word American overlay does not cover. The
  // base dictionary is British-leaning (Wiktionary lists an RP pronunciation
  // first), so an uncovered word came out British. These are the systematic shifts
  // that separate the two accents, so they hold across the vocabulary: American is
  // rhotic, so the RP "comma" vowel is r-coloured; the GOAT vowel is [oʊ] not [əʊ];
  // and LOT is unrounded. They are an approximation, not the tagged data, so they
  // apply only where the overlay is silent (see background.ts).
  'en-us': [
    { from: 'əʊ', to: 'oʊ' },
    { from: 'ɐ', to: 'ɚ' },
    { from: 'ɒ', to: 'ɑ' },
  ],
  // Canadian is rhotic and unrounds LOT like American; the overlay carries the
  // features that differ from it (Canadian raising).
  'en-ca': [
    { from: 'əʊ', to: 'oʊ' },
    { from: 'ɐ', to: 'ɚ' },
    { from: 'ɒ', to: 'ɑ' },
  ],

  // Latin-American Spanish: seseo (θ→s) and yeísmo (ʎ→ʝ).
  'es-419': [{ from: 'θ', to: 's' }, { from: 'ʎ', to: 'ʝ' }],

  // Rioplatense (Buenos Aires, Montevideo): seseo and yeísmo as above, then
  // žeísmo/šeísmo — what the rest of the Spanish world says as [ʝ] is a postalveolar
  // fricative here, so "yo" and "calle" carry [ʃ].
  'es-ar': [{ from: 'θ', to: 's' }, { from: 'ʎ', to: 'ʃ' }, { from: 'ʝ', to: 'ʃ' }],

  // Swiss Standard German. Swiss dialects have no ich-Laut, so speakers use the
  // ach-Laut throughout; /r/ is an alveolar trill rather than the uvular
  // fricative, including where northern German vocalizes it; and a word does not
  // begin on a glottal stop. Final -ig stays a plosive instead of becoming [ɪç].
  'de-ch': [
    { from: /ɪç$/u, to: 'ɪɡ', word: /ig$/i },  // richtig, before the ich-Laut merger
    { from: 'ç', to: 'x' },                     // ich: [ɪç] → [ɪx]
    { from: 'ʁ', to: 'r' },
    { from: 'ɐ̯', to: 'r' },                     // vocalized r becomes a consonant again
    { from: 'ɐ', to: 'ər' },
    { from: 'ʔ', to: '' },
  ],

  // Austrian Standard German keeps the uvular r, but likewise has no initial
  // glottal stop, and ends -ig on a plosive.
  'de-at': [
    { from: /ɪç$/u, to: 'ɪk', word: /ig$/i },
    { from: 'ʔ', to: '' },
  ],
};

/** Rewrite standard IPA to the accent's regional variant. No-op for standard accents. */
export function applyRegion(ipa: string, accent: string, word = ''): string {
  const rules = REGIONS[accent];
  if (!rules || !ipa) return ipa;

  let out = ipa;
  for (const rule of rules) {
    if (rule.word && !rule.word.test(word)) continue;
    out = typeof rule.from === 'string'
      ? out.split(rule.from).join(rule.to)
      : out.replace(rule.from, rule.to);
  }
  return out;
}
