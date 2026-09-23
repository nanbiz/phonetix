// The host: one core, the packs it has open, and the answers it gives.
//
// One instance and one place, because a pack is tens of megabytes and a copy per tab would
// be a copy per tab. Nothing here decides what a word means; that is the core's, compiled
// once and run on both platforms.
import {
  annotate, complete, curve, detect, lookUp, openHomographs, openLanguages, phrase, readRuns,
  readScreen, readWiktionary, symbolsOf, type Said,
} from '@/core';
import type { Batch, TextRun } from '@/core/tokens';
import { afresh, answered, noted, recent } from './health';
import { onMessage } from './messages';
import { voiceOf } from '@/data/accents';
import { commonsAt, wiktionarySource } from '@/data/links';
import { forget, get, held, offered, open } from './packs';
import { audio, guessed, ipa, translatable } from './voice';

/** Start answering. Called once, by the background entry point. */
export function host(): void {
  // A new place to fetch dictionaries from is a new situation: what failed against the old
  // host was about the old host, and a reader who has just corrected the address should not
  // be met by the complaint they were correcting. Whatever is still wrong records itself
  // again the next time it is asked for something.
  browser.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && 'packBaseUrl' in changes) afresh();
  });

  onMessage('openPack', async ({ data }) => {
    try {
      return await get(data.lang);
    } catch (e) {
      console.warn(`[Phonetix] No pack for ${data.lang}:`, e);
      return null;
    }
  });

  onMessage('languages', async () => openLanguages());

  onMessage('annotate', async ({ data }) => {
    // Both packs, because a translation is a join between them: with only the source open
    // every word would come back with its English gloss, which is the anchor rather than the
    // answer. A page in a language with no pack is still annotated, since the states say what
    // each word reached and a host with no tokens could not tell that from a blank page.
    await Promise.all([
      open(data.source).catch(() => null),
      data.target === data.source ? null : open(data.target).catch(() => null),
      data.options.accent ? open(data.options.accent).catch(() => null) : null,
      // The classifier for what is being read, where the language has one. It decides which
      // word a spelling is, above the rule about the word before it.
      openHomographs(data.source).catch(() => 0),
    ]);
    const batch = await annotate(data.runs, data.source, data.target, data.options);
    const languages = {
      source: data.source,
      target: data.target,
      accent: data.options.accent ?? '',
    };
    // Three passes, in the order the reader is owed them: which word a spelling is, how it is
    // said, then what it means. Each stamps its own name on what it filled, so a card can say
    // which of them answered and neither is mistaken for the dictionary.
    const decided = await settled(batch, data.runs, languages);
    const spoken = await said(decided, data.source, languages);
    return meant(spoken, data.source, data.target, languages);
  });

  onMessage('curve', async () => curve());

  // What has stopped answering. Recorded as it happens rather than probed here: asking each
  // engine whether it is alive means waking it and running a word through it, which is how a
  // health check becomes the thing that breaks the health it reports on.
  onMessage('health', () => Promise.resolve({ trouble: recent() }));

  onMessage('packs', async () => ({
    held: await held(),
    open: await openLanguages(),
    // What is on offer is asked for rather than remembered: a reader who changed where their
    // dictionaries come from means it from that moment.
    offered: await offered().then(
      (list) => {
        answered('the dictionary host');
        return list;
      },
      (e) => {
        console.warn('[Phonetix] No list of packs:', e);
        noted('the dictionary host', 'cannot be reached');
        return [];
      }
    ),
  }));

  onMessage('getPack', async ({ data }) => {
    try {
      return await get(data.lang);
    } catch (e) {
      console.warn(`[Phonetix] Could not fetch the ${data.lang} pack:`, e);
      return null;
    }
  });

  onMessage('forgetPack', async ({ data }) => {
    await forget(data.lang);
    return true;
  });

  onMessage('diagram', async ({ data }) => {
    try {
      return await drawing(data.file, data.width);
    } catch (e) {
      console.warn(`[Phonetix] No diagram for ${data.file}:`, e);
      // Nothing rather than a broken picture: the sheet shows no diagram slot at all when
      // there is none, which reads better than a frame with a failure in it.
      return '';
    }
  });

  onMessage('symbols', async ({ data }) => symbolsOf(data.ipa));

  onMessage('detect', async ({ data }) => detect(data.text));

  onMessage('readScreen', async ({ data }) => readScreen(data.text));

  onMessage('readRuns', async ({ data }) => readRuns(data.texts));

  onMessage('enrich', async ({ data }) => {
    try {
      return await fromWiktionary(data.word, data.lang);
    } catch (e) {
      console.warn(`[Phonetix] Wiktionary said nothing about ${data.word}:`, e);
      return null;
    }
  });

  onMessage('fetch', async ({ data }) => {
    try {
      const res = await fetch(data.url);
      if (!res.ok) throw new Error(String(res.status));
      return Array.from(new Uint8Array(await res.arrayBuffer()));
    } catch (e) {
      console.warn(`[Phonetix] Nothing fetched from ${data.url}:`, e);
      return [];
    }
  });

  onMessage('speak', async ({ data }) => {
    try {
      const bytes = await audio(voiceOf(data.lang, data.accent ?? ''), data.word);
      answered('the synthesiser');
      return bytes;
    } catch (e) {
      console.warn(`[Phonetix] Nothing said ${data.word}:`, e);
      noted('the synthesiser', 'could not say a word');
      return [];
    }
  });

  onMessage('phrase', async ({ data }) => {
    // Nothing but an engine can answer several words at once, so this does not ask the packs.
    // What comes back is a guess and the core is what says so.
    const [said] = await guessed(data.source, data.target, [data.text]).catch(() => []);
    return phrase(data.text, said ?? '', data.source, data.target);
  });

  onMessage('say', async ({ data }) => {
    // Whether the pair can be translated at all, so a reader whose host publishes no model
    // for the direction is told that rather than that their word does not exist.
    const missing = !(await translatable().catch(() => []))
      .some((pair) => pair.from === data.target && pair.to === data.source);
    if (missing) return { answer: null, missing };
    // The reading direction, reversed: what is typed is in the language the reader already
    // has, and the word wanted is in the one they are learning.
    const [word] = await guessed(data.target, data.source, [data.text]).catch(() => []);
    const wanted = (word ?? '').trim();
    if (!wanted || wanted.toLowerCase() === data.text.trim().toLowerCase()) {
      return { answer: null, missing: false };
    }
    // And then the word's own entry, so what a machine handed over can be judged: how it is
    // said, what it means back in the reader's language, which sounds are in it.
    await Promise.all([
      open(data.source),
      data.target === data.source ? null : open(data.target),
      openHomographs(data.source).catch(() => 0),
    ]);
    // One word is looked up as one word; a machine that answered with a phrase is answered
    // as a phrase, because no dictionary holds one.
    const answer = await (wanted.split(/\s+/).length > 1
      ? phrase(wanted, data.text, data.source, data.target)
      : lookUp(wanted, data.source, data.target, '', ''));
    return { answer, missing: false };
  });

  onMessage('lookUp', async ({ data }) => {
    // A missing pack is an answer the cascade gives; anything else is a fault, and it
    // travels back as one rather than as a card claiming the word does not exist.
    await Promise.all([
      open(data.source),
      data.target === data.source ? null : open(data.target),
      // The accent's own words, where that accent is one that has them.
      data.accent ? open(data.accent).catch(() => null) : null,
      openHomographs(data.source).catch(() => 0),
    ]);
    return lookUp(data.word, data.source, data.target, data.accent ?? '', data.before ?? '');
  });
}


