// The tooltip's Wiktionary enrichment (the link, the recording, the exact IPA) all
// hinge on pulling the right language section and IPA template out of a page's wikitext,
// per edition. A regex regression here fails invisibly — the tooltip just shows no
// audio — so the parse is pinned against saved wikitext fixtures. The edition configs
// below mirror src/lib/types.ts (en/de).
import { parseWikitext } from '../src/lib/wiktionary-parse.ts';

const EN_CFG = {
  wiktLangRe: /^==\s*(.+?)\s*==/m,
  wiktLangIsName: true,
  wiktIpaRe: /\{\{IPA\|[a-z-]+\|\/([^/]+)\//,
};
const DE_CFG = {
  wiktLangRe: /\{\{Sprache\|(.+?)\}\}/,
  wiktLangIsName: true,
  wiktIpaRe: /\{\{Lautschrift\|([^|}]+)/,
};
const NAME_TO_CODE: Record<string, string> = { English: 'en', French: 'fr', Deutsch: 'de' };

let pass = 0, fail = 0;
function check(name: string, cond: boolean, detail = '') {
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${cond ? '' : '  ' + detail}`);
  cond ? pass++ : fail++;
}

const EN = `==English==
===Pronunciation===
* {{IPA|en|/kæt/}}

==French==
===Pronunciation===
* {{IPA|fr|/ka/}}
`;

let r = parseWikitext(EN, EN_CFG, NAME_TO_CODE, 'en');
check('en: prefers the English section', r.wordLang === 'en' && r.wiktIpa === 'kæt', JSON.stringify(r));

r = parseWikitext(EN, EN_CFG, NAME_TO_CODE, 'fr');
check('en: preferred lang picks the French section', r.wordLang === 'fr' && r.wiktIpa === 'ka', JSON.stringify(r));

r = parseWikitext(EN, EN_CFG, NAME_TO_CODE);
check('en: no preference falls to the first section', r.wordLang === 'en' && r.wiktIpa === 'kæt', JSON.stringify(r));

// A "===" subsection header must not be mistaken for a "==" language section.
check('en: sub-headers are not language sections', parseWikitext(EN, EN_CFG, NAME_TO_CODE, 'en').wordLang === 'en');

const DE = `== Katze ({{Sprache|Deutsch}}) ==
{{Aussprache}}
:{{IPA}} {{Lautschrift|ˈkat͡sə}}
`;
r = parseWikitext(DE, DE_CFG, NAME_TO_CODE, 'de');
check('de: Sprache + Lautschrift', r.wordLang === 'de' && r.wiktIpa === 'ˈkat͡sə', JSON.stringify(r));

check('empty wikitext → nulls', parseWikitext('', EN_CFG, NAME_TO_CODE).wordLang === null);
check('missing edition config → nulls', parseWikitext(EN, undefined, NAME_TO_CODE, 'en').wordLang === null);
check('no matching template → null IPA',
  parseWikitext('==English==\nno pronunciation here', EN_CFG, NAME_TO_CODE, 'en').wiktIpa === null);

const MULTI = `==English==
* {{IPA|en|/ˈdeɪtə/}}
* {{IPA|en|/ˈdætə/}}
`;
r = parseWikitext(MULTI, EN_CFG, NAME_TO_CODE, 'en');
check('collects all IPAs, first is primary', r.wiktIpa === 'ˈdeɪtə' && r.allIpas.length === 2, JSON.stringify(r));

console.log(`\n${pass}/${pass + fail} passed`);
if (fail) process.exit(1);
