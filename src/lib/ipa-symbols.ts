export interface IPASymbolInfo {
  /** Short phonetic name */
  name: string;
  type: 'vowel' | 'consonant' | 'suprasegmental' | 'diacritic';
  /** Example word */
  example: string;
  /** Wikimedia Commons audio filename (null = no audio available) */
  audio: string | null;
}

/**
 * Build a Wikimedia Commons direct URL from a filename.
 * Pattern: https://commons.wikimedia.org/wiki/Special:FilePath/{filename}
 * This 302-redirects to the actual file. Audio elements follow redirects.
 */
export function wikimediaAudioURL(filename: string): string {
  return `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(filename)}`;
}

export const IPA_SYMBOLS: Record<string, IPASymbolInfo> = {
  // ─── Consonants: Plosives ──────────────────────────────────────────
  'p': { name: 'voiceless bilabial plosive', type: 'consonant', example: '"p" in pin', audio: 'Voiceless_bilabial_plosive.ogg' },
  'b': { name: 'voiced bilabial plosive', type: 'consonant', example: '"b" in bin', audio: 'Voiced_bilabial_plosive.ogg' },
  't': { name: 'voiceless alveolar plosive', type: 'consonant', example: '"t" in tin', audio: 'Voiceless_alveolar_plosive.ogg' },
  'd': { name: 'voiced alveolar plosive', type: 'consonant', example: '"d" in din', audio: 'Voiced_alveolar_plosive.ogg' },
  'k': { name: 'voiceless velar plosive', type: 'consonant', example: '"k" in kin', audio: 'Voiceless_velar_plosive.ogg' },
  'ɡ': { name: 'voiced velar plosive', type: 'consonant', example: '"g" in give', audio: 'Voiced_velar_plosive.ogg' },
  'g': { name: 'voiced velar plosive', type: 'consonant', example: '"g" in give', audio: 'Voiced_velar_plosive.ogg' },
  'ʔ': { name: 'glottal stop', type: 'consonant', example: 'the catch in "uh-oh"', audio: 'Glottal_stop.ogg' },
  'c': { name: 'voiceless palatal plosive', type: 'consonant', example: '"k" in keen (some dialects)', audio: 'Voiceless_palatal_plosive.ogg' },
  'ɟ': { name: 'voiced palatal plosive', type: 'consonant', example: '"g" in argue (some dialects)', audio: 'Voiced_palatal_plosive.ogg' },
  'q': { name: 'voiceless uvular plosive', type: 'consonant', example: 'Arabic "q" in Quran', audio: 'Voiceless_uvular_plosive.ogg' },

  // ─── Consonants: Fricatives ────────────────────────────────────────
  'f': { name: 'voiceless labiodental fricative', type: 'consonant', example: '"f" in fan', audio: 'Voiceless_labiodental_fricative.ogg' },
  'v': { name: 'voiced labiodental fricative', type: 'consonant', example: '"v" in van', audio: 'Voiced_labiodental_fricative.ogg' },
  'θ': { name: 'voiceless dental fricative', type: 'consonant', example: '"th" in thin', audio: 'Voiceless_dental_fricative.ogg' },
  'ð': { name: 'voiced dental fricative', type: 'consonant', example: '"th" in this', audio: 'Voiced_dental_fricative.ogg' },
  's': { name: 'voiceless alveolar fricative', type: 'consonant', example: '"s" in sin', audio: 'Voiceless_alveolar_fricative.ogg' },
  'z': { name: 'voiced alveolar fricative', type: 'consonant', example: '"z" in zoo', audio: 'Voiced_alveolar_fricative.ogg' },
  'ʃ': { name: 'voiceless postalveolar fricative', type: 'consonant', example: '"sh" in shin', audio: 'Voiceless_palato-alveolar_sibilant.ogg' },
  'ʒ': { name: 'voiced postalveolar fricative', type: 'consonant', example: '"s" in measure', audio: 'Voiced_palato-alveolar_sibilant.ogg' },
  'ç': { name: 'voiceless palatal fricative', type: 'consonant', example: '"ch" in German ich', audio: 'Voiceless_palatal_fricative.ogg' },
  'x': { name: 'voiceless velar fricative', type: 'consonant', example: '"ch" in German Bach', audio: 'Voiceless_velar_fricative.ogg' },
  'ɣ': { name: 'voiced velar fricative', type: 'consonant', example: '"g" in Spanish fuego', audio: 'Voiced_velar_fricative.ogg' },
  'χ': { name: 'voiceless uvular fricative', type: 'consonant', example: '"ch" in Swiss-German', audio: 'Voiceless_uvular_fricative.ogg' },
  'ʁ': { name: 'voiced uvular fricative', type: 'consonant', example: '"r" in French rouge', audio: 'Voiced_uvular_fricative.ogg' },
  'h': { name: 'voiceless glottal fricative', type: 'consonant', example: '"h" in hat', audio: 'Voiceless_glottal_fricative.ogg' },
  'ɦ': { name: 'voiced glottal fricative', type: 'consonant', example: '"h" in ahead', audio: 'Voiced_glottal_fricative.ogg' },
  'ɸ': { name: 'voiceless bilabial fricative', type: 'consonant', example: 'blowing out a candle', audio: 'Voiceless_bilabial_fricative.ogg' },
  'β': { name: 'voiced bilabial fricative', type: 'consonant', example: '"b" in Spanish cabo', audio: 'Voiced_bilabial_fricative.ogg' },

  // ─── Consonants: Nasals ────────────────────────────────────────────
  'm': { name: 'bilabial nasal', type: 'consonant', example: '"m" in man', audio: 'Bilabial_nasal.ogg' },
  'n': { name: 'alveolar nasal', type: 'consonant', example: '"n" in no', audio: 'Alveolar_nasal.ogg' },
  'ŋ': { name: 'velar nasal', type: 'consonant', example: '"ng" in sing', audio: 'Velar_nasal.ogg' },
  'ɲ': { name: 'palatal nasal', type: 'consonant', example: '"ñ" in Spanish año', audio: 'Palatal_nasal.ogg' },
  'ɱ': { name: 'labiodental nasal', type: 'consonant', example: '"m" in symphony', audio: 'Labiodental_nasal.ogg' },

  // ─── Consonants: Approximants ──────────────────────────────────────
  'ɹ': { name: 'alveolar approximant', type: 'consonant', example: '"r" in English red', audio: 'Alveolar_approximant.ogg' },
  'j': { name: 'palatal approximant', type: 'consonant', example: '"y" in yes', audio: 'Palatal_approximant.ogg' },
  'w': { name: 'labio-velar approximant', type: 'consonant', example: '"w" in win', audio: 'Voiced_labio-velar_approximant.ogg' },
  'ʍ': { name: 'voiceless labio-velar fricative', type: 'consonant', example: '"wh" in which (some dialects)', audio: 'Voiceless_labio-velar_fricative.ogg' },
  'ɥ': { name: 'labial-palatal approximant', type: 'consonant', example: '"u" in French huit', audio: 'LL-Q150_(fra)-WikiLucas00-labial-palatal_approximant.wav' },
  'ɰ': { name: 'velar approximant', type: 'consonant', example: 'Korean "w" sound', audio: 'Voiced_velar_approximant.ogg' },

  // ─── Consonants: Laterals ──────────────────────────────────────────
  'l': { name: 'alveolar lateral approximant', type: 'consonant', example: '"l" in let', audio: 'Alveolar_lateral_approximant.ogg' },
  'ɫ': { name: 'velarized lateral (dark L)', type: 'consonant', example: '"l" in full', audio: 'Velarized_alveolar_lateral_approximant.ogg' },
  'ɬ': { name: 'voiceless lateral fricative', type: 'consonant', example: '"ll" in Welsh llan', audio: 'Voiceless_alveolar_lateral_fricative.ogg' },
  'ɮ': { name: 'voiced lateral fricative', type: 'consonant', example: '"dl" in Zulu', audio: 'Voiced_alveolar_lateral_fricative.ogg' },
  'ʎ': { name: 'palatal lateral approximant', type: 'consonant', example: '"gli" in Italian famiglia', audio: 'Palatal_lateral_approximant.ogg' },

  // ─── Consonants: Trills & Taps ─────────────────────────────────────
  'r': { name: 'alveolar trill', type: 'consonant', example: '"rr" in Spanish perro', audio: 'Alveolar_trill.ogg' },
  'ʀ': { name: 'uvular trill', type: 'consonant', example: '"r" in some French dialects', audio: 'Uvular_trill.ogg' },
  'ɾ': { name: 'alveolar tap', type: 'consonant', example: '"r" in Spanish pero, "tt" in butter', audio: 'Alveolar_flap.ogg' },
  'ɽ': { name: 'retroflex flap', type: 'consonant', example: '"ḍ" in Hindi', audio: 'Retroflex_flap.ogg' },

  // ─── Consonants: Affricates (common) ───────────────────────────────
  't͡ʃ': { name: 'voiceless postalveolar affricate', type: 'consonant', example: '"ch" in church', audio: 'Voiceless_palato-alveolar_affricate.ogg' },
  'd͡ʒ': { name: 'voiced postalveolar affricate', type: 'consonant', example: '"j" in judge', audio: 'Voiced_palato-alveolar_affricate.ogg' },
  't͡s': { name: 'voiceless alveolar affricate', type: 'consonant', example: '"z" in German Zeit', audio: 'Voiceless_alveolar_sibilant_affricate.ogg' },
  'd͡z': { name: 'voiced alveolar affricate', type: 'consonant', example: '"dz" in adze', audio: 'Voiced_alveolar_sibilant_affricate.ogg' },
  'p͡f': { name: 'voiceless labiodental affricate', type: 'consonant', example: '"pf" in German Pferd', audio: null },

  // ─── Vowels: Close ─────────────────────────────────────────────────
  'i': { name: 'close front unrounded vowel', type: 'vowel', example: '"ee" in see', audio: 'Close_front_unrounded_vowel.ogg' },
  'y': { name: 'close front rounded vowel', type: 'vowel', example: '"u" in French tu', audio: 'Close_front_rounded_vowel.ogg' },
  'ɨ': { name: 'close central unrounded vowel', type: 'vowel', example: '"y" in Polish ryba', audio: 'Close_central_unrounded_vowel.ogg' },
  'ʉ': { name: 'close central rounded vowel', type: 'vowel', example: '"oo" in Australian goose', audio: 'Close_central_rounded_vowel.ogg' },
  'ɯ': { name: 'close back unrounded vowel', type: 'vowel', example: '"u" in Turkish kul', audio: 'Close_back_unrounded_vowel.ogg' },
  'u': { name: 'close back rounded vowel', type: 'vowel', example: '"oo" in blue', audio: 'Close_back_rounded_vowel.ogg' },

  // ─── Vowels: Near-Close ────────────────────────────────────────────
  'ɪ': { name: 'near-close front unrounded vowel', type: 'vowel', example: '"i" in sit', audio: 'Near-close_near-front_unrounded_vowel.ogg' },
  'ʏ': { name: 'near-close front rounded vowel', type: 'vowel', example: '"ü" in German hübsch', audio: 'Near-close_near-front_rounded_vowel.ogg' },
  'ʊ': { name: 'near-close back rounded vowel', type: 'vowel', example: '"oo" in foot', audio: 'Near-close_near-back_rounded_vowel.ogg' },

  // ─── Vowels: Close-Mid ─────────────────────────────────────────────
  'e': { name: 'close-mid front unrounded vowel', type: 'vowel', example: '"ay" in say (pure)', audio: 'Close-mid_front_unrounded_vowel.ogg' },
  'ø': { name: 'close-mid front rounded vowel', type: 'vowel', example: '"eu" in French peu', audio: 'Close-mid_front_rounded_vowel.ogg' },
  'o': { name: 'close-mid back rounded vowel', type: 'vowel', example: '"o" in go (pure)', audio: 'Close-mid_back_rounded_vowel.ogg' },

  // ─── Vowels: Mid ───────────────────────────────────────────────────
  'ə': { name: 'schwa (mid central vowel)', type: 'vowel', example: '"a" in about', audio: 'Mid-central_vowel.ogg' },

  // ─── Vowels: Open-Mid ──────────────────────────────────────────────
  'ɛ': { name: 'open-mid front unrounded vowel', type: 'vowel', example: '"e" in bed', audio: 'Open-mid_front_unrounded_vowel.ogg' },
  'œ': { name: 'open-mid front rounded vowel', type: 'vowel', example: '"eu" in French coeur', audio: 'Open-mid_front_rounded_vowel.ogg' },
  'ɜ': { name: 'open-mid central unrounded vowel', type: 'vowel', example: '"ir" in British bird', audio: 'Open-mid_central_unrounded_vowel.ogg' },
  'ɞ': { name: 'open-mid central rounded vowel', type: 'vowel', example: 'rounded schwa variant', audio: 'Open-mid_central_rounded_vowel.ogg' },
  'ʌ': { name: 'open-mid back unrounded vowel', type: 'vowel', example: '"u" in strut', audio: 'PR-open-mid_back_unrounded_vowel.ogg' },
  'ɔ': { name: 'open-mid back rounded vowel', type: 'vowel', example: '"aw" in thought', audio: 'PR-open-mid_back_rounded_vowel.ogg' },

  // ─── Vowels: Near-Open ─────────────────────────────────────────────
  'æ': { name: 'near-open front unrounded vowel', type: 'vowel', example: '"a" in cat', audio: 'Near-open_front_unrounded_vowel.ogg' },
  'ɐ': { name: 'near-open central vowel', type: 'vowel', example: '"a" in German Ratte', audio: 'Near-open_central_unrounded_vowel.ogg' },

  // ─── Vowels: Open ──────────────────────────────────────────────────
  'a': { name: 'open front unrounded vowel', type: 'vowel', example: '"a" in French patte', audio: 'Open_front_unrounded_vowel.ogg' },
  'ɑ': { name: 'open back unrounded vowel', type: 'vowel', example: '"a" in father', audio: 'Open_back_unrounded_vowel.ogg' },
  'ɒ': { name: 'open back rounded vowel', type: 'vowel', example: '"o" in British lot', audio: 'Open_back_rounded_vowel.ogg' },

  // ─── Vowels: R-colored ─────────────────────────────────────────────
  'ɚ': { name: 'r-colored schwa', type: 'vowel', example: '"er" in American butter', audio: null },
  'ɝ': { name: 'r-colored open-mid central vowel', type: 'vowel', example: '"ir" in American bird', audio: null },

  // ─── Suprasegmentals ───────────────────────────────────────────────
  'ˈ': { name: 'primary stress', type: 'suprasegmental', example: 'next syllable is stressed', audio: null },
  'ˌ': { name: 'secondary stress', type: 'suprasegmental', example: 'next syllable has weaker stress', audio: null },
  "'": { name: 'primary stress', type: 'suprasegmental', example: 'next syllable is stressed', audio: null },
  ',': { name: 'secondary stress', type: 'suprasegmental', example: 'next syllable has weaker stress', audio: null },
  'ː': { name: 'long', type: 'suprasegmental', example: 'preceding sound is held longer', audio: null },
  'ˑ': { name: 'half-long', type: 'suprasegmental', example: 'preceding sound is slightly held', audio: null },
  '.': { name: 'syllable break', type: 'suprasegmental', example: 'marks a syllable boundary', audio: null },
  '|': { name: 'minor break', type: 'suprasegmental', example: 'minor group break', audio: null },
  '‖': { name: 'major break', type: 'suprasegmental', example: 'major group break', audio: null },

  // ─── Diacritics (standalone modifiers) ─────────────────────────────
  'ʰ': { name: 'aspirated', type: 'diacritic', example: 'a burst of air after the sound', audio: null },
  'ʷ': { name: 'labialized', type: 'diacritic', example: 'produced with lip rounding', audio: null },
  'ʲ': { name: 'palatalized', type: 'diacritic', example: 'tongue raised toward palate', audio: null },
  'ˠ': { name: 'velarized', type: 'diacritic', example: 'tongue raised toward velum', audio: null },
  'ˤ': { name: 'pharyngealized', type: 'diacritic', example: 'pharyngeal constriction', audio: null },
  'ⁿ': { name: 'nasal release', type: 'diacritic', example: 'released through the nose', audio: null },
  'ˡ': { name: 'lateral release', type: 'diacritic', example: 'released at tongue sides', audio: null },

  // ─── Tie bars ──────────────────────────────────────────────────────
  '͡': { name: 'tie bar', type: 'diacritic', example: 'links two sounds as one', audio: null },
  '͜': { name: 'tie bar (below)', type: 'diacritic', example: 'links two sounds as one', audio: null },
};

