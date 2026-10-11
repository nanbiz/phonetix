// Which of a word's recordings a reader hears, by the accent they chose, from the labels real
// Wiktionary pages put on them ("tomato", "water", "update", "fazer", "perro").
//
//   node --experimental-strip-types scripts/proofread/recordings.ts
import { recordingIn } from '../../src/data/accents.ts';

const tomato = {
  files: ['en-uk-tomato.ogg', 'En-au-tomato.ogg', 'en-us-tomato.ogg'],
  labels: ['UK', 'AU', 'California'],
};
const water = {
  files: ['En-uk-water.ogg', 'LL-Q1860 (eng)-Back ache-water.wav',
    'LL-Q1860 (eng)-Nail123Real-water.wav', 'En-us-water.ogg'],
  labels: ['RP', 'UK', 'US,non-cot-caught', 'US,cot-caught'],
};
const update = {
  files: ['En-us-update.ogg', 'LL-Q1860 (eng)-Vealhurl-update (verb).wav'],
  labels: ['US', 'Southern England'],
};
const fazer = {
  files: ['pt-br-fazer.ogg', 'LL-Q5146 (por)-Santamarcanda-fazer.wav'],
  labels: ['São Paulo', 'Portugal (Porto)'],
};

// Language, accent, the recordings, and what is heard: a file, or '' for the voice.
const cases: [string, string, { files: string[]; labels: string[] }, string][] = [
  ['en', 'en-gb', tomato, 'en-uk-tomato.ogg'],
  ['en', 'en-us', tomato, 'en-us-tomato.ogg'],
  ['en', 'en-au', tomato, 'En-au-tomato.ogg'],
  // A Canadian reader hears the voice in their accent, not a speaker of another.
  ['en', 'en-ca', tomato, ''],
  ['en', 'en-gb', water, 'En-uk-water.ogg'],
  ['en', 'en-us', water, 'LL-Q1860 (eng)-Nail123Real-water.wav'],
  ['en', 'en-gb', update, 'LL-Q1860 (eng)-Vealhurl-update (verb).wav'],
  ['en', 'en-us', update, 'En-us-update.ogg'],
  ['pt', 'pt-pt', fazer, 'LL-Q5146 (por)-Santamarcanda-fazer.wav'],
  ['pt', 'pt-br', fazer, 'pt-br-fazer.ogg'],
  ['es', 'es-419', { files: ['LL-Q1321 (spa)-Millars-perro.wav'], labels: ['Spain'] }, ''],
  // A speaker labelled as from nowhere in particular is anyone's.
  ['en', 'en-gb', { files: ['LL-Q1860 (eng)-x-word.wav'], labels: [''] }, 'LL-Q1860 (eng)-x-word.wav'],
  // A language with one way of saying it plays what there is.
  ['it', '', { files: ['LL-Q652 (ita)-x-tomato.wav'], labels: [''] }, 'LL-Q652 (ita)-x-tomato.wav'],
];

let failed = 0;
for (const [lang, accent, { files, labels }, want] of cases) {
  const got = recordingIn(lang, accent, files, labels);
  if (got !== want) {
    failed++;
    console.log(`  ${lang} ${accent}: heard ${got || 'the voice'}, wanted ${want || 'the voice'}`);
  }
}
if (failed) {
  console.log(`\nFAIL - ${failed} of ${cases.length}`);
  process.exit(1);
}
console.log(`PASS - all ${cases.length}: a reader hears a speaker of the accent they chose`);