/** Which languages a batch is being read between, and in which accent. */
interface Languages {
  source: string;
  target: string;
  accent: string;
}

/**
 * The batch a pass produced, still carrying what the core said was missing.
 *
 * A pass answers one part of a miss - the transcription, or the meaning - and the core's reply
 * to it says nothing about what the next pass still has to answer. Without this a word that
 * needed both came back spoken and never translated, because the second pass looked at a batch
 * whose misses the first had already dropped.
 */
function carrying(asked: Batch, answered: Batch): Batch {
  return { ...answered, misses: asked.misses };
}

/**
 * Lines as the engine translated them, by direction and text, the most recently used last. A
 * page is annotated again whenever it changes or is drawn again, with the same lines each time.
 */
const translatedLines = new Map<string, string>();
const LINES_KEPT = 512;
/** Lines waiting for the engine, in the order they were asked about. */
const waitingLines = new Map<string, { from: string; target: string; text: string }>();
let translatingLines = false;

function lineKey(from: string, target: string, text: string): string {
  return `${from}>${target}\n${text}`;
}

function translatedLine(key: string): string | undefined {
  const line = translatedLines.get(key);
  if (line === undefined) return undefined;
  // Used again, so kept longest.
  translatedLines.delete(key);
  translatedLines.set(key, line);
  return line;
}

/**
 * Translate what is waiting, a few lines at a time, and tell the page when there is more
 * to draw with. The page redraws when told, and its lines are then answered from here.
 */