// ─── Combining diacritic ranges ──────────────────────────────────────

const COMBINING = /[\u0300-\u036F\u0361\u035C]/;
const MODIFIER = /[\u02B0-\u02FF]/;

/** Suprasegmentals that live in the MODIFIER range but should be standalone tokens */
const STANDALONE_MODIFIERS = new Set([
  '\u02C8', // ˈ primary stress
  '\u02CC', // ˌ secondary stress
  '\u02D0', // ː long
  '\u02D1', // ˑ half-long
]);

/**
 * Tokenize an IPA string into individual symbols, grouping base characters
 * with their combining diacritics and handling tie bars for affricates.
 *
 * Examples:
 *   "kæt"   → ["k", "æ", "t"]
 *   "t͡ʃ"    → ["t͡ʃ"]
 *   "ˈhɛloʊ" → ["ˈ", "h", "ɛ", "l", "o", "ʊ"]
 */
export function tokenizeIPA(ipa: string): string[] {
  const tokens: string[] = [];
  let i = 0;

  while (i < ipa.length) {
    let token = ipa[i];
    i++;

    // Absorb combining diacritics, modifiers, and tie bars
    while (i < ipa.length) {
      const ch = ipa[i];
      if (COMBINING.test(ch) || (MODIFIER.test(ch) && !STANDALONE_MODIFIERS.has(ch))) {
        token += ch;
        i++;
        // Tie bar: absorb the next base character too
        if ((ch === '\u0361' || ch === '\u035C') && i < ipa.length) {
          token += ipa[i];
          i++;
        }
      } else {
        break;
      }
    }

    tokens.push(token);
  }

  return tokens;
}
