// Unit tests for the segmentation + espeak-reliability rules. Run:
//   node --experimental-strip-types scripts/test-resolution.ts
import { words, segment, scriptOf, dominantScript, isLetterSpelling, espeakAllowed } from '../src/lib/segment.ts';

let pass = 0, fail = 0;
function check(name: string, cond: boolean, detail = '') {
  if (cond) { pass++; }
  else { fail++; console.log(`FAIL  ${name}  ${detail}`); }
}

// ── scriptOf: Unicode script of a word (off-the-shelf \p{Script}) ──
check('scriptOf latin', scriptOf('Renault') === 'Latin');
check('scriptOf cyrillic', scriptOf('Кошка') === 'Cyrillic');
check('scriptOf greek', scriptOf('Γάτα') === 'Greek');
check('scriptOf arabic', scriptOf('العربية') === 'Arabic');
check('scriptOf han', scriptOf('貓') === 'Han');
check('scriptOf hangul', scriptOf('한국') === 'Hangul');

// ── dominantScript: derive a language's script from its dictionary keys ──
check('dominant cyrillic', dominantScript(['кошка', 'собака', 'мир']) === 'Cyrillic');
check('dominant latin', dominantScript(['cat', 'dog', 'world']) === 'Latin');

// ── isLetterSpelling: espeak spelled a word out (spaces) → garbage ──
check('spelling detected', isLetterSpelling('aɹəbɪk alif aɹəbɪk lam') === true);
check('real word not spelling', isLetterSpelling('mʲɪˈnʲu') === false);

// ── espeakAllowed: script-match + no logographic Han ──
check('latin word, latin lang', espeakAllowed('Renault', 'Latin') === true);
check('cyrillic word, cyrillic lang', espeakAllowed('Кошка', 'Cyrillic') === true);
check('cyrillic word, han lang (ja) skipped', espeakAllowed('Аԥсшәа', 'Han') === false);
check('latin word, thai lang skipped', espeakAllowed('Acèh', 'Thai') === false);
check('han word never espeak', espeakAllowed('閩', 'Han') === false);
check('unknown lang script allows latin', espeakAllowed('word', '') === true);

// ── words(): multi-script, drops numbers/punctuation, lowercases ──
const got = words('The Café в Москве, 123 貓!', 'en');
check('words multi-script', JSON.stringify(got) === JSON.stringify(['the', 'café', 'в', 'москве', '貓']),
  JSON.stringify(got));

// ── segment(): covers the whole string, marks words vs non-words ──
const segs = segment('a, b', 'en');
check('segment round-trips text', segs.map(s => s.text).join('') === 'a, b', JSON.stringify(segs));
check('segment word flags', segs.filter(s => s.isWord).map(s => s.text).join(',') === 'a,b');

// ── numbers are not words ──
check('digits not a word', words('42 cats', 'en').join(',') === 'cats');
// ── overlong tokens excluded ──
check('overlong excluded', words('a'.repeat(60) + ' ok', 'en', 50).join(',') === 'ok');

console.log(`\n${pass}/${pass + fail} passed`);
if (fail) process.exit(1);