async function translateWaiting(): Promise<void> {
  if (translatingLines) return;
  translatingLines = true;
  let arrived = 0;
  try {
    while (waitingLines.size > 0) {
      const [first] = waitingLines.values();
      const some = [...waitingLines.entries()]
        .filter(([, line]) => line.from === first.from && line.target === first.target)
        .slice(0, 8);
      try {
        const answers = await guessed(first.from, first.target, some.map(([, line]) => line.text));
        answered('the translator');
        some.forEach(([key], at) => {
          const said = answers[at];
          if (said) {
            translatedLines.set(key, said);
            arrived += 1;
          }
        });
      } catch (e) {
        console.warn(`[Phonetix] Nothing translated the line out of ${first.from}:`, e);
        noted('the translator', 'is not answering');
      }
      for (const [key] of some) waitingLines.delete(key);
      while (translatedLines.size > LINES_KEPT) {
        const [oldest] = translatedLines.keys();
        translatedLines.delete(oldest);
      }
    }
  } finally {
    translatingLines = false;
  }
  if (arrived > 0) {
    await browser.storage.local.set({ linesTranslated: Date.now() }).catch(() => undefined);
  }
}

/**
 * Which word a spelling is, and which of its senses, where the line it is on can say.
 *
 * A spelling that is several words with nothing on the page deciding which, and a word that
 * is decided but whose senses are different words - "banco" is a bank and a bench - are both
 * questions the core asks back: the line, translated. The engine reads the whole line, and the
 * core compares what each reading and each sense means with what it wrote. One translation per
 * line rather than per word, because a line is what the engine reads.
 *
 * Neither holds the page up. With the published dictionaries nearly every line has a word
 * of one kind or the other, so what the dictionary ranks first is drawn now, the lines go to
 * the engine behind the page, and the page is drawn again when they are back.
 */
async function settled(batch: Batch, runs: TextRun[], languages: Languages): Promise<Batch> {
  const { source, target } = languages;
  if (!target || target === source) return batch;
  const wanted = batch.misses.filter((miss) => miss.need === 'Sentence' || miss.need === 'Sense');
  if (wanted.length === 0) return batch;
  const text = new Map<number, string>(runs.map((run) => [run.id, run.text]));
  const results: { token: number; sentence: string }[] = [];
  let asked = false;
  for (const miss of wanted) {
    const token = batch.tokens[miss.token];
    if (!token) continue;
    // By the language that line is in: a line the engine cannot translate out of is a line it
    // says nothing about.
    const from = token.lang || source;
    const line = text.get(token.run);
    if (from === target || !line) continue;
    const key = lineKey(from, target, line);
    const sentence = translatedLine(key);
    if (sentence) {
      results.push({ token: miss.token, sentence });
    } else if (!waitingLines.has(key)) {
      waitingLines.set(key, { from, target, text: line });
      asked = true;
    }
  }
  if (asked) void translateWaiting();
  if (results.length === 0) return batch;
  return carrying(batch, await complete(batch.batch, results, 'bergamot', languages));
}

/**
 * Fill in how the words no pack could say are said.
 *
 * The core asks for what it is missing rather than the host deciding to be helpful: a word a
 * pack answered keeps the pronunciation the dictionary recorded, and only the rest reach the
 * engine. What comes back goes through the core, so that it is marked as synthesised in the
 * one place that decides what a reader is told.
 */
async function said(batch: Batch, lang: string, languages: Languages): Promise<Batch> {
  // Only what asked for a sound. A word waiting on its sentence has the dictionary's sound
  // already, and a voice's guess over it would replace a transcription a person wrote.
  const wanted = batch.misses.filter((miss) => miss.need === 'Ipa' || miss.need === 'Both');
  // The voice fills a transcription; what a word means is the other engine's, below.
  if (wanted.length === 0) return batch;
  // By the language of the word rather than of the page. A page is not always in one: an
  // English line on a Spanish page read with the Spanish voice comes back saying "the" as
  // "te", which is a pronunciation of a word nobody was reading.
  const byLang = new Map<string, string[]>();
  for (const miss of wanted) {
    const token = batch.tokens[miss.token];
    if (!token) continue;
    const spoken = token.lang || lang;
    const words = byLang.get(spoken) ?? [];
    if (!words.includes(token.spelling)) words.push(token.spelling);
    byLang.set(spoken, words);
  }
  const spoken = new Map<string, Record<string, string>>();
  for (const [voice, words] of byLang) {
    try {
      spoken.set(voice, await ipa(voice, words));
      answered('the synthesiser');
    } catch (e) {
      console.warn(`[Phonetix] The ${voice} voice did not answer:`, e);
      noted('the synthesiser', 'is not answering');
    }
  }
  const results = wanted
    .map((miss) => {
      const token = batch.tokens[miss.token];
      const said = token ? spoken.get(token.lang || lang)?.[token.spelling] : undefined;
      return { token: miss.token, ipa: said };
    })
    .filter((result): result is { token: number; ipa: string } => Boolean(result.ipa));
  if (results.length === 0) return batch;
  return carrying(batch, await complete(batch.batch, results, 'espeak', languages));
}

