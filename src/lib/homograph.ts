// ─── Homograph disambiguation ────────────────────────────────────────
// Ported from PolyPhoneme's HomographClassifier. Given a homograph and its
// surrounding tokens, pick the pronunciation that fits the context.
//
// Two classifier JSON shapes are supported:
//  - "classes"  (en, fr, de, it, pt, nl, ru, zh): class → { pronunciation, keywords, example_count }
//  - "readings" (ja): reading → { frequency, keywords }, plus a top-level "default"

export interface HomographClass {
  pronunciation: string;
  keywords: string[];
  frequency: number;
  classId: string;
}

export interface DecisionRule {
  score: number;
  feature: string;
  classId: string;
}

export interface HomographEntry {
  classes: HomographClass[];
  defaultPronunciation: string;
  decisionRules: DecisionRule[];
}

type RawEntry = {
  classes?: Record<string, { pronunciation?: string; keywords?: string[]; example_count?: number }>;
  readings?: Record<string, { frequency?: number; keywords?: string[] }>;
  default?: string;
  decision_rules?: { score?: number; feature?: string; class?: string }[];
};

/** Parse a raw classifier JSON object into a word → entry map. */
export function parseClassifier(json: Record<string, RawEntry>): Map<string, HomographEntry> {
  const entries = new Map<string, HomographEntry>();

  for (const [word, obj] of Object.entries(json)) {
    const classes: HomographClass[] = [];
    let defaultPron = '';

    if (obj.readings) {
      for (const [reading, r] of Object.entries(obj.readings)) {
        classes.push({
          pronunciation: reading,
          keywords: r.keywords ?? [],
          frequency: r.frequency ?? 0,
          classId: reading,
        });
      }
      classes.sort((a, b) => b.frequency - a.frequency);
      defaultPron = obj.default ?? classes[0]?.pronunciation ?? '';
    } else if (obj.classes) {
      for (const [classId, c] of Object.entries(obj.classes)) {
        if (!c.pronunciation) continue;
        classes.push({
          pronunciation: c.pronunciation,
          keywords: c.keywords ?? [],
          frequency: c.example_count ?? 1,
          classId,
        });
      }
      classes.sort((a, b) => b.frequency - a.frequency);
      defaultPron = classes[0]?.pronunciation ?? '';
    } else {
      continue;
    }

    const decisionRules: DecisionRule[] = (obj.decision_rules ?? []).map((r) => ({
      score: r.score ?? 0,
      feature: r.feature ?? '',
      classId: r.class ?? '',
    }));

    entries.set(word, { classes, defaultPronunciation: defaultPron, decisionRules });
  }

  return entries;
}

/**
 * Resolve a homograph to its context-appropriate IPA.
 * `tokens` are the lowercased words of the surrounding context in order;
 * `wordIndex` is the position of the target word within `tokens`.
 * Returns the chosen pronunciation (falls back to the default class).
 */
