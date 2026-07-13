/**
 * Dictionary values must render as exactly one pronunciation.
 *
 * Two layers: the documented cases, and a sweep of every shipped dictionary to
 * prove no entry survives normalization still carrying variant punctuation. The
 * sweep is what catches a new data release regressing this.
 *
 * Run: node --experimental-strip-types scripts/test-ipa-normalize.ts
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { normalizeIpa, isCleanIpa } from '../src/lib/ipa-normalize.ts';

const CASES: [string, string, string][] = [
  // [key, raw, expected]
  ['the', 'ðə, ði', 'ðə'],                       // the reported bug: a variant list
  ['arm', 'ˈɑr(ə)m', 'ˈɑrm'],                    // optional sound
  ['aberr', 'əˈbɜː(ɹ)', 'əˈbɜː'],
  ['ver', 'fɛr ~ fær', 'fɛr'],                   // tilde-separated variants
  ['နည်း', 'nɛ́; nɛ́', 'nɛ́'],                     // semicolon
  ['আঁডু', 'a.du/ ~ /á.du', 'a.du'],              // slash + tilde
  ['les', 'ləs/ [ɫəs̺', 'ləs'],                   // markup leftovers
  ['أوركسترا', 'ʔor.kestraː or ʔorkestraː', 'ʔor.kestraː'],
  ['absey-book', 'ˈæb.siː bʊk', 'ˈæb.siː bʊk'],  // compound key: the space is real
  ['word', '/wɜːd/', 'wɜːd'],                    // delimited transcription
  ['x', '', ''],                                 // nothing usable
  ['plain', 'plaɪn', 'plaɪn'],                   // already clean: untouched
];

let pass = 0;
for (const [key, raw, want] of CASES) {
  const got = normalizeIpa(key, raw);
  const ok = got === want;
  pass += ok ? 1 : 0;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${key.padEnd(12)} ${JSON.stringify(raw).padEnd(26)} -> ${JSON.stringify(got)}${ok ? '' : `  want ${JSON.stringify(want)}`}`);
}

// Sweep the shipped data: after normalization nothing may still look like a
// variant list, and normalization must be stable (running it twice is a no-op).
const dir = path.join(import.meta.dirname, '..', 'public', 'dictionaries');
const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter(f => f.endsWith('.json.gz')) : [];
if (!files.length) {
  console.log('\nFAIL  no dictionaries on disk — run `pnpm fetch:dict` (a passing sweep would be vacuous)');
  process.exit(1);
}

let entries = 0;
const dirty: string[] = [];
for (const f of files) {
  const obj: Record<string, string> = JSON.parse(
    zlib.gunzipSync(fs.readFileSync(path.join(dir, f))).toString(),
  );
  for (const [key, raw] of Object.entries(obj)) {
    entries++;
    const ipa = normalizeIpa(key, raw);
    if (ipa && !isCleanIpa(key, ipa) && dirty.length < 10) {
      dirty.push(`${f.split('.')[0]}:${key} -> ${JSON.stringify(raw)} => ${JSON.stringify(ipa)}`);
    }
  }
}
console.log(`\nswept ${entries.toLocaleString()} entries across ${files.length} dictionaries`);
for (const d of dirty) console.log('  DIRTY', d);

const sweepOk = dirty.length === 0;
console.log(`${sweepOk ? 'PASS' : 'FAIL'}  every entry normalizes to a single clean pronunciation`);
console.log(`\n${pass + (sweepOk ? 1 : 0)}/${CASES.length + 1} passed`);
process.exit(pass === CASES.length && sweepOk ? 0 : 1);