/**
 * Fill in what the words no pack could translate mean.
 *
 * The dictionary answers first and this fills the rest: a word with no entry, a pair no pack
 * covers. What comes back is a machine's guess and is marked as one all the way to the card,
 * because a guess wearing a dictionary's authority is what the whole cascade is shaped to
 * avoid - the reader is told which of the two answered, every time.
 *
 * A reader who has chosen no language to read into is not translating, and a word already
 * answered by a pack never reaches this: the core says what it is missing and only that is
 * asked for.
 */
async function meant(
  batch: Batch,
  source: string,
  target: string,
  languages: Languages
): Promise<Batch> {
  if (!target || target === source) return batch;
  // Only what asked for a meaning. A word waiting on its sentence has the dictionary's
  // readings, and translating it alone would answer it with a guess made without the context
  // it was waiting for.
  const wanted = batch.misses.filter((miss) => miss.need === 'Gloss' || miss.need === 'Both');
  if (wanted.length === 0) return batch;
  // By the language of the word rather than of the page, for the same reason the voice is:
  // an English line on a Spanish page is translated out of English or not at all.
  const byLang = new Map<string, string[]>();
  for (const miss of wanted) {
    const token = batch.tokens[miss.token];
    if (!token) continue;
    const from = token.lang || source;
    if (from === target) continue;
    const words = byLang.get(from) ?? [];
    if (!words.includes(token.spelling)) words.push(token.spelling);
    byLang.set(from, words);
  }
  const guesses = new Map<string, Record<string, string>>();
  for (const [from, words] of byLang) {
    try {
      const answers = await guessed(from, target, words);
      guesses.set(from, Object.fromEntries(words.map((word, at) => [word, answers[at] ?? ''])));
      answered('the translator');
    } catch (e) {
      console.warn(`[Phonetix] Nothing translated ${from} to ${target}:`, e);
      noted('the translator', 'is not answering');
    }
  }
  const results = wanted
    .map((miss) => {
      const token = batch.tokens[miss.token];
      const gloss = token ? guesses.get(token.lang || source)?.[token.spelling] : undefined;
      return { token: miss.token, gloss };
    })
    .filter((result): result is { token: number; gloss: string } =>
      Boolean(result.gloss) && result.gloss !== '');
  if (results.length === 0) return batch;
  return carrying(batch, await complete(batch.batch, results, 'bergamot', languages));
}

/**
 * A sagittal section of the mouth making a sound, as a data URL.
 *
 * Commons serves these as SVG, so the file is asked for at a width and comes back as a
 * raster: a page cannot be handed an SVG it did not fetch itself, and the sheet wants one
 * size anyway. Fetched here because the page's own policy would refuse the load.
 */
async function drawing(file: string, width: number): Promise<string> {
  const res = await fetch(commonsAt(file, width));
  if (!res.ok) throw new Error(String(res.status));
  const bytes = new Uint8Array(await res.arrayBuffer());
  let binary = '';
  for (let at = 0; at < bytes.length; at += 8192) {
    binary += String.fromCharCode(...bytes.subarray(at, at + 8192));
  }
  const type = res.headers.get('content-type') ?? 'image/png';
  return `data:${type};base64,${btoa(binary)}`;
}

/** What has already been asked about, so a reader hovering a word twice asks once. */
const asked = new Map<string, Said | null>();

/**
 * What Wiktionary says about a word.
 *
 * A pack holds what the dump held, and the dump is a snapshot. The page carries two things
 * worth more than anything in it: a transcription a person wrote and a recording of a person
 * saying the word. The page is fetched here, because a page's own policy would refuse it, and
 * read by the core, because reading it is the same work on both platforms.
 */
async function fromWiktionary(word: string, lang: string): Promise<Said | null> {
  const key = `${lang}:${word.toLowerCase()}`;
  if (asked.has(key)) return asked.get(key) ?? null;
  const res = await fetch(wiktionarySource(word));
  // A word with no page is an ordinary answer, not a failure: most words have no recording.
  const said = res.ok ? await readWiktionary(await res.text(), lang) : null;
  asked.set(key, said);
  return said;
}
