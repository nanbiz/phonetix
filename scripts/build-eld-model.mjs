#!/usr/bin/env node
/**
 * Turn eld's ngram model into something Android can read without shipping a megabyte of
 * JavaScript source.
 *
 * eld identifies a language from character ngrams, and the extension uses it to decide which
 * dictionary a block of text belongs to. The Android app had no language detection at all, so
 * a German page came back covered in English pronunciations; the model is the same one, so
 * the two ports agree about what language a line is in.
 *
 * The model ships as source: an object literal of about a hundred thousand keys, each mapping
 * to per-language scores. This writes the same thing as a flat file the app memory-maps and
 * searches: keys sorted, so a lookup is a binary search over a LongArray rather than a hash of
 * a hundred thousand boxed strings.
 *
 * Keys are strings of characters from eld's own byte dictionary - it maps each UTF-8 byte to a
 * printable character, because JavaScript has no raw byte strings - and they are stored here
 * as indices into the distinct characters of that dictionary, which fit in a byte each.
 *
 *   node scripts/build-eld-model.mjs [XS|S|M|L]
 */
import { writeFileSync } from 'node:fs';

const size = (process.argv[2] || 'XS').toUpperCase();
const { ngramsData } = await import(`@yutengjing/eld/src/ngrams/ngrams${size}60.js`);
const { dictionary } = await import('@yutengjing/eld/src/dictionary.js');
const { avgScore } = await import('@yutengjing/eld/src/avgScore.js');

const languages = ngramsData.languages;
const codes = Object.keys(languages)
  .map(Number)
  .sort((a, b) => a - b)
  .map((i) => languages[i]);

// The characters the model's keys are made of, in a fixed order, so the app can turn a byte
// into the same index this does.
// Numbered from one, not from zero. A key is packed by shifting each character's number into
// a long, so a character numbered zero contributes nothing and " abcd" packs to the same
// number as "abcd" - and eld's keys differ in exactly that: whether the run of letters starts
// or ends a word. With them merged the scores came out several per cent high and near ties
// went to the wrong language.
const alphabet = [...new Set(dictionary)];
const indexOf = new Map(alphabet.map((c, i) => [c, i + 1]));
if (alphabet.length + 1 > 256) throw new Error(`alphabet too large: ${alphabet.length}`);

const entries = Object.entries(ngramsData.ngrams);
// Sorted by the packed key, so the app can binary-search it.
const packed = entries.map(([key, scores]) => {
  let value = 0n;
  for (const ch of key) {
    const at = indexOf.get(ch);
    if (at === undefined) throw new Error(`character outside the dictionary: ${JSON.stringify(ch)}`);
    value = (value << 8n) | BigInt(at);
  }
  if (key.length > 7) throw new Error(`key too long to pack: ${key}`);
  return [value, scores];
});
packed.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));

const pairs = packed.reduce((n, [, scores]) => n + Object.keys(scores).length, 0);

// magic, version, language count, codes, alphabet, ngram count, keys, offsets, pairs
const head = Buffer.alloc(8);
head.write('PXELD2', 0, 'ascii');
head.writeUInt16LE(codes.length, 6);
const codesBuf = Buffer.from(codes.join(','), 'ascii');
const alphabetBuf = Buffer.from(alphabet.join(''), 'utf8');

const counts = Buffer.alloc(4 + 4 + 2);
counts.writeUInt32LE(packed.length, 0);
counts.writeUInt32LE(pairs, 4);
counts.writeUInt16LE(alphabetBuf.length, 8);

const keys = Buffer.alloc(packed.length * 8);
const starts = Buffer.alloc((packed.length + 1) * 4);
const langs = Buffer.alloc(pairs);
const scores = Buffer.alloc(pairs * 2);

let at = 0;
packed.forEach(([key, byLang], i) => {
  keys.writeBigUInt64LE(key, i * 8);
  starts.writeUInt32LE(at, i * 4);
  for (const [lang, score] of Object.entries(byLang)) {
    langs.writeUInt8(Number(lang), at);
    scores.writeUInt16LE(Math.min(65535, Math.round(score)), at * 2);
    at++;
  }
});
starts.writeUInt32LE(at, packed.length * 4);

const codesLength = Buffer.alloc(2);
codesLength.writeUInt16LE(codesBuf.length, 0);

// What a language's ngrams score on average, which is what eld holds a result against before
// calling it reliable. Shipped rather than restated, so the two ports agree on that too.
const averages = Buffer.alloc(codes.length * 4);
codes.forEach((code, i) => averages.writeFloatLE(avgScore[code] ?? 0, i * 4));

// Which character of the alphabet each byte maps to, so the app need not carry a copy of
// eld's dictionary and hope it typed it out correctly.
const bytes = Buffer.alloc(256);
for (let b = 0; b < 256; b++) {
  const ch = dictionary[b] ?? ' ';
  bytes.writeUInt8(indexOf.get(ch) ?? indexOf.get(' ') ?? 1, b);
}

const out = Buffer.concat([
  head, codesLength, codesBuf, averages, bytes, counts, alphabetBuf,
  keys, starts, langs, scores,
]);
const where = new URL('../android/app/src/main/assets/eld.bin', import.meta.url);
writeFileSync(where, out);
console.log(
  `${size}: ${packed.length} ngrams, ${pairs} pairs, ${codes.length} languages, ` +
    `${alphabet.length} characters -> ${(out.length / 1024).toFixed(0)}KB`,
);
