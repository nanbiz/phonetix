/**
 * Every symbol the reader sees must be described, and described correctly.
 *
 * A transcription is mostly base sounds carrying diacritics: "kʰ" is an aspirated
 * k, "n̩" a syllabic n. The tooltip used to describe such a symbol by looking up
 * its first character, which reported the aspirated sound as the plain one — a
 * confident, wrong answer. This sweeps the shipped dictionaries and their accent
 * overlays, and fails when a symbol the reader can actually meet has no
 * description behind it.
 *
 * Run: node --experimental-strip-types scripts/test-symbols.ts
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

import { IPA_SYMBOLS, TERM_LINKS, describeSymbol, tokenizeIPA } from '../src/data/ipa-symbols.ts';
import { normalizeIpa } from '../src/data/ipa-normalize.ts';

/** Composed symbols: the description must carry the diacritic, not drop it. */
const COMPOSED: [string, string][] = [
  ['kʰ', 'aspirated'],
  ['n̩', 'syllabic'],
  ['ʊ̯', 'non-syllabic'],
  ['sʲ', 'palatalized'],
  ['tˤ', 'pharyngealized'],
  ['d̥', 'voiceless'],
  ['ã', 'nasalized'],       // arrives precomposed as one code point
];

let pass = 0;
let total = 0;

for (const [token, mark] of COMPOSED) {
  total++;
  const info = describeSymbol(token);
  const ok = !!info && info.name.includes(mark);
  if (ok) pass++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${token} -> ${info ? info.name : 'undescribed'}${ok ? '' : `   (should say "${mark}")`}`);
}

// The base sound must not be reported as the whole symbol.
total++;
const plain = describeSymbol('k')?.name;
const aspirated = describeSymbol('kʰ')?.name;
if (plain && aspirated && plain !== aspirated) {
  pass++;
  console.log(`PASS  kʰ is not described as k (${aspirated} vs ${plain})`);
} else {
  console.log(`FAIL  kʰ is described exactly as k (${aspirated})`);
}

// Every linked article must be a title, not a guess.
total++;
const badLinks = Object.entries(IPA_SYMBOLS)
  .filter(([, info]) => info.wiki && /[ ?#]/.test(info.wiki))
  .map(([sym]) => sym);
if (!badLinks.length) {
  pass++;
  console.log(`PASS  ${Object.values(IPA_SYMBOLS).filter(i => i.wiki).length} symbols link to an article`);
} else {
  console.log(`FAIL  malformed article links: ${badLinks.join(', ')}`);
}

// A description is a stack of independent facts, and each must be followable on
// its own: linking the phrase as a whole sends the reader to one of them.
total++;
const eachTermLinked = ['r-colored', 'open-mid', 'central', 'vowel']
  .every(term => TERM_LINKS[term]);
if (eachTermLinked) {
  pass++;
  console.log(`PASS  every term of "r-colored open-mid central vowel" links on its own`);
} else {
  console.log(`FAIL  some terms of a description have no article of their own`);
}

// A term's article must be a title, not a search or a guess.
total++;
const badTerms = Object.entries(TERM_LINKS)
  .filter(([, title]) => /[ ?#]/.test(title))
  .map(([term]) => term);
if (!badTerms.length) {
  pass++;
  console.log(`PASS  ${Object.keys(TERM_LINKS).length} phonetic terms link to an article`);
} else {
  console.log(`FAIL  malformed term links: ${badTerms.join(', ')}`);
}

// Sweep the real data: what share of the symbols on screen have no description?
const dirs = ['assets/dictionaries', 'assets/dictionaries/accents'];
const files: string[] = [];
for (const dir of dirs) {
  if (!fs.existsSync(dir)) continue;
  for (const f of fs.readdirSync(dir)) {
    if (f.endsWith('.json.gz')) files.push(path.join(dir, f));
  }
}
if (!files.length) {
  console.log('\nFAIL  no dictionaries on disk — run `pnpm fetch:dict` (a passing sweep would be vacuous)');
  process.exit(1);
}

let tokens = 0;
let undescribed = 0;
const worst = new Map<string, number>();
let seen = 0;

for (const file of files) {
  const obj: Record<string, string> = JSON.parse(zlib.gunzipSync(fs.readFileSync(file)).toString());
  for (const [word, raw] of Object.entries(obj)) {
    if (seen++ > 400_000) break;
    for (const token of tokenizeIPA(normalizeIpa(word, raw))) {
      if (!token.trim()) continue;
      tokens++;
      if (!describeSymbol(token)) {
        undescribed++;
        worst.set(token, (worst.get(token) ?? 0) + 1);
      }
    }
  }
}

const share = (100 * undescribed) / tokens;
console.log(`\nswept ${tokens.toLocaleString()} symbols of real transcriptions`);
if (worst.size) {
  const top = [...worst].sort((a, b) => b[1] - a[1]).slice(0, 5)
    .map(([t, c]) => `${JSON.stringify(t)}×${c}`).join(' ');
  console.log(`  undescribed: ${share.toFixed(3)}% — ${top}`);
}

total++;
if (share < 0.5) {
  pass++;
  console.log(`PASS  ${(100 - share).toFixed(2)}% of the symbols a reader meets are described`);
} else {
  console.log(`FAIL  ${share.toFixed(2)}% of symbols have no description`);
}

console.log(`\n${pass}/${total} passed`);
process.exit(pass === total ? 0 : 1);
