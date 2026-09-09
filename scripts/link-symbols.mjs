// Give every IPA symbol a link to the article describing that sound.
//
// Wikipedia carries one article per sound, named after the sound itself
// ("Voiceless velar plosive"), which is a better source for phonetics than a
// dictionary entry: it explains the articulation, lists the languages that use
// the sound, and holds a recording.
//
// The titles are resolved against Wikipedia rather than guessed: a title written
// from the symbol's name would silently point at nothing for every sound whose
// article is named a little differently. Only a title Wikipedia confirms is
// written back into the table.
//
//   node scripts/link-symbols.mjs [--write]

import fs from 'node:fs';

const SOURCE = 'src/data/ipa-symbols.ts';
const API = 'https://en.wikipedia.org/w/api.php';
const WRITE = process.argv.includes('--write');

/** Ask Wikipedia for the article on this sound. Null when it has none. */
const UA = 'phonetix-symbol-linker/1.0 (https://github.com/nanbiz/phonetix)';

/** Wikipedia throttles hard; a rejected request must be retried, not read as JSON. */
async function api(url) {
  for (let attempt = 0; attempt < 6; attempt++) {
    const res = await fetch(url, { headers: { 'User-Agent': UA, 'Accept': 'application/json' } });
    const text = await res.text();
    try {
      return JSON.parse(text);
    } catch {
      await new Promise(r => setTimeout(r, 2000 * (attempt + 1)));
    }
  }
  throw new Error(`Wikipedia kept refusing: ${url}`);
}

async function resolveTitle(name) {
  // The parenthetical in a readable name ("schwa (mid central vowel)") is a gloss,
  // not part of any title.
  const query = name.replace(/\s*\([^)]*\)/g, '').trim();

  const url = `${API}?action=query&format=json&redirects=1&titles=${encodeURIComponent(query)}`;
  const data = await api(url);
  const pages = Object.values(data?.query?.pages ?? {});
  const page = pages[0];
  if (page && !('missing' in page)) return page.title;

  // No article under that name: ask what Wikipedia would suggest for it, and take
  // the suggestion only if it is about the sound rather than something else.
  const search = `${API}?action=query&format=json&list=search&srlimit=3&srsearch=${encodeURIComponent(query)}`;
  const found = await api(search);
  for (const hit of found?.query?.search ?? []) {
    const title = hit.title.toLowerCase();
    if (/(plosive|fricative|nasal|approximant|trill|flap|vowel|affricate|stop|click|schwa|stress|tone)/.test(title)) {
      return hit.title;
    }
  }
  return null;
}

const source = fs.readFileSync(SOURCE, 'utf8');
const entry = /^(\s*)'(?<sym>[^']+)':\s*\{\s*name:\s*'(?<name>[^']+)'(?<rest>.*)$/;

const lines = source.split('\n');
const out = [];
let linked = 0;
let unlinked = [];

for (const line of lines) {
  const m = line.match(entry);
  if (!m || line.includes('wiki:')) {
    out.push(line);
    continue;
  }

  const { sym, name, rest } = m.groups;
  const title = await resolveTitle(name);
  if (title) {
    linked++;
    out.push(line.replace(`name: '${name}'`, `name: '${name}', wiki: '${title.replace(/ /g, '_')}'`));
  } else {
    unlinked.push(`${sym} (${name})`);
    out.push(line);
  }
  await new Promise(r => setTimeout(r, 300));   // be polite to the API
}

console.log(`linked ${linked} symbols to their article`);
if (unlinked.length) console.log(`no article for ${unlinked.length}: ${unlinked.join(', ')}`);

if (WRITE) {
  fs.writeFileSync(SOURCE, out.join('\n'));
  console.log(`wrote ${SOURCE}`);
} else {
  console.log('(dry run; pass --write to update the table)');
}
