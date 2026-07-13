/**
 * The rule-derived accents, checked against their defining features.
 *
 * These accents have no per-word dictionary anywhere (Wiktionary tags a few
 * hundred German words for Switzerland and almost no Spanish word at all), so
 * rules are what makes them work — and a rule that misfires corrupts every word
 * rather than one. The first version of the German -ig rule used \b, which
 * JavaScript defines on ASCII, so it saw a word boundary beside every IPA symbol
 * and rewrote "richtig" in the middle.
 *
 * Run: node --experimental-strip-types scripts/test-regions.ts
 */
import { applyRegion } from '../src/lib/regions.ts';

const CASES: [string, string, string, string][] = [
  // [accent, word (for the message), standard IPA, expected]

  // Swiss Standard German: no ich-Laut, trilled r (including where the north
  // vocalizes it), no glottal stop, final -ig on a plosive.
  ['de-ch', 'ich', 'ɪç', 'ɪx'],
  ['de-ch', 'Milch', 'mɪlç', 'mɪlx'],
  ['de-ch', 'Kirche', 'ˈkɪʁçə', 'ˈkɪrxə'],
  ['de-ch', 'Arbeit', 'ˈaʁbaɪ̯t', 'ˈarbaɪ̯t'],
  ['de-ch', 'Vater', 'ˈfaːtɐ', 'ˈfaːtər'],
  ['de-ch', 'richtig', 'ˈʁɪçtɪç', 'ˈrɪxtɪɡ'],   // -ig, and only at the end
  ['de-ch', 'Ende', 'ˈʔɛndə', 'ˈɛndə'],

  // Austrian keeps the uvular r, drops the glottal stop, ends -ig on [k].
  ['de-at', 'ich', 'ɪç', 'ɪç'],
  ['de-at', 'richtig', 'ˈʁɪçtɪç', 'ˈʁɪçtɪk'],
  ['de-at', 'Ende', 'ˈʔɛndə', 'ˈɛndə'],

  // Latin-American Spanish: seseo and yeísmo.
  ['es-419', 'cielo', 'θjelo', 'sjelo'],
  ['es-419', 'calle', 'kaʎe', 'kaʝe'],

  // Rioplatense: as above, then [ʝ] and [ʎ] become [ʃ].
  ['es-ar', 'calle', 'kaʎe', 'kaʃe'],
  ['es-ar', 'yo', 'ʝo', 'ʃo'],
  ['es-ar', 'cielo', 'θjelo', 'sjelo'],

  // The standard accents change nothing.
  ['de', 'ich', 'ɪç', 'ɪç'],
  ['es', 'cielo', 'θjelo', 'θjelo'],
  ['en-us', 'water', 'ˈwɔtɚ', 'ˈwɔtɚ'],   // English accents are data, not rules
];

let pass = 0;
for (const [accent, word, standard, want] of CASES) {
  const got = applyRegion(standard, accent, word);
  const ok = got === want;
  pass += ok ? 1 : 0;
  console.log(
    `${ok ? 'PASS' : 'FAIL'}  ${accent.padEnd(7)} ${word.padEnd(9)} ${standard} -> ${got}${ok ? '' : `   want ${want}`}`,
  );
}

// A rule set must be stable: applying it to its own output changes nothing, or a
// word already carrying the accent (from the tagged overlay) would be shifted twice.
for (const [accent, word, standard] of CASES) {
  const once = applyRegion(standard, accent, word);
  const twice = applyRegion(once, accent, word);
  if (once !== twice) {
    console.log(`FAIL  ${accent} ${word}: applying twice changes it again (${once} -> ${twice})`);
    pass = -1;
  }
}

console.log(`\n${pass}/${CASES.length} passed`);
process.exit(pass === CASES.length ? 0 : 1);
