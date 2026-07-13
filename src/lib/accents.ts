/**
 * What an accent is made of.
 *
 * Two independent things decide whether an accent can be offered at all:
 *
 *  - `tags`: the accent labels the source dictionary uses. They give the real
 *    pronunciation of the words the dictionary knows, which is most of a page.
 *    An accent with no tagged data would leave the page unchanged.
 *
 *  - `voice`: the espeak voice used for words no dictionary knows, and for the
 *    spoken audio. espeak does not have a voice for every accent, and asked for
 *    one it lacks it does not fail — it emits nonsense ("əəəəəəə"). So a voice
 *    is named here only if espeak really speaks it (scripts/test-voices asserts
 *    this), and otherwise the language's base voice is used: a synthesized word
 *    then keeps the standard accent while every dictionary word carries the
 *    chosen one.
 *
 * Accents whose difference is a rule rather than a word list (Latin-American
 * Spanish is seseo and yeísmo, in every word) carry no tags and are handled by
 * the transforms in regions.ts, which cover the whole vocabulary instead of the
 * fraction Wiktionary happens to have tagged.
 */
export interface Accent {
  /** Accent id, also the key the user's choice is stored under. */
  id: string;
  label: string;
  /** Source-dictionary tags carrying this accent's pronunciation, best first. */
  tags: string[];
  /** espeak voice, when espeak has a real one. Otherwise the base language voice. */
  voice?: string;
}

/** Accents with enough tagged data to change what the reader sees, per language. */
export const ACCENTS: Record<string, Accent[]> = {
  en: [
    { id: 'en-gb', label: 'British', tags: ['Received-Pronunciation', 'British', 'UK'], voice: 'en-gb-x-rp' },
    { id: 'en-us', label: 'American', tags: ['General-American', 'US'], voice: 'en-us' },
    { id: 'en-au', label: 'Australian', tags: ['Australian'], voice: 'en-au' },
    { id: 'en-scotland', label: 'Scottish', tags: ['Scotland'], voice: 'en-gb-scotland' },
    // espeak has no Canadian, New Zealand, Irish or Indian voice; the dictionary does.
    { id: 'en-ca', label: 'Canadian', tags: ['Canada'] },
    { id: 'en-nz', label: 'New Zealand', tags: ['New-Zealand'] },
    { id: 'en-ie', label: 'Irish', tags: ['Ireland', 'Northern-Ireland'] },
    { id: 'en-in', label: 'Indian', tags: ['India', 'South-Asia'] },
  ],
  pt: [
    { id: 'pt', label: 'European', tags: ['Portugal'] },
    { id: 'pt-br', label: 'Brazilian', tags: ['Brazil', 'Southern-Brazil', 'São-Paulo', 'Rio-de-Janeiro'], voice: 'pt-br' },
  ],
  ca: [
    { id: 'ca', label: 'Central', tags: ['Central'] },
    { id: 'ca-valencia', label: 'Valencian', tags: ['Valencia'] },
    { id: 'ca-balearic', label: 'Balearic', tags: ['Balearic'] },
    { id: 'ca-northwestern', label: 'Northwestern', tags: ['Northwestern'] },
  ],
  zh: [
    { id: 'zh', label: 'Mandarin', tags: ['Mandarin', 'Standard-Chinese'] },
    // espeak has no Cantonese or Western Armenian voice; the dictionary does.
    { id: 'zh-yue', label: 'Cantonese', tags: ['Cantonese', 'Guangzhou'] },
    { id: 'zh-nan', label: 'Min Nan', tags: ['Min-Nan', 'Hokkien'] },
    { id: 'zh-hak', label: 'Hakka', tags: ['Hakka', 'Sixian'] },
  ],
  fa: [
    { id: 'fa', label: 'Iranian', tags: ['Iran', 'Tehrani'] },
    { id: 'fa-dari', label: 'Dari', tags: ['Dari', 'Kabuli'] },
    { id: 'fa-tajik', label: 'Tajik', tags: ['Tajik'] },
  ],
  hy: [
    { id: 'hy', label: 'Eastern', tags: ['Eastern-Armenian'] },
    { id: 'hy-west', label: 'Western', tags: ['Western-Armenian'] },
  ],
  cy: [
    { id: 'cy', label: 'North Wales', tags: ['North-Wales'] },
    { id: 'cy-south', label: 'South Wales', tags: ['South-Wales'] },
  ],
  ga: [
    { id: 'ga', label: 'Munster', tags: ['Munster'] },
    { id: 'ga-ulster', label: 'Ulster', tags: ['Ulster'] },
    { id: 'ga-connacht', label: 'Connacht', tags: ['Connacht', 'Galway'] },
  ],
  eu: [
    { id: 'eu', label: 'Standard', tags: [] },
    { id: 'eu-biscayan', label: 'Biscayan', tags: ['Biscayan'] },
    { id: 'eu-navarro', label: 'Navarro-Lapurdian', tags: ['Navarro-Lapurdian'] },
  ],
  vi: [
    { id: 'vi', label: 'Hanoi', tags: ['Hà-Nội'] },
    { id: 'vi-hue', label: 'Huế', tags: ['Huế'], voice: 'vi-vn-x-central' },
  ],
};

/**
 * The espeak voice to synthesize and speak with for a chosen accent.
 *
 * An accent that espeak cannot speak falls back to the language's base voice.
 * Never to the accent id itself: espeak answers an unknown voice with nonsense
 * rather than an error, so "en-ca" would reach the page as "əəəəəəə".
 */
export function voiceForAccent(lang: string, accentId: string): string {
  const known = ACCENTS[lang];
  if (known) {
    const accent = known.find(a => a.id === accentId);
    return accent?.voice ?? lang;
  }
  // Languages configured outside this table name espeak voices directly.
  return accentId || lang;
}

/** The overlay file holding a language's accent-specific pronunciations. */
export function overlayName(lang: string, accentId: string): string {
  return `${lang}.${accentId}`;
}
