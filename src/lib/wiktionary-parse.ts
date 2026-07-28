// Pulling the word's language and IPA out of a Wiktionary page's wikitext. Each
// Wiktionary edition marks a language section and an IPA template differently, so this
// is driven by that per-edition config — passed in rather than imported, so the parser
// has no dependencies and can be unit-tested against saved wikitext without a browser.

export interface WiktEditionCfg {
  /** Matches a language section; group 1 is the language (a name or a code). */
  wiktLangRe?: RegExp;
  /** Matches an IPA template; group 1 is the transcription. */
  wiktIpaRe?: RegExp;
  /** True when `wiktLangRe`'s group 1 is a language name, not a code. */
  wiktLangIsName?: boolean;
}

export interface WiktParse {
  wordLang: string | null;
  wiktIpa: string | null;
  allIpas: string[];
}

const EMPTY: WiktParse = { wordLang: null, wiktIpa: null, allIpas: [] };

export function parseWikitext(
  wikitext: string,
  cfg: WiktEditionCfg | undefined,
  nameToCode: Record<string, string>,
  preferredLang?: string
): WiktParse {
  if (!wikitext || !cfg?.wiktLangRe || !cfg?.wiktIpaRe) return { ...EMPTY };

  // Level-2 sections (each starts with "== … ==", not "=== …").
  const sections = wikitext.split(/(?=^==[^=])/m);

  const resolveSectionLang = (section: string): string | null => {
    const m = section.match(cfg.wiktLangRe!);
    if (!m?.[1]) return null;
    const raw = m[1].trim();
    return cfg.wiktLangIsName ? (nameToCode[raw] || raw.toLowerCase().slice(0, 2)) : raw;
  };

  const extractAllIpas = (section: string): string[] => {
    const re = new RegExp(cfg.wiktIpaRe!.source, 'g');
    const results: string[] = [];
    let m;
    while ((m = re.exec(section)) !== null) {
      const ipa = m[1].replace(/^[/\[]|[/\]]$/g, '').trim();
      if (ipa && !results.includes(ipa)) results.push(ipa);
    }
    return results;
  };

  // Prefer the section for the language we're actually looking for.
  if (preferredLang) {
    for (const section of sections) {
      if (resolveSectionLang(section) === preferredLang) {
        const allIpas = extractAllIpas(section);
        return { wordLang: preferredLang, wiktIpa: allIpas[0] || null, allIpas };
      }
    }
  }

  // Otherwise the first section whose language is detectable.
  for (const section of sections) {
    const lang = resolveSectionLang(section);
    if (lang) {
      const allIpas = extractAllIpas(section);
      return { wordLang: lang, wiktIpa: allIpas[0] || null, allIpas };
    }
  }

  return { ...EMPTY };
}
