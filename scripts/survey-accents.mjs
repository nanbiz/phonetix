// Survey which accents the source data can actually support.
//
// A kaikki entry carries several `sounds[]`, each optionally tagged with the
// accent it belongs to ("US", "Received-Pronunciation", "Switzerland", ...).
// The dictionary build keeps only the first, which is why choosing an accent
// changes nothing for a word the dictionary knows.
//
// This measures, per language and per accent tag: how many words carry that tag,
// and for how many of them the tagged pronunciation actually differs from the
// language's default. An accent is only worth shipping if the second number is
// substantial — a tag that reproduces the default is a label, not an accent.
//
//   node scripts/survey-accents.mjs [maxLines]

import fs from 'node:fs';
import zlib from 'node:zlib';
import readline from 'node:readline';

const DUMP = '.cache/raw-wiktextract-data.jsonl.gz';
const MAX = Number(process.argv[2] || 0);

/** Languages the extension ships a dictionary for. */
const LANGS = new Set(JSON.parse(
  fs.readFileSync('public/dictionaries/manifest.json', 'utf8'),
) instanceof Object ? Object.keys(JSON.parse(fs.readFileSync('public/dictionaries/manifest.json', 'utf8'))) : []);

function cleanIpa(raw) {
  if (!raw) return null;
  let ipa = String(raw).replace(/^[/[]|[/\]]$/g, '').trim();
  if (!ipa || ipa.length > 100) return null;
  if (ipa.includes('{{') || ipa.includes('}}') || ipa.includes('|')) return null;
  return ipa;
}

// lang -> tag -> {words:Set, differing:Set}
const stats = new Map();

const rl = readline.createInterface({
  input: fs.createReadStream(DUMP).pipe(zlib.createGunzip()),
  crlfDelay: Infinity,
});

let lines = 0;
for await (const line of rl) {
  if (MAX && ++lines > MAX) break;
  if (!line || line[0] !== '{') continue;

  let e;
  try { e = JSON.parse(line); } catch { continue; }

  const lang = e.lang_code;
  if (!lang || !LANGS.has(lang)) continue;
  const word = e.word;
  const sounds = e.sounds;
  if (!word || !Array.isArray(sounds) || !sounds.length) continue;

  // The default is the first usable IPA, exactly as the dictionary build takes it.
  let base = null;
  for (const s of sounds) {
    const ipa = cleanIpa(s.ipa);
    if (ipa) { base = ipa; break; }
  }
  if (!base) continue;

  for (const s of sounds) {
    const ipa = cleanIpa(s.ipa);
    if (!ipa || !Array.isArray(s.tags) || !s.tags.length) continue;
    for (const tag of s.tags) {
      let byTag = stats.get(lang);
      if (!byTag) stats.set(lang, (byTag = new Map()));
      let rec = byTag.get(tag);
      if (!rec) byTag.set(tag, (rec = { words: new Set(), differing: new Set() }));
      rec.words.add(word);
      if (ipa !== base) rec.differing.add(word);
    }
  }
}

const rows = [];
for (const [lang, byTag] of stats) {
  for (const [tag, rec] of byTag) {
    if (rec.words.size < 200) continue;   // too thin to judge
    rows.push({
      lang,
      tag,
      words: rec.words.size,
      differing: rec.differing.size,
      pctDiffer: (100 * rec.differing.size) / rec.words.size,
    });
  }
}
rows.sort((a, b) => a.lang.localeCompare(b.lang) || b.differing - a.differing);

console.log('lang  tag                                words   differing  differ%');
for (const r of rows) {
  console.log(
    `${r.lang.padEnd(5)} ${r.tag.slice(0, 32).padEnd(34)} ${String(r.words).padStart(7)} ${String(r.differing).padStart(10)}  ${r.pctDiffer.toFixed(0).padStart(5)}%`,
  );
}
fs.writeFileSync('.cache/accent-survey.json', JSON.stringify(rows, null, 2));
console.log(`\n${rows.length} tag/language pairs -> .cache/accent-survey.json`);
