// Link each term of a symbol's description to the article on that term.
//
// A description is a stack of independent phonetic facts: "r-colored open-mid
// central vowel" is r-colouring, and a height, and a backness, and a vowel. Linking
// the phrase as a whole sends the reader to one of them and hides the rest, so each
// term gets its own link.
//
// The article for a term is resolved against Wikipedia, not written from memory. A
// term is disambiguated by the company it keeps: one that only ever appears on
// consonants is looked up as a consonant ("alveolar consonant"), one that only
// appears on vowels as a vowel ("open-mid vowel"). Only titles Wikipedia confirms
// are kept.
//
//   node scripts/link-terms.mjs [--write]

import fs from 'node:fs';

const SOURCE = 'src/data/ipa-symbols.ts';
const API = 'https://en.wikipedia.org/w/api.php';
const UA = 'phonetix-symbol-linker/1.0 (https://github.com/nanbiz/phonetix)';
const WRITE = process.argv.includes('--write');

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
 * The article under this exact title, if it exists and is about speech.
 *
 * A bare term resolves to whatever else carries that name: "minor" and "primary"
 * have articles, and neither is about stress. An article counts only if Wikipedia
 * files it under phonetics, phonology or linguistics — which is the difference
 * between a link that explains the term and one that misleads.
 */
async function exists(title) {
  const url = `${API}?action=query&format=json&redirects=1&prop=categories&cllimit=max`
    + `&titles=${encodeURIComponent(title)}`;
  const data = await api(url);
  const page = Object.values(data?.query?.pages ?? {})[0];
  if (!page || 'missing' in page) return null;

  // Wikipedia answers with the article's maintenance categories as well as its
  // real ones, so the subject is looked for in both the categories and the title:
  // "Aspirated consonant" is filed under nothing that names phonetics, and says so
  // in its title instead.
  const SPEECH = /phonetic|phonolog|linguist|vowel|consonant|prosody|syllable|speech|articulation|phonation|plosive|fricative|affricate|approximant|nasal|trill|flap|sonorant|obstruent|stress|aspiration|length|schwa|roundedness|voice/;
  const categories = (page.categories ?? []).map(c => c.title.toLowerCase()).join(' ');
  if (!SPEECH.test(categories) && !SPEECH.test(page.title.toLowerCase())) return null;

  return page.title;
}

const { IPA_SYMBOLS } = await import('../src/data/ipa-symbols.ts');

// Which kind of sound each term is used to describe, from our own table.
const usage = new Map();
for (const info of Object.values(IPA_SYMBOLS)) {
  for (const term of info.name.toLowerCase().replace(/[()]/g, ' ').split(/\s+/)) {
    if (!term) continue;
    const seen = usage.get(term) ?? { vowel: 0, consonant: 0, other: 0 };
    seen[info.type === 'vowel' ? 'vowel' : info.type === 'consonant' ? 'consonant' : 'other']++;
    usage.set(term, seen);
  }
}

/** Titles to try for a term, most specific first. */
function candidates(term, seen) {
  const Term = term[0].toUpperCase() + term.slice(1);
  const tries = [];

  if (seen.consonant && !seen.vowel) tries.push(`${Term} consonant`);
  if (seen.vowel && !seen.consonant) tries.push(`${Term} vowel`);
  if (seen.vowel && seen.consonant) tries.push(`${Term} consonant`, `${Term} vowel`);

  tries.push(`${Term} (phonetics)`, `${Term} (linguistics)`, Term);
  return tries;
}

const links = {};
const unresolved = [];

for (const [term, seen] of [...usage].sort()) {
  // A word that carries no phonetic claim of its own explains nothing on its own.
  if (['the', 'a', 'in', 'or', 'bar', 'tie', 'below', 'l', 'dark'].includes(term)) continue;

  let found = null;
  for (const title of candidates(term, seen)) {
    found = await exists(title);
    await new Promise(r => setTimeout(r, 250));
    if (found) break;
  }

  if (found) links[term] = found.replace(/ /g, '_');
  else unresolved.push(term);
}

console.log(`resolved ${Object.keys(links).length} terms`);
if (unresolved.length) console.log(`no article for: ${unresolved.join(', ')}`);
for (const [term, title] of Object.entries(links)) console.log(`  ${term.padEnd(16)} -> ${title}`);

if (!WRITE) {
  console.log('\n(dry run; pass --write to update the table)');
  process.exit(0);
}

const source = fs.readFileSync(SOURCE, 'utf8');
const block = `
/**
 * The article on each term a description is built from.
 *
 * A description stacks independent facts ("r-colored open-mid central vowel" is
 * r-colouring, a height, a backness and a vowel), so each term is linked on its
 * own rather than the phrase pointing at one of them. Resolved against Wikipedia
 * by scripts/link-terms.mjs; a term with no article is simply not a link.
 */
export const TERM_LINKS: Record<string, string> = ${JSON.stringify(links, null, 2).replace(/"/g, "'")};
`;

fs.writeFileSync(SOURCE, source.trimEnd() + '\n' + block);
console.log(`\nwrote ${SOURCE}`);
