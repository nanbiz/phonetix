// Give each symbol its Seeing Speech deep link.
//
// Seeing Speech (University of Glasgow) films every IPA sound under MRI, ultrasound
// and animation. The films are not free to redistribute, but the site takes a hash
// that opens one directly: /ipa-charts/?chart=N#location=CODEPOINT. The chart a
// sound is on and the code point it is keyed by are both read off the real chart
// pages here, so a link is written only when the site really has that sound — never
// guessed.
//
//   node scripts/link-seeingspeech.mjs [--write]

import fs from 'node:fs';

const SOURCE = 'data/ipa-symbols.json';
const UA = 'Mozilla/5.0 (phonetix-symbol-linker)';
const WRITE = process.argv.includes('--write');

// code point -> the first chart it appears on
const chartOf = new Map();
for (const chart of [1, 2, 3, 4]) {
  const html = await (await fetch(
    `https://www.seeingspeech.ac.uk/ipa-charts/?chart=${chart}`,
    { headers: { 'User-Agent': UA } },
  )).text();
  for (const m of html.matchAll(/id="video-\d+-\d+-\d+-(\d+)"/g)) {
    const cp = Number(m[1]);
    if (!chartOf.has(cp)) chartOf.set(cp, chart);
  }
}
console.log(`Seeing Speech knows ${chartOf.size} sounds across 4 charts`);

const table = JSON.parse(fs.readFileSync(SOURCE, 'utf8'));
let linked = 0;
const none = [];

for (const [sym, row] of Object.entries(table.symbols)) {
  if (row.seeing) continue;
  // The film is keyed by the base sound's code point; a diacritic does not have its own
  // film, so a modified symbol shares the base sound's.
  const base = [...sym].find(ch => chartOf.has(ch.codePointAt(0)));
  const cp = base?.codePointAt(0);
  const chart = cp != null ? chartOf.get(cp) : undefined;
  if (chart) {
    linked++;
    row.seeing = `?chart=${chart}#location=${cp}`;
  } else {
    none.push(`${sym} (${row.name})`);
  }
}

console.log(`linked ${linked} sounds to Seeing Speech`);
if (none.length) console.log(`no film for ${none.length}: ${none.slice(0, 16).join(', ')}${none.length > 16 ? ' ...' : ''}`);

if (WRITE) {
  fs.writeFileSync(SOURCE, JSON.stringify(table, null, 2) + '\n');
  console.log(`wrote ${SOURCE}`);
} else {
  console.log('(dry run; pass --write to update the table)');
}
