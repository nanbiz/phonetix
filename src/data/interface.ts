// What the interface says, in the reader's own language.
//
// The words are authored once, in English, in data/wording.json, and each language's are a
// table beside it in public/interface/<lang>.json with the same names. Switching swaps the
// words in the tables every surface already reads from - the rows, the choices, the phrases -
// so nothing that draws them needs to know which language it is drawing in. A word a table
// does not have stays English: a row half in the reader's language is better than a row
// missing.
import { CHOICES, ENDS, ROWS, SAYS, type Words } from './wording';
import { LANGUAGES, nameIn } from './languages';
import { ACCENTS } from './accents';

/** A grammatical term said plainly, with an example whose word in that form is in stars. */
export interface Plain {
  plain?: string;
  example?: string;
}

/** What each term is said plainly in the interface's language, where it is not English. */
export const plainIn: { categories?: Record<string, Plain>; values?: Record<string, Plain> } = {};

/** One language's words, by the names data/wording.json gives them. */
export interface Spoken {
  says?: Record<string, string>;
  rows?: Record<string, Partial<Words>>;
  ends?: Record<string, [string, string]>;
  /** By table, then by the value each choice is stored as. */
  choices?: Record<
    string,
    Record<string, { label?: string; about?: string | { browser?: string; phone?: string } }>
  >;
  /** What each grammatical term is, said plainly, as data/grammar-plain.json has it. */
  plain?: typeof plainIn;
}

/** The languages the interface has words in besides English. */
export const SPOKEN = Object.keys(LANGUAGES).filter((lang) => lang !== 'en');

/** The English, kept to go back to. */
const english = {
  says: { ...SAYS },
  rows: Object.fromEntries(Object.entries(ROWS).map(([name, row]) => [name, { ...row }])),
  ends: Object.fromEntries(Object.entries(ENDS).map(([name, pair]) => [name, [...pair]])) as Record<
    string,
    [string, string]
  >,
  choices: Object.fromEntries(
    Object.entries(CHOICES).map(([name, rows]) => [name, rows.map((row) => ({ ...row }))])
  ),
  accents: Object.fromEntries(
    Object.entries(ACCENTS).map(([lang, rows]) => [lang, rows.map((row) => row.name)])
  ),
};

/** The language the interface is in now. */
export let speaking = 'en';

/** Say everything in these words from now on, or in English with none. */
export function speakIn(lang: string, words: Spoken | null): void {
  speaking = words ? lang : 'en';
  for (const key of Object.keys(SAYS)) delete SAYS[key];
  Object.assign(SAYS, english.says, words?.says ?? {});
  for (const [name, row] of Object.entries(english.rows)) {
    ROWS[name] = { ...row, ...(words?.rows?.[name] ?? {}) };
  }
  for (const [name, pair] of Object.entries(english.ends)) {
    ENDS[name] = words?.ends?.[name] ?? [...pair];
  }
  for (const [name, rows] of Object.entries(english.choices)) {
    rows.forEach((row, at) => {
      const said = words?.choices?.[name]?.[row.value];
      const about =
        typeof said?.about === 'string' ? said.about : said?.about?.browser || said?.about?.phone;
      Object.assign(CHOICES[name][at], row, said?.label ? { label: said.label } : {},
        about ? { about } : {});
    });
  }
  // An accent by the place or people it is named for, which the tables call accent-<name>.
  for (const [lang, names] of Object.entries(english.accents)) {
    ACCENTS[lang].forEach((row, at) => {
      row.name = SAYS[`accent-${names[at]}`] ?? names[at];
    });
  }
  for (const key of Object.keys(plainIn) as (keyof typeof plainIn)[]) delete plainIn[key];
  if (words?.plain) Object.assign(plainIn, words.plain);
  nameIn(speaking);
}

/** Say on a document of our own which language it is in and which way it reads. Never on a
 *  page's: the card is ours, the document it is drawn over is not. */
export function markDocument(): void {
  document.documentElement.lang = speaking;
  document.documentElement.dir = LANGUAGES[speaking]?.rtl ? 'rtl' : 'ltr';
}

/** The language the interface is drawn in for a reader whose own language is this one: theirs
 *  where it has words for it, English otherwise. */
export function interfaceFor(mine: string): string {
  return SPOKEN.includes(mine) ? mine : 'en';
}

/** A language's words, from the extension's own files; none for English, or where they cannot
 *  be read. */
export async function spokenFrom(lang: string): Promise<Spoken | null> {
  if (lang === 'en') return null;
  try {
    const res = await fetch(browser.runtime.getURL(`/interface/${lang}.json` as never));
    return res.ok ? ((await res.json()) as Spoken) : null;
  } catch {
    return null;
  }
}