export function disambiguate(entry: HomographEntry, tokens: string[], wordIndex: number): string {
  const { classes } = entry;
  if (classes.length <= 1) return entry.defaultPronunciation;

  const word = tokens[wordIndex] ?? '';
  const prevWord = wordIndex > 0 ? tokens[wordIndex - 1].toLowerCase() : '';
  const nextWord = wordIndex + 1 < tokens.length ? tokens[wordIndex + 1].toLowerCase() : '';

  // CJK compound lookup: for a 1-2 char CJK target, prefer a class whose short
  // keyword touches an adjacent character. Only when exactly one class matches.
  if (word.length <= 2 && [...word].some((ch) => ch.codePointAt(0)! > 0x2e80)) {
    const adjacent = [prevWord, nextWord].filter(Boolean);
    const matches = classes.filter((cls) =>
      cls.keywords.some((kw) => kw.length <= 2 && adjacent.some((adj) => adj.includes(kw) || kw.includes(adj)))
    );
    if (matches.length === 1) return matches[0].pronunciation;
  }

  // German/Dutch separable verbs: only fire with positive evidence of separation.
  const hasSepInsep = classes.some((c) => c.classId.toLowerCase() === 'separable' || c.classId.toLowerCase() === 'inseparable');
  if (hasSepInsep) {
    const prefix = ['über', 'unter', 'wieder', 'durch', 'um', 'vor'].find((p) => word.startsWith(p));
    if (prefix) {
      const separated = tokens.some((t) => t.toLowerCase() === prefix && t.toLowerCase() !== word);
      const hasZuInfix = word.includes(`${prefix}zu`);
      if (separated || hasZuInfix) {
        const sepClass = classes.find((c) => c.classId.toLowerCase() === 'separable');
        if (sepClass) return sepClass.pronunciation;
      }
    }
  }

  // Yarowsky decision rules: scan by descending score, first match wins.
  if (entry.decisionRules.length > 0) {
    const window = new Set<string>();
    for (let i = wordIndex - 5; i <= wordIndex + 5; i++) {
      if (i >= 0 && i < tokens.length && i !== wordIndex) window.add(tokens[i].toLowerCase());
    }
    for (const rule of entry.decisionRules) {
      let matched = false;
      if (rule.feature.startsWith('-1:')) matched = prevWord === rule.feature.slice(3);
      else if (rule.feature.startsWith('+1:')) matched = nextWord === rule.feature.slice(3);
      else if (rule.feature.startsWith('bi:')) {
        const parts = rule.feature.slice(3).split('_');
        matched = parts.length >= 2 && prevWord === parts[0] && nextWord === parts.slice(1).join('_');
      } else if (rule.feature.startsWith('w:')) matched = window.has(rule.feature.slice(2));
      if (matched) {
        const cls = classes.find((c) => c.classId === rule.classId);
        if (cls) return cls.pronunciation;
      }
    }
  }

  // POS fallback: infer noun vs verb from the preceding word, use only if unambiguous.
  const hasPosTags = classes.some((c) => {
    const id = c.classId.toLowerCase();
    return id.includes('nou') || id.includes('vrb') || id.includes('verb') || id.includes('adj') || id.includes('noun') || id.includes('adverb');
  });
  if (hasPosTags) {
    const pos = detectPos(prevWord);
    if (pos) {
      const posMatches = classes.filter((c) => classMatchesPos(c.classId, pos));
      if (posMatches.length === 1) return posMatches[0].pronunciation;
    }
  }

  // Keyword scoring with proximity + exclusivity weighting.
  const distances = new Map<string, number>();
  for (let i = 0; i < tokens.length; i++) {
    if (i === wordIndex) continue;
    const w = tokens[i].toLowerCase();
    const dist = Math.abs(i - wordIndex);
    const cur = distances.get(w);
    if (cur === undefined || dist < cur) distances.set(w, dist);
  }
  const contextString = tokens.filter((_, i) => i !== wordIndex).map((t) => t.toLowerCase()).join('');

  const keywordToClasses = new Map<string, Set<number>>();
  classes.forEach((cls, idx) => {
    for (const kw of cls.keywords) {
      const k = kw.toLowerCase();
      if (!keywordToClasses.has(k)) keywordToClasses.set(k, new Set());
      keywordToClasses.get(k)!.add(idx);
    }
  });

  const maxFreq = Math.max(1, ...classes.map((c) => c.frequency));
  let best = classes[0];
  let bestScore = -1;

  for (const cls of classes) {
    let keywordScore = 0;
    for (const kw of cls.keywords) {
      const k = kw.toLowerCase();
      const dist = distances.get(k);
      const matched = dist !== undefined || contextString.includes(k);
      if (!matched) continue;

      const isExclusive = keywordToClasses.get(k)?.size === 1;
      const actualDist = dist ?? tokens.length;
      const proximity = actualDist <= 1 ? 3 : actualDist <= 3 ? 2 : actualDist <= 6 ? 1.5 : 1;
      const exclusivity = isExclusive ? 5 : 0.5;
      keywordScore += exclusivity * proximity;
    }
    const score = keywordScore + cls.frequency / maxFreq;
    if (score > bestScore) {
      bestScore = score;
      best = cls;
    }
  }

  return best.pronunciation;
}

const NOUN_SIGNALS = new Set([
  'the', 'a', 'an', 'this', 'that', 'these', 'those',
  'my', 'your', 'his', 'her', 'its', 'our', 'their',
  'some', 'any', 'no', 'each', 'every', 'much', 'many',
  'fresh', 'new', 'old', 'good', 'bad', 'great', 'big',
  'world', 'war', 'front',
]);
const VERB_SIGNALS = new Set([
  'to', 'will', 'would', 'shall', 'should', 'can', 'could',
  'may', 'might', 'must', 'do', 'does', 'did',
  'please', 'let', 'cannot', "don't", "doesn't", "didn't",
  'not', 'also', 'always', 'never', 'often',
]);

function detectPos(precedingWord: string): 'noun' | 'verb' | null {
  if (NOUN_SIGNALS.has(precedingWord)) return 'noun';
  if (VERB_SIGNALS.has(precedingWord)) return 'verb';
  return null;
}

function classMatchesPos(classId: string, pos: 'noun' | 'verb'): boolean {
  const id = classId.toLowerCase();
  if (pos === 'noun') return id.includes('nou') || id.includes('adj') || id.includes('noun');
  return id.includes('vrb') || id.includes('verb');
}
