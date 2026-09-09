#!/usr/bin/env node
/**
 * Download and process kaikki.org English Wiktionary dump into
 * per-language IPA dictionaries.
 *
 * Usage: node scripts/build-dictionaries.mjs
 *
 * Downloads the ~2.3GB gzipped JSONL, streams through it, extracts
 * word + lang_code + first IPA from sounds[], groups by language,
 * and writes gzipped JSON to assets/dictionaries/{lang}.json.gz
 */

import { createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'fs';
import { createGunzip, createGzip } from 'zlib';
import { pipeline } from 'stream/promises';
import { createInterface } from 'readline';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';
import { Writable } from 'stream';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const OUT_DIR = join(ROOT, 'assets', 'dictionaries');
const CACHE_DIR = join(ROOT, '.cache');
const DUMP_URL = 'https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz';
const DUMP_FILE = join(CACHE_DIR, 'raw-wiktextract-data.jsonl.gz');
const MIN_ENTRIES = 1000; // Skip languages with fewer entries

// Languages we want dictionaries for (must have espeak-ng voices)
const WANTED_LANGS = new Set([
  'af', 'ar', 'bg', 'bn', 'bs', 'ca', 'cs', 'cy', 'da', 'de',
  'el', 'en', 'eo', 'es', 'et', 'eu', 'fa', 'fi', 'fr', 'ga',
  'hi', 'hr', 'hu', 'hy', 'id', 'is', 'it', 'ja', 'ka', 'kk',
  'ko', 'ku', 'la', 'lt', 'lv', 'mk', 'ml', 'ms', 'my', 'nb',
  'nl', 'no', 'pl', 'pt', 'ro', 'ru', 'sk', 'sl', 'sq', 'sr',
  'sv', 'sw', 'ta', 'te', 'th', 'tr', 'uk', 'ur', 'uz', 'vi',
  'zh',
]);

// kaikki uses some codes that differ from what our extension uses.
// Map kaikki code → extension code for the output filename.
const LANG_CODE_MAP = {
  'nb': 'no', // Norwegian Bokmål → Norwegian
};

async function downloadDump() {
  if (existsSync(DUMP_FILE)) {
    const size = statSync(DUMP_FILE).size;
    console.log(`Using cached dump (${(size / 1e9).toFixed(1)}GB): ${DUMP_FILE}`);
    return;
  }

  mkdirSync(CACHE_DIR, { recursive: true });
  console.log(`Downloading ${DUMP_URL} ...`);
  console.log('This is ~2.3GB and may take a while.');

  const res = await fetch(DUMP_URL);
  if (!res.ok) throw new Error(`Download failed: ${res.status} ${res.statusText}`);

  const total = Number(res.headers.get('content-length')) || 0;
  let downloaded = 0;
  let lastPct = -1;

  const fileStream = createWriteStream(DUMP_FILE);
  const reader = res.body.getReader();

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    fileStream.write(value);
    downloaded += value.length;
    if (total > 0) {
      const pct = Math.floor(downloaded / total * 100);
      if (pct !== lastPct) {
        process.stdout.write(`\r  ${pct}% (${(downloaded / 1e6).toFixed(0)}MB / ${(total / 1e6).toFixed(0)}MB)`);
        lastPct = pct;
      }
    } else {
      process.stdout.write(`\r  ${(downloaded / 1e6).toFixed(0)}MB downloaded`);
    }
  }

  await new Promise((resolve, reject) => {
    fileStream.end(() => resolve());
    fileStream.on('error', reject);
  });
  console.log('\nDownload complete.');
}

function cleanIpa(raw) {
  if (!raw) return null;
  // Strip surrounding /.../ or [...]
  let ipa = raw.replace(/^[/\[]|[/\]]$/g, '').trim();
  // Skip entries that are just references or empty
  if (!ipa || ipa.length < 1 || ipa.length > 100) return null;
  // Skip entries with obvious template artifacts
  if (ipa.includes('{{') || ipa.includes('}}') || ipa.includes('|')) return null;
  return ipa;
}

async function processDump() {
  console.log('Processing dump...');
  const langData = new Map(); // lang -> Map<word, ipa>

  const gunzip = createGunzip();
  const fileStream = createReadStream(DUMP_FILE);

  const rl = createInterface({
    input: fileStream.pipe(gunzip),
    crlfDelay: Infinity,
  });

  let lineCount = 0;
  let extracted = 0;

  for await (const line of rl) {
    lineCount++;
    if (lineCount % 500_000 === 0) {
      process.stdout.write(`\r  Processed ${(lineCount / 1e6).toFixed(1)}M lines, extracted ${extracted} entries`);
    }

    try {
      const entry = JSON.parse(line);
      const langCode = entry.lang_code;
      if (!langCode || !WANTED_LANGS.has(langCode)) continue;

      const word = entry.word;
      if (!word || word.length > 80 || word.includes(' ')) continue;

      // Extract first valid IPA from sounds array
      const sounds = entry.sounds;
      if (!Array.isArray(sounds) || sounds.length === 0) continue;

      let ipa = null;
      for (const s of sounds) {
        if (s.ipa) {
          ipa = cleanIpa(s.ipa);
          if (ipa) break;
        }
      }
      if (!ipa) continue;

      if (!langData.has(langCode)) langData.set(langCode, new Map());
      const dict = langData.get(langCode);
      // First entry wins (usually the most common pronunciation)
      const wordLower = word.toLowerCase();
      if (!dict.has(wordLower)) {
        dict.set(wordLower, ipa);
        extracted++;
      }
    } catch {
      // Skip malformed lines
    }
  }

  console.log(`\nProcessed ${lineCount} lines, extracted ${extracted} entries across ${langData.size} languages.`);
  return langData;
}

