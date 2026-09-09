// Find the picture of the mouth making each sound.
//
// A sound is easier to understand seen than read: Wikipedia's article on a
// consonant carries a sagittal section — the head cut open, tongue and lips in the
// position that makes the sound — and a vowel's article places it on the vowel
// chart. The articles hold both those and a pile of interface icons, so the
// diagram is picked out rather than assumed to be the first image.
//
//   node scripts/link-diagrams.mjs [--write]

import fs from 'node:fs';

const SOURCE = 'data/ipa-symbols.json';
const API = 'https://en.wikipedia.org/w/api.php';
const COMMONS = 'https://commons.wikimedia.org/w/api.php';
const UA = 'phonetix-symbol-linker/1.0 (https://github.com/nanbiz/phonetix)';
const WRITE = process.argv.includes('--write');

/** Interface furniture: on every article, about none of them. */
const CHROME = /^(loudspeaker|disc plain black|blank vowel trapezoid|question book|ambox|audio-input|commons-logo|wiktionary|edit-clear|folder|symbol|padlock|wikidata|speakerlink|braille|ipa unicode)/i;

/**
 * A file that shows the mouth making the sound, and only that.
 *
 * It must be an SVG: the line drawings are vector and recolour cleanly, while the
 * bitmaps are MRI photographs that read as a grey smear at thumbnail size. And it
 * must be an articulation section, not a vowel chart — the trapezoid with a dot is
 * the same picture for every vowel and shows no mouth at all.
 */
function isMouthDiagram(file) {
  const lower = file.toLowerCase();
  if (!lower.endsWith('.svg')) return false;
  if (CHROME.test(file)) return false;
  if (/vowel chart|trapezoid|quadrilateral|vowel space|blank vowel/.test(lower)) return false;
  return true;
}

async function api(url) {
  for (let attempt = 0; attempt < 6; attempt++) {
    const res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json' } });
    const text = await res.text();
    try {
      return JSON.parse(text);
    } catch {
      await new Promise(r => setTimeout(r, 2000 * (attempt + 1)));
    }
  }
  throw new Error(`Wikipedia kept refusing: ${url}`);
}

/**
 * The diagram of this sound, from the article about it.
 *
 * A picture counts when the article names it after the sound ("Voiceless velar
 * plosive.svg") or after the act of making it ("... articulation.svg"): those are
 * the sagittal sections. A vowel has no such picture, and takes the chart the
 * article marks its position on.
 */
/**
 * Commons carries a series of sagittal sections named after the symbol itself
 * ("IPA k Sagittal Section.svg"), drawn to one style and — unlike the articles —
 * covering vowels as well. Where one exists it is the better picture, so it is
 * looked for first.
 */
async function seriesDiagram(symbol) {
  for (const variant of [symbol, symbol.toUpperCase()]) {
    const file = `IPA ${variant} Sagittal Section.svg`;
    const data = await api(
      `${COMMONS}?action=query&format=json&titles=${encodeURIComponent(`File:${file}`)}`,
    );
    const page = Object.values(data?.query?.pages ?? {})[0];
    await new Promise(r => setTimeout(r, 200));
    if (page && !('missing' in page)) return file;
  }
  return null;
}

async function diagramFor(title, name) {
  const data = await api(`${API}?action=query&format=json&prop=images&imlimit=max&titles=${encodeURIComponent(title)}`);
  const page = Object.values(data?.query?.pages ?? {})[0];
  if (!page?.images) return null;

  const files = page.images
    .map(i => i.title.replace(/^File:/, ''))
    .filter(isMouthDiagram);

  const words = name.toLowerCase().split(/[\s-]+/).filter(w => w.length > 3);

  const scored = files.map(file => {
    const lower = file.toLowerCase();
    let score = 0;
    if (/articulation|sagittal|section|mouth|tongue|vocal tract/.test(lower)) score += 5;
    for (const word of words) if (lower.includes(word)) score += 2;
    return { file, score };
  }).filter(s => s.score > 0).sort((a, b) => b.score - a.score);

  if (scored[0]) return scored[0].file;

  // The article does not always embed the diagram even when one exists: Commons
  // holds "Voiceless alveolar fricative articulation.svg" while the article on that
  // sound shows only recordings. So the sound is looked for on Commons itself, and
  // a file is taken only if its name really is this sound's.
  const search = await api(
    `${COMMONS}?action=query&format=json&list=search&srnamespace=6&srlimit=8`
    + `&srsearch=${encodeURIComponent(`${name} articulation`)}`,
  );
  for (const hit of search?.query?.search ?? []) {
    const file = hit.title.replace(/^File:/, '');
    if (!isMouthDiagram(file)) continue;
    const lower = file.toLowerCase();
    const matched = words.filter(w => lower.includes(w)).length;
    // A file named after the sound is that sound's picture, whether or not it also
    // says "articulation": "Alveolar lateral approximant.svg" is the diagram.
    if (matched === words.length) return file;
  }
  return null;
}

const table = JSON.parse(fs.readFileSync(SOURCE, 'utf8'));
let found = 0;
const none = [];

for (const [sym, row] of Object.entries(table.symbols)) {
  if (row.diagram || !row.wiki) continue;
  const file = (await seriesDiagram(sym)) ?? (await diagramFor(row.wiki.replace(/_/g, ' '), row.name));
  await new Promise(r => setTimeout(r, 250));
  if (file) {
    found++;
    row.diagram = file;
  } else {
    none.push(`${sym} (${row.name})`);
  }
}

console.log(`found a diagram for ${found} sounds`);
if (none.length) console.log(`none for ${none.length}: ${none.slice(0, 12).join(', ')}${none.length > 12 ? ' ...' : ''}`);

if (WRITE) {
  fs.writeFileSync(SOURCE, JSON.stringify(table, null, 2) + '\n');
  console.log(`wrote ${SOURCE}`);
} else {
  console.log('(dry run; pass --write to update the table)');
}
