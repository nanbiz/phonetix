// ─── Language configuration (single source of truth) ─────────────────
// To add a new language: just add an entry here. Everything else derives.

import { ACCENTS } from './accents';

export interface LanguageConfig {
  name: string;
  /** Default espeak-ng voice ID */
  defaultAccent: string;
  /** Available espeak-ng accents: { voiceId: displayName } */
  accents: Record<string, string>;
  /** Wiktionary pronunciation section heading (if real-time Wiktionary lookup is supported) */
  wiktAnchor?: string;
  /** Regex to extract the word's actual language from Wiktionary wikitext (capture group 1) */
  wiktLangRe?: RegExp;
  /** true = capture group is a language name (needs mapping), false = ISO code */
  wiktLangIsName?: boolean;
  /** Regex to extract IPA from Wiktionary wikitext (capture group 1) */
  wiktIpaRe?: RegExp;
}

export const Languages: Record<string, LanguageConfig> = {
  // ── Languages with full Wiktionary parsing support ──────────────────
  en: {
    name: 'English',
    defaultAccent: 'en',
    accents: { 'en': 'English (Default)', 'en-us': 'English (US)', 'en-gb-scotland': 'English (Scotland)' },
    wiktAnchor: 'Pronunciation',
    wiktLangRe: /^==\s*(.+?)\s*==/m,
    wiktLangIsName: true,
    wiktIpaRe: /\{\{IPA\|[a-z-]+\|\/([^/]+)\//,
  },
  de: {
    name: 'German',
    defaultAccent: 'de',
    accents: { 'de': 'German' },
    wiktAnchor: 'Aussprache',
    wiktLangRe: /\{\{Sprache\|(.+?)\}\}/,
    wiktLangIsName: true,
    wiktIpaRe: /\{\{Lautschrift\|([^|}]+)/,
  },
  es: {
    name: 'Spanish',
    defaultAccent: 'es',
    accents: { 'es': 'Spanish (Castilian)', 'es-419': 'Spanish (Latin America)' },
    wiktAnchor: 'Pronunciación',
    wiktLangRe: /\{\{lengua\|(.+?)\}\}/,
    wiktLangIsName: false,
    wiktIpaRe: /\{\{pron-graf\|[^}]*?fono?=([^|}]+)/,
  },
  fr: {
    name: 'French',
    defaultAccent: 'fr',
    accents: { 'fr': 'French' },
    wiktAnchor: 'Prononciation',
    wiktLangRe: /\{\{langue\|(.+?)\}\}/,
    wiktLangIsName: false,
    wiktIpaRe: /\{\{pron\|([^|}]+)/,
  },

  // ── Languages with dictionary + espeak support ──────────────────────
  af: { name: 'Afrikaans', defaultAccent: 'af', accents: { 'af': 'Afrikaans' } },
  ar: { name: 'Arabic', defaultAccent: 'ar', accents: { 'ar': 'Arabic' } },
  bg: { name: 'Bulgarian', defaultAccent: 'bg', accents: { 'bg': 'Bulgarian' } },
  bn: { name: 'Bengali', defaultAccent: 'bn', accents: { 'bn': 'Bengali' } },
  bs: { name: 'Bosnian', defaultAccent: 'bs', accents: { 'bs': 'Bosnian' } },
  ca: { name: 'Catalan', defaultAccent: 'ca', accents: { 'ca': 'Catalan' } },
  cs: { name: 'Czech', defaultAccent: 'cs', accents: { 'cs': 'Czech' } },
  cy: { name: 'Welsh', defaultAccent: 'cy', accents: { 'cy': 'Welsh' } },
  da: { name: 'Danish', defaultAccent: 'da', accents: { 'da': 'Danish' } },
  el: { name: 'Greek', defaultAccent: 'el', accents: { 'el': 'Greek' } },
  eo: { name: 'Esperanto', defaultAccent: 'eo', accents: { 'eo': 'Esperanto' } },
  et: { name: 'Estonian', defaultAccent: 'et', accents: { 'et': 'Estonian' } },
  eu: { name: 'Basque', defaultAccent: 'eu', accents: { 'eu': 'Basque' } },
  fa: { name: 'Persian', defaultAccent: 'fa', accents: { 'fa': 'Persian' } },
  fi: { name: 'Finnish', defaultAccent: 'fi', accents: { 'fi': 'Finnish' } },
  ga: { name: 'Irish', defaultAccent: 'ga', accents: { 'ga': 'Irish' } },
  hi: { name: 'Hindi', defaultAccent: 'hi', accents: { 'hi': 'Hindi' } },
  hr: { name: 'Croatian', defaultAccent: 'hr', accents: { 'hr': 'Croatian' } },
  hu: { name: 'Hungarian', defaultAccent: 'hu', accents: { 'hu': 'Hungarian' } },
  hy: { name: 'Armenian', defaultAccent: 'hy', accents: { 'hy': 'Armenian' } },
  id: { name: 'Indonesian', defaultAccent: 'id', accents: { 'id': 'Indonesian' } },
  is: { name: 'Icelandic', defaultAccent: 'is', accents: { 'is': 'Icelandic' } },
  it: { name: 'Italian', defaultAccent: 'it', accents: { 'it': 'Italian' } },
  ja: { name: 'Japanese', defaultAccent: 'ja', accents: { 'ja': 'Japanese' } },
  ka: { name: 'Georgian', defaultAccent: 'ka', accents: { 'ka': 'Georgian' } },
  kk: { name: 'Kazakh', defaultAccent: 'kk', accents: { 'kk': 'Kazakh' } },
  ko: { name: 'Korean', defaultAccent: 'ko', accents: { 'ko': 'Korean' } },
  ku: { name: 'Kurdish', defaultAccent: 'ku', accents: { 'ku': 'Kurdish' } },
  la: { name: 'Latin', defaultAccent: 'la', accents: { 'la': 'Latin' } },
  lt: { name: 'Lithuanian', defaultAccent: 'lt', accents: { 'lt': 'Lithuanian' } },
  lv: { name: 'Latvian', defaultAccent: 'lv', accents: { 'lv': 'Latvian' } },
  mk: { name: 'Macedonian', defaultAccent: 'mk', accents: { 'mk': 'Macedonian' } },
  ml: { name: 'Malayalam', defaultAccent: 'ml', accents: { 'ml': 'Malayalam' } },
  ms: { name: 'Malay', defaultAccent: 'ms', accents: { 'ms': 'Malay' } },
  my: { name: 'Burmese', defaultAccent: 'my', accents: { 'my': 'Burmese' } },
  nl: { name: 'Dutch', defaultAccent: 'nl', accents: { 'nl': 'Dutch' } },
  no: { name: 'Norwegian', defaultAccent: 'nb', accents: { 'nb': 'Norwegian Bokmål' } },
  pl: { name: 'Polish', defaultAccent: 'pl', accents: { 'pl': 'Polish' } },
  pt: { name: 'Portuguese', defaultAccent: 'pt', accents: { 'pt': 'Portuguese (European)', 'pt-br': 'Portuguese (Brazil)' } },
  ro: { name: 'Romanian', defaultAccent: 'ro', accents: { 'ro': 'Romanian' } },
  ru: { name: 'Russian', defaultAccent: 'ru', accents: { 'ru': 'Russian' } },
  sk: { name: 'Slovak', defaultAccent: 'sk', accents: { 'sk': 'Slovak' } },
  sl: { name: 'Slovenian', defaultAccent: 'sl', accents: { 'sl': 'Slovenian' } },
  sq: { name: 'Albanian', defaultAccent: 'sq', accents: { 'sq': 'Albanian' } },
  sr: { name: 'Serbian', defaultAccent: 'sr', accents: { 'sr': 'Serbian' } },
  sv: { name: 'Swedish', defaultAccent: 'sv', accents: { 'sv': 'Swedish' } },
  sw: { name: 'Swahili', defaultAccent: 'sw', accents: { 'sw': 'Swahili' } },
  ta: { name: 'Tamil', defaultAccent: 'ta', accents: { 'ta': 'Tamil' } },
  te: { name: 'Telugu', defaultAccent: 'te', accents: { 'te': 'Telugu' } },
  th: { name: 'Thai', defaultAccent: 'th', accents: { 'th': 'Thai' } },
  tr: { name: 'Turkish', defaultAccent: 'tr', accents: { 'tr': 'Turkish' } },
  uk: { name: 'Ukrainian', defaultAccent: 'uk', accents: { 'uk': 'Ukrainian' } },
  ur: { name: 'Urdu', defaultAccent: 'ur', accents: { 'ur': 'Urdu' } },
  uz: { name: 'Uzbek', defaultAccent: 'uz', accents: { 'uz': 'Uzbek' } },
  vi: { name: 'Vietnamese', defaultAccent: 'vi', accents: { 'vi': 'Vietnamese' } },
  zh: { name: 'Chinese', defaultAccent: 'zh', accents: { 'zh': 'Chinese (Mandarin)', 'zh-yue': 'Chinese (Cantonese)' } },
};

export type Language = string;

// ─── Derived constants (auto-generated, no manual sync needed) ───────

export const LanguageNames: Record<string, string> = Object.fromEntries(
  Object.entries(Languages).map(([k, v]) => [k, v.name])
);

export const DefaultAccents: Record<string, string> = Object.fromEntries(
  Object.entries(Languages).map(([k, v]) => [k, v.defaultAccent])
);

/**
 * The accents offered per language: every accent the dictionary has real data
 * for, plus the language's standard as the first choice. A language with no
 * accent data keeps whatever its own config declares (Spanish, whose accents are
 * a rule applied to every word rather than a list of tagged ones).
 */
export const AccentsByLanguage: Record<string, Record<string, string>> = Object.fromEntries(
  Object.entries(Languages).map(([lang, cfg]) => {
    const dataAccents = ACCENTS[lang];
    if (!dataAccents) return [lang, cfg.accents];

    const options: Record<string, string> = { [lang]: 'Standard' };
    for (const accent of dataAccents) {
      if (accent.id === lang) continue;   // the standard is already the first entry
      options[accent.id] = accent.label;
    }
    return [lang, options];
  }),
);

export const WiktionaryAnchors: Record<string, string> = Object.fromEntries(
  Object.entries(Languages)
    .filter(([, v]) => v.wiktAnchor)
    .map(([k, v]) => [k, v.wiktAnchor!])
);

/** All supported language codes */
export const SupportedLanguages = Object.keys(Languages);

/** Languages with real-time Wiktionary parsing support (for tooltip lookup) */
export const WiktionaryLanguages = Object.keys(Languages).filter(
  k => Languages[k].wiktAnchor && Languages[k].wiktLangRe && Languages[k].wiktIpaRe
);

export const LanguageOptions: Record<string, string> = {
  auto: 'Auto-detect',
  ...LanguageNames,
};

export type LanguageOption = 'auto' | string;

// ─── Language name → ISO code mapping ────────────────────────────────
// Used to resolve language names from English/German Wiktionary headings.
// French and Spanish Wiktionary use ISO codes directly.

export const LANG_NAME_TO_CODE: Record<string, string> = {
  // English names
  'Afrikaans': 'af', 'Albanian': 'sq', 'Arabic': 'ar', 'Armenian': 'hy',
  'Basque': 'eu', 'Bengali': 'bn', 'Bosnian': 'bs', 'Bulgarian': 'bg',
  'Catalan': 'ca', 'Chinese': 'zh', 'Croatian': 'hr', 'Czech': 'cs',
  'Danish': 'da', 'Dutch': 'nl', 'English': 'en', 'Esperanto': 'eo', 'Estonian': 'et',
  'Finnish': 'fi', 'French': 'fr', 'Galician': 'gl', 'Georgian': 'ka',
  'German': 'de', 'Greek': 'el', 'Hebrew': 'he', 'Hindi': 'hi', 'Hungarian': 'hu',
  'Icelandic': 'is', 'Indonesian': 'id', 'Irish': 'ga', 'Italian': 'it',
  'Japanese': 'ja', 'Kazakh': 'kk', 'Korean': 'ko', 'Kurdish': 'ku',
  'Latin': 'la', 'Latvian': 'lv', 'Lithuanian': 'lt',
  'Macedonian': 'mk', 'Malay': 'ms', 'Malayalam': 'ml',
  'Mongolian': 'mn', 'Norwegian': 'no',
  'Persian': 'fa', 'Polish': 'pl', 'Portuguese': 'pt', 'Romanian': 'ro',
  'Russian': 'ru', 'Serbian': 'sr', 'Slovak': 'sk', 'Slovenian': 'sl',
  'Spanish': 'es', 'Swahili': 'sw', 'Swedish': 'sv',
  'Tamil': 'ta', 'Telugu': 'te', 'Thai': 'th', 'Turkish': 'tr',
  'Ukrainian': 'uk', 'Urdu': 'ur', 'Uzbek': 'uz', 'Vietnamese': 'vi', 'Welsh': 'cy',
  // German names
  'Albanisch': 'sq', 'Arabisch': 'ar', 'Armenisch': 'hy',
  'Baskisch': 'eu', 'Bengalisch': 'bn', 'Bosnisch': 'bs', 'Bulgarisch': 'bg',
  'Chinesisch': 'zh', 'Dänisch': 'da', 'Deutsch': 'de',
  'Englisch': 'en', 'Estnisch': 'et',
  'Finnisch': 'fi', 'Französisch': 'fr', 'Galicisch': 'gl', 'Georgisch': 'ka',
  'Griechisch': 'el', 'Hebräisch': 'he',
  'Indonesisch': 'id', 'Irisch': 'ga', 'Isländisch': 'is', 'Italienisch': 'it',
  'Japanisch': 'ja', 'Kasachisch': 'kk', 'Katalanisch': 'ca', 'Koreanisch': 'ko',
  'Kroatisch': 'hr', 'Kurdisch': 'ku',
  'Latein': 'la', 'Lateinisch': 'la', 'Lettisch': 'lv', 'Litauisch': 'lt',
  'Malaiisch': 'ms',
  'Mazedonisch': 'mk', 'Mongolisch': 'mn',
  'Niederländisch': 'nl', 'Norwegisch': 'no',
  'Persisch': 'fa', 'Polnisch': 'pl', 'Portugiesisch': 'pt', 'Rumänisch': 'ro',
  'Russisch': 'ru', 'Schwedisch': 'sv', 'Serbisch': 'sr', 'Slowakisch': 'sk',
  'Slowenisch': 'sl', 'Spanisch': 'es', 'Suaheli': 'sw',
  'Tadschikisch': 'tg', 'Tamilisch': 'ta', 'Thailändisch': 'th', 'Tschechisch': 'cs',
  'Türkisch': 'tr', 'Ukrainisch': 'uk', 'Ungarisch': 'hu',
  'Usbekisch': 'uz', 'Vietnamesisch': 'vi', 'Walisisch': 'cy',
};

// ─── Non-language types ──────────────────────────────────────────────

export const Modes = {
  showOriginalOnHover: 'Show Original on Hover',
  onHover: 'Translate on Hover',
  sprinkle: 'Sprinkle',
} as const;

export type Mode = keyof typeof Modes;

/** Short labels, for controls that show every mode side by side. Named for what a
 *  hover brings up: the page shows IPA and a hover brings the text back, or the page
 *  shows the text and a hover brings the IPA up. Sprinkle transcribes only a small,
 *  high-confidence fraction of the words, the rest staying as ordinary text. */
export const ModeLabels: Record<Mode, string> = {
  showOriginalOnHover: 'Text on hover',
  onHover: 'IPA on hover',
  sprinkle: 'Sprinkle',
};

export interface ResolvedIpa {
  ipa: string;
  /** Language the IPA was resolved from. May differ from the block language for
   *  loanwords/proper nouns found via cross-dictionary fallback (e.g. "Renault"
   *  in a German page resolved from the English/French dict). */
  lang: string;
  /** Which tier produced it — drives the tooltip source label + audio voice. */
  src: 'dict' | 'espeak';
}

/** word → resolved pronunciation. Same object drives the page span, the tooltip
 *  headline, and the audio voice, so all three always agree. */
export type PhonemeResult = Record<string, ResolvedIpa>;

/** Block-level HTML tags used to group text nodes for per-block language detection */
export const BLOCK_TAGS = new Set([
  'P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
  'LI', 'TD', 'TH', 'BLOCKQUOTE', 'SECTION', 'ARTICLE',
  'ASIDE', 'HEADER', 'FOOTER', 'MAIN', 'FIGCAPTION', 'DD', 'DT',
]);
