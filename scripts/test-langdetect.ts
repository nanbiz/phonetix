// Unit tests for the per-block language decision. Run:
//   node --experimental-strip-types scripts/test-langdetect.ts
import { pickLanguage } from '../src/lib/langdetect.ts';

let pass = 0, fail = 0;
function check(name: string, cond: boolean, detail = '') {
  if (cond) pass++;
  else { fail++; console.log(`FAIL  ${name}  ${detail}`); }
}
const cov = (m: Record<string, number>) => (l: string) => m[l] ?? 0;

// English title on a German page: coverage switches it to English.
check('en title on de page', pickLanguage('de', ['de', 'en', 'fr'], true, 'en',
  cov({ de: 0.57, en: 1.0, fr: 0.29 })) === 'en');

// German block stays German.
check('de block stays de', pickLanguage('de', ['de', 'en'], true, 'de',
  cov({ de: 0.9, en: 0.1 })) === 'de');

// French title, candidate pooled page-wide even if its own eld guess was noisy.
check('fr title via pool', pickLanguage('de', ['de', 'fr', 'ca'], true, 'fr',
  cov({ de: 0.38, fr: 1.0, ca: 0.3 })) === 'fr');

// Proper-noun title: no dictionary covers it, defer to a reliable eld guess.
check('weak coverage -> reliable eld', pickLanguage('de', ['en'], true, 'en',
  cov({ de: 0.1, en: 0.12 })) === 'en');

// Weak coverage but eld NOT reliable: keep the page language.
check('weak coverage + unreliable -> page', pickLanguage('de', ['en'], false, 'en',
  cov({ de: 0.1, en: 0.12 })) === 'de');

// Margin too small to switch (stability against noise).
check('small margin no switch', pickLanguage('de', ['en'], false, 'en',
  cov({ de: 0.6, en: 0.7 })) === 'de');

// No candidates at all: page language.
check('no candidates', pickLanguage('de', [], false, undefined,
  cov({ de: 0.8 })) === 'de');

// Clear switch beats the page language by margin and floor.
check('clear switch', pickLanguage('de', ['es'], true, 'es',
  cov({ de: 0.4, es: 0.95 })) === 'es');

console.log(`\n${pass}/${pass + fail} passed`);
if (fail) process.exit(1);
