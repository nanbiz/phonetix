// What the card says about a form: the names of its grammatical values, what each category and
// value is, and which part of a spelling makes it the form it is.
//
// The explanations are Wiktionary's, gathered into data/grammar.json by tools/grammar_terms.py.
// The file is read when it is there; a term it has no text for is named and nothing more, since
// a definition written here would be ours passed off as the dictionary's.
import type { ParadigmForm, PlaceValue } from '@/core/answer';
import { SAYS, say } from '@/data/wording';
import { plainIn, type Plain } from '@/data/interface';

/** One category or value as data/grammar.json describes it. */
interface Described {
  text?: string | null;
  source?: string | null;
}

interface Grammar {
  categories?: Record<string, Described & { name?: string }>;
  values?: Record<string, Described & { category?: string }>;
}

// Globbed rather than imported, so a build made before the file exists still builds and draws
// every term by its name.
const files = import.meta.glob<Grammar>('../../../data/grammar.json', {
  eager: true,
  import: 'default',
});
const grammar: Grammar = Object.values(files)[0] ?? {};

/** What each term is said plainly, written for a reader who has never met it: Wiktionary's
 *  glossary is written for one who has, and "hypothetical or anticipated rather than actual"
 *  told nobody what a subjunctive is. */
const plainFiles = import.meta.glob<{ categories?: Record<string, Plain>; values?: Record<string, Plain> }>(
  '../../../data/grammar-plain.json',
  { eager: true, import: 'default' }
);
const plain = Object.values(plainFiles)[0] ?? {};

/** What a term is, plainly where it has been said plainly and as Wiktionary has it otherwise,
 *  with its example after it, the stars that mark the example's word taken out. */
function told(said: Plain | undefined, wiktionary: string | null | undefined): string | null {
  if (!said?.plain) return wiktionary ?? null;
  return said.example
    ? `${said.plain} ${say('for-example', said.example.replaceAll('*', ''))}`
    : said.plain;
}

/** What a category is. */
export function categoryText(category: string): string | null {
  return told(
    plainIn.categories?.[category] ?? plain.categories?.[category],
    grammar.categories?.[category]?.text
  );
}

/** What a value is. */
export function valueText(value: string): string | null {
  return told(plainIn.values?.[value] ?? plain.values?.[value], grammar.values?.[value]?.text);
}

/** A value as a reader reads it, in the interface's language where it has a name for it:
 *  "3rd" for "third-person", "future perfect" for "future-perfect". */
export function valueName(value: string): string {
  return SAYS[`ordinal-${value}`] ?? SAYS[`term-${value}`] ?? value.replaceAll('-', ' ');
}

/** A category as a sheet names it. Person moves with number, so the sheet is about both. */
export function categoryName(category: string): string {
  return category === 'person' ? say('person-and-number') : (SAYS[`term-${category}`] ?? category);
}

/**
 * One term of a form line: a value, or a person with its number, which a grammar names
 * together ("3rd singular") and lays out as one grid.
 */
export interface Term {
  /** The category of the sheet the term opens, which is the one its along is filed under. */
  category: string;
  values: PlaceValue[];
  /** As the form line says it: "indicative", "3rd singular". */
  label: string;
  /** As a sheet's header says it: "3rd person singular". */
  long: string;
}

/** The terms of a place, in the order the core names them. */
export function termsOf(place: PlaceValue[]): Term[] {
  const person = place.find((value) => value.category === 'person');
  const number = place.find((value) => value.category === 'number');
  const out: Term[] = [];
  for (const value of place) {
    if (value === number && person) continue;
    if (value === person) {
      const values = number ? [person, number] : [person];
      const ordinal = valueName(person.value);
      const many = number ? valueName(number.value) : '';
      out.push({
        category: 'person',
        values,
        label: number ? say('person-number', ordinal, many) : ordinal,
        long: number ? say('person-number-long', ordinal, many) : say('person-long', ordinal),
      });
      continue;
    }
    const name = valueName(value.value);
    out.push({ category: value.category, values: [value], label: name, long: name });
  }
  return out;
}

/** The value a place has in a category. */
export function valueIn(place: PlaceValue[], category: string): string | undefined {
  return place.find((value) => value.category === category)?.value;
}

/** Whether two places are the same place of a table. */
export function samePlace(one: PlaceValue[], other: PlaceValue[]): boolean {
  const key = (place: PlaceValue[]) =>
    place
      .map((value) => `${value.category}:${value.value}`)
      .sort()
      .join(' ');
  return key(one) === key(other);
}

/** A spelling cut where its own ending starts, by characters rather than code units. */
export function cut(spelling: string, at: number): [string, string] {
  const letters = Array.from(spelling);
  return [letters.slice(0, at).join(''), letters.slice(at).join('')];
}

/** How many characters a set of spellings start with alike, ignoring case. */
export function sharedStart(spellings: string[]): number {
  const lowered = spellings.map((spelling) => Array.from(spelling.toLowerCase()));
  if (lowered.length === 0) return 0;
  let at = 0;
  while (lowered.every((letters) => at < letters.length && letters[at] === lowered[0][at])) at++;
  return at;
}

/**
 * The stem every form in a table shares with the lemma, so each row of a sheet draws the
 * part that changes in the accent: "and" across anda, anduvo, andaba, andará.
 */
export function stemOf(forms: ParadigmForm[], lemma: string | null): number {
  return sharedStart([...(lemma ? [lemma] : []), ...forms.map((form) => form.spelling)]);
}

/**
 * Who each cell of a person grid is, out of what it says: "I", "you all", "they" from "I
 * walked", "you all walked", "they walked". What every cell says alike before and after is
 * the verb, and what is left is the person.
 */
export function whoOf(saids: string[]): string[] {
  const split = saids.map((said) => said.split(' '));
  if (split.length < 2) return saids;
  let head = 0;
  while (split.every((words) => head < words.length - 1 && words[head] === split[0][head])) head++;
  let tail = 0;
  while (
    split.every(
      (words) =>
        tail < words.length - head - 1 &&
        words[words.length - 1 - tail] === split[0][split[0].length - 1 - tail]
    )
  )
    tail++;
  return split.map((words) => words.slice(head, words.length - tail).join(' '));
}
