// ─── Word segmentation ───────────────────────────────────────────────
// Intl.Segmenter handles every script (Latin, Cyrillic, Greek, Arabic, CJK,
// Thai, Devanagari, …) and uses locale-aware, dictionary-based segmentation
// for scripts without spaces (Chinese, Japanese, Thai). The previous Latin-only
// regex silently skipped all non-Latin text.

const segmenters = new Map<string, Intl.Segmenter>();

function segmenter(lang: string): Intl.Segmenter {
  let s = segmenters.get(lang);
  if (!s) {
    try {
      s = new Intl.Segmenter(lang, { granularity: 'word' });
    } catch {
      s = new Intl.Segmenter('und', { granularity: 'word' });
    }
    segmenters.set(lang, s);
  }
  return s;
}

/** A word segment carries at least one letter; numbers/punctuation are not words. */
const HAS_LETTER = /\p{L}/u;

export interface Segment {
  text: string;
  /** True for a real word (word-like per Intl AND contains a letter). */
  isWord: boolean;
}

/** Segment text into ordered word / non-word pieces (covers the whole string). */
export function segment(text: string, lang: string, maxWordLen = 50): Segment[] {
  const out: Segment[] = [];
  for (const s of segmenter(lang).segment(text)) {
    const word = s.isWordLike === true && s.segment.length <= maxWordLen && HAS_LETTER.test(s.segment);
    out.push({ text: s.segment, isWord: word });
  }
  return out;
}

/** Just the lowercased words in the text, in order. */
export function words(text: string, lang: string, maxWordLen = 50): string[] {
  const out: string[] = [];
  for (const s of segmenter(lang).segment(text)) {
    if (s.isWordLike === true && s.segment.length <= maxWordLen && HAS_LETTER.test(s.segment)) {
      out.push(s.segment.toLowerCase());
    }
  }
  return out;
}

// ─── Script identification (Unicode, off-the-shelf) ──────────────────
// Used to gate espeak: espeak reads a word with the block-language voice, which
// only produces real phonemes when the word's script matches the language. Fed a
// foreign script it spells out letter names. The language's expected script is
// derived from its dictionary (see dominantScript); nothing is hardcoded.

const SCRIPT_TESTS: [string, RegExp][] = [
  ['Latin', /\p{Script=Latin}/u],
  ['Cyrillic', /\p{Script=Cyrillic}/u],
  ['Greek', /\p{Script=Greek}/u],
  ['Arabic', /\p{Script=Arabic}/u],
  ['Hebrew', /\p{Script=Hebrew}/u],
  ['Han', /\p{Script=Han}/u],
  ['Hiragana', /\p{Script=Hiragana}/u],
  ['Katakana', /\p{Script=Katakana}/u],
  ['Hangul', /\p{Script=Hangul}/u],
  ['Devanagari', /\p{Script=Devanagari}/u],
  ['Bengali', /\p{Script=Bengali}/u],
  ['Tamil', /\p{Script=Tamil}/u],
  ['Thai', /\p{Script=Thai}/u],
  ['Armenian', /\p{Script=Armenian}/u],
  ['Georgian', /\p{Script=Georgian}/u],
];

/** The Unicode script of a word (its first letter). '' if none. */
export function scriptOf(word: string): string {
  for (const ch of word) {
    for (const [name, re] of SCRIPT_TESTS) if (re.test(ch)) return name;
  }
  return '';
}

/** Dominant script across a sample of a dictionary's keys — the language's script. */
export function dominantScript(keys: string[]): string {
  const step = Math.max(1, Math.floor(keys.length / 600));
  const counts = new Map<string, number>();
  for (let i = 0; i < keys.length; i += step) {
    const s = scriptOf(keys[i]);
    if (s) counts.set(s, (counts.get(s) ?? 0) + 1);
  }
  let best = '', n = 0;
  for (const [s, c] of counts) if (c > n) { best = s; n = c; }
  return best;
}

/** espeak spells an unknown word out letter by letter, emitting spaces between
 *  the pieces. A genuine single-word phonemization has none — so any space means
 *  espeak gave up and named the characters. Voice- and script-independent. */
export function isLetterSpelling(espeakIpa: string): boolean {
  return espeakIpa.trim().includes(' ');
}

/** May espeak be trusted for this word under a block language whose script is
 *  `langScript` (derived from its dictionary)?
 *   - A foreign-script word would be spelled out as letter names — skip.
 *   - Han is logographic: espeak has no grapheme-to-phoneme for it and names the
 *     Unicode block ("chinese letter") for out-of-table characters — skip. */
export function espeakAllowed(word: string, langScript: string): boolean {
  const s = scriptOf(word);
  if (s === 'Han') return false;
  return langScript === '' || s === langScript;
}