/**
 * Merge existing TSV data from scripts/data/ into the kaikki extraction.
 * TSV files have much better coverage for specific languages (e.g. de.txt
 * has 787K entries from German Wiktionary vs 69K from English Wiktionary).
 * TSV entries take priority over kaikki entries.
 */
function mergeTsvData(langData) {
  const TSV_MAP = {
    'en_US.txt': 'en',
    'en_UK.txt': 'en',  // merge into en
    'de.txt': 'de',
    'es_ES.txt': 'es',
    'fr_FR.txt': 'fr',
  };

  for (const [file, lang] of Object.entries(TSV_MAP)) {
    const tsvPath = join(__dirname, 'data', file);
    if (!existsSync(tsvPath)) continue;

    const content = readFileSync(tsvPath, 'utf-8');
    if (!langData.has(lang)) langData.set(lang, new Map());
    const dict = langData.get(lang);
    let added = 0;

    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      const tab = line.indexOf('\t');
      if (tab < 0) continue;
      const word = line.slice(0, tab).toLowerCase();
      const rawIpa = line.slice(tab + 1).trim();
      // Take first IPA if multiple (format: /ipa1/, /ipa2/)
      const m = rawIpa.match(/\/([^/]+)\//);
      const ipa = m?.[1]?.trim();
      if (!word || !ipa || word.length > 80 || word.includes(' ')) continue;

      // TSV data overrides kaikki data (it's from the language-specific Wiktionary)
      if (!dict.has(word)) added++;
      dict.set(word, ipa);
    }

    console.log(`  Merged ${file}: +${added.toLocaleString()} new entries → ${dict.size.toLocaleString()} total for ${lang}`);
  }
}

async function writeDictionaries(langData) {
  mkdirSync(OUT_DIR, { recursive: true });

  const stats = [];

  // Merge mapped language codes (e.g. nb data → no output)
  for (const [from, to] of Object.entries(LANG_CODE_MAP)) {
    if (langData.has(from) && !langData.has(to)) {
      langData.set(to, langData.get(from));
      langData.delete(from);
    } else if (langData.has(from) && langData.has(to)) {
      // Merge into target (existing entries take priority)
      const target = langData.get(to);
      for (const [word, ipa] of langData.get(from)) {
        if (!target.has(word)) target.set(word, ipa);
      }
      langData.delete(from);
    }
  }

  for (const [lang, dict] of langData) {
    if (dict.size < MIN_ENTRIES) {
      console.log(`  Skipping ${lang}: only ${dict.size} entries (min: ${MIN_ENTRIES})`);
      continue;
    }

    // Build plain object
    const obj = {};
    for (const [word, ipa] of dict) {
      obj[word] = ipa;
    }

    const jsonStr = JSON.stringify(obj);
    const outPath = join(OUT_DIR, `${lang}.json.gz`);

    // Gzip and write
    await new Promise((resolve, reject) => {
      const gzip = createGzip({ level: 9 });
      const out = createWriteStream(outPath);
      gzip.pipe(out);
      gzip.write(jsonStr);
      gzip.end();
      out.on('finish', resolve);
      out.on('error', reject);
    });

    const size = statSync(outPath).size;
    stats.push({ lang, entries: dict.size, sizeKB: Math.round(size / 1024) });
    console.log(`  ${lang}: ${dict.size.toLocaleString()} entries → ${(size / 1024).toFixed(0)}KB`);
  }

  // Write manifest with language info
  const manifest = {};
  for (const s of stats) {
    manifest[s.lang] = { entries: s.entries, sizeKB: s.sizeKB };
  }
  writeFileSync(join(OUT_DIR, 'manifest.json'), JSON.stringify(manifest, null, 2));
  console.log(`\nWrote ${stats.length} dictionaries + manifest.json to ${OUT_DIR}`);
  console.log(`Total size: ${stats.reduce((a, s) => a + s.sizeKB, 0).toLocaleString()}KB`);
}

async function main() {
  console.log('=== Building IPA Dictionaries from kaikki.org ===\n');
  await downloadDump();
  const langData = await processDump();
  console.log('\nMerging TSV data from scripts/data/ ...');
  mergeTsvData(langData);
  await writeDictionaries(langData);
  console.log('\nDone!');
}

main().catch((err) => {
  console.error('Error:', err);
  process.exit(1);
});
