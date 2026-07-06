// Node test for the homograph disambiguator. Run:
//   node --experimental-strip-types scripts/test-homograph.ts
import { gunzipSync } from 'node:zlib';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseClassifier, disambiguate } from '../src/lib/homograph.ts';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const raw = JSON.parse(gunzipSync(readFileSync(join(root, 'public/homographs/en.json.gz'))).toString());
const clf = parseClassifier(raw);

const WORD_RE = /[a-z']+/gi;

/** Tokenize, resolve the `occurrence`-th match of `target`, return chosen IPA. */
function resolve(sentence: string, target: string, occurrence = 0): string | null {
  const tokens = [...sentence.matchAll(WORD_RE)].map((m) => m[0].toLowerCase());
  const t = target.toLowerCase();
  let seen = -1;
  for (let i = 0; i < tokens.length; i++) {
    if (tokens[i] === t && ++seen === occurrence) {
      const entry = clf.get(t);
      if (!entry) return null;
      return disambiguate(entry, tokens, i);
    }
  }
  return null;
}

// [sentence, target, occurrence, expected-IPA-substring]
const CASES: [string, string, number, string][] = [
  ['I read the book yesterday.', 'read', 0, 'ɛd'],
  ['You can read another book tomorrow.', 'read', 0, 'iːd'],
  ['The lead singer sang.', 'lead', 0, 'iːd'],
  ['The pipe was made of lead.', 'lead', 0, 'ɛd'],
  ['The wind blew hard.', 'wind', 0, 'ɪnd'],
  ['Please wind the clock.', 'wind', 0, 'aɪnd'],
  ['She began to record the album.', 'record', 0, 'ɔː'],
  ['He broke the world record.', 'record', 0, 'ɹɛk'],
  ['They watched the live broadcast.', 'live', 0, 'aɪv'],
  ['I want to live here.', 'live', 0, 'ɪv'],
  ['They gave me a present.', 'present', 0, 'ɹɛz'],
  ['I will present the award.', 'present', 0, 'ɛnt'],
];

let pass = 0;
let fail = 0;
for (const [sentence, target, occ, expect] of CASES) {
  const got = resolve(sentence, target, occ);
  const ok = got != null && got.includes(expect);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${target.padEnd(8)} → ${got ?? '(none)'}  ${ok ? '' : `(want …${expect}…)`}  | ${sentence}`);
  ok ? pass++ : fail++;
}
console.log(`\n${pass}/${pass + fail} passed`);
if (fail > 0) process.exit(1);
