// The host: one core, the packs it has open, and the answers it gives.
//
// One instance and one place, because a pack is tens of megabytes and a copy per tab would
// be a copy per tab. Nothing here decides what a word means; that is the core's, compiled
// once and run on both platforms.
import {
  annotate, complete, curve, detect, lookUp, meanings, openHomographs, openLanguages, phrase,
  readRuns, readScreen, readWiktionary, symbolsOf, typedInMine, wordFor, type Said,
  heard as heardIn,
} from '@/core';
import type { Batch, TextRun } from '@/core/tokens';
import { afresh, answered, noted, recent } from './health';
import { onMessage } from './messages';
import { voiceOf } from '@/data/accents';
import { commonsAt, wiktionarySource } from '@/data/links';
import { forget, get, held, offered, open, openReadInto, ownHostAnswered } from './packs';
import { audio, guessed, ipa, translatable } from './voice';
import { hear, stopHearing } from './speech';

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

  // In a tab of their own: a page cannot open the toolbar's popup, and a touch screen has no
  // toolbar button within reach of the hand holding the side button anyway.
  onMessage('openSettings', async () => {
    await browser.tabs.create({ url: browser.runtime.getURL('/popup.html') });
    return true;
  });

  onMessage('annotate', async ({ data }) => {
    // Both packs, because a translation is a join between them: with only the source open
    // every word would come back with its English gloss, which is the anchor rather than the
    // answer. A page in a language with no pack is still annotated, since the states say what
    // each word reached and a host with no tokens could not tell that from a blank page.
    await Promise.all([
      open(data.source).catch(() => null),
      data.target === data.source ? null : openReadInto(data.target).catch(() => null),
      data.options.accent ? open(data.options.accent).catch(() => null) : null,
      // The accent each other language on the page is read in, where it has words of its own.
      ...Object.entries(data.options.accents ?? {})
        .filter(([lang, accent]) => accent && accent !== lang)
        .map(([, accent]) => open(accent).catch(() => null)),
      // And each language a line of the page turned out to be in, so an English notice on a
      // German page is read with the English pack rather than the German one.
      ...[...new Set(data.runs.map((run) => run.lang).filter((lang): lang is string =>
        Boolean(lang) && lang !== data.source && lang !== data.target))]
        .map((lang) => open(lang).catch(() => null)),
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
    const translated = await meant(spoken, data.source, data.target, languages);
    // With both, what replaces a word is how its translation is said: a translation whose
    // entry has no transcription, or one the engine wrote, is said by the voice for the
    // language read into rather than drawn as the written word.
    return data.options.mode === 'both'
      ? translationSaid(translated, data.target, languages)
      : translated;
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
        // Answered by the published packs where the reader's own host is not answering, and
        // the reader who set that host is still told.
        if (ownHostAnswered()) answered('the dictionary host');
        else noted('the dictionary host', 'cannot be reached');
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

  onMessage('guess', async ({ data }) => {
    const [said] = await guessed(data.source, data.target, [data.word]).catch(() => []);
    const word = (said ?? '').trim();
    // The word handed back unchanged is the engine saying it has nothing.
    return word.toLowerCase() === data.word.trim().toLowerCase() ? '' : word;
  });

  onMessage('phrase', async ({ data }) => {
    // Nothing but an engine can answer several words at once, so this does not ask the packs.
    // What comes back is a guess and the core is what says so.
    const [said] = await guessed(data.source, data.target, [data.text]).catch(() => []);
    return phrase(data.text, said ?? '', data.source, data.target);
  });

  onMessage('say', async ({ data }) => {
    // The reading direction, reversed: what is typed is in the language the reader already
    // has, and the word wanted is in the one they are learning. The engine first, where it
    // has a model for the pair: it reads a phrase whole.
    const modelled = (await translatable().catch(() => []))
      .some((pair) => pair.from === data.target && pair.to === data.source);
    const [word] = modelled
      ? await guessed(data.target, data.source, [data.text]).catch(() => [])
      : [];
    const machine = (word ?? '').trim();
    // And then the word's own entry, so what came back can be judged: how it is said, what it
    // means back in the reader's language, which sounds are in it.
    await Promise.all([
      open(data.source),
      data.target === data.source ? null : openReadInto(data.target),
      openHomographs(data.source).catch(() => 0),
    ]);
    const wanted =
      machine && machine.toLowerCase() !== data.text.trim().toLowerCase()
        ? machine
        : // Where no model answers, the dictionaries still do: what was typed, found through
          // the English glosses both are written in.
          await fromTheDictionaries(data.text, data.target, data.source);
    if (!wanted) return { answer: null, missing: !modelled };
    // One word is looked up as one word; a machine that answered with a phrase is answered
    // as a phrase, because no dictionary holds one.
    const answer = await (wanted.split(/\s+/).length > 1
      ? phrase(wanted, data.text, data.source, data.target)
      : lookUp(wanted, data.source, data.target, '', ''));
    return { answer, missing: false };
  });

  /** How many words a phrase can have and still be looked for as a dictionary entry of its own:
   *  "echar de menos", "auf jeden Fall", "un día sí y otro no". */
  const PHRASE_ENTRY_WORDS = 5;

  onMessage('ask', async ({ data }) => {
    // Typed in either language and answered in the other, the way the phone's panel answers:
    // the core works out which it was typed in, and the reader's arrow overrides it.
    const text = data.text.trim();
    const { mine, learning } = data;
    if (!text || !mine || !learning || mine === learning) {
      return { forward: true, kind: 'nothing' as const };
    }
    // What is held now: a dictionary still on its way is fetched behind the answer rather
    // than in front of it, and the engine answers until it is here.
    await Promise.all([openReadInto(mine), openReadInto(learning)]);
    const forward = data.turned ?? (await typedInMine(text, mine, learning));
    const [from, to] = forward ? [mine, learning] : [learning, mine];
    // What the dictionary says, for a word and for a phrase short enough to be an entry of its
    // own: Wiktionary files "buenos días" and "echar de menos" as words, and what they mean is
    // not what their words mean one by one.
    const phrase = /\s/.test(text);
    const asWords = !phrase || text.split(/\s+/).length <= PHRASE_ENTRY_WORDS;
    const found = asWords ? await meanings(text, from, to).catch(() => []) : [];
    // How each is said where no dictionary of that language is here to say it: by the voice,
    // as the page says such a word.
    const unsaid = found.filter((m) => !m.ipa).map((m) => m.word);
    const voicedMeanings: Record<string, string> = unsaid.length
      ? await ipa(voiceOf(to, ''), unsaid).catch(() => ({}))
      : {};
    const meant = found.map((m) => ({ ...m, ipa: m.ipa ?? voicedMeanings[m.word] ?? null }));
    if (!phrase && meant.length > 0) return { forward, kind: 'meanings' as const, meanings: meant };
    const [said] = await guessed(from, to, [text]).catch(() => []);
    const line = (said ?? '').trim();
    if (!line || line.toLowerCase() === text.toLowerCase()) {
      return meant.length > 0
        ? { forward, kind: 'meanings' as const, meanings: meant }
        : { forward, kind: 'nothing' as const };
    }
    // How the answer is said, word by word, as the page says a word the dictionaries lack.
    const words = line.split(/\s+/).map((w) => w.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, ''))
      .filter(Boolean);
    const voiced: Record<string, string> = await ipa(voiceOf(to, ''), words).catch(() => ({}));
    const sounds = words.map((w) => voiced[w]).filter(Boolean).join(' ');
    // The dictionary's meanings besides the line, the line itself not again.
    const besides = meant.filter((m) => m.word.toLowerCase() !== line.toLowerCase());
    return {
      forward, kind: 'line' as const, line, ipa: sounds,
      ...(besides.length > 0 ? { meanings: besides } : {}),
    };
  });

  // What is said to the panel rather than typed into it: recorded and written down here, and
  // then asked like anything typed.
  onMessage('listen', async ({ data, tab }) => {
    // The language's dictionary, opened while the reader speaks, puts right a word the
    // recogniser missed by a letter.
    const opening = open(data.lang).catch(() => null);
    const said = await hear(data.lang, tab).catch((e) => {
      console.warn('[Phonetix] Nothing was heard:', e);
      throw e;
    });
    if (said.kind !== 'said') return said;
    await opening;
    return { kind: 'said' as const, text: await heardIn(said.text, data.lang).catch(() => said.text) };
  });
  onMessage('stopListening', async () => {
    await stopHearing();
    return true;
  });

  onMessage('lookUp', async ({ data }) => {
    // A missing pack is an answer the cascade gives; anything else is a fault, and it
    // travels back as one rather than as a card claiming the word does not exist.
    await Promise.all([
      open(data.source),
      data.target === data.source ? null : openReadInto(data.target),
      // The accent's own words, where that accent is one that has them.
      data.accent ? open(data.accent).catch(() => null) : null,
      openHomographs(data.source).catch(() => 0),
    ]);
    const answer = await lookUp(
      data.word, data.source, data.target, data.accent ?? '', data.before ?? '', data.drawn ?? ''
    );
    if (answer.ipa.length > 0 || answer.state === 'Phrase') return answer;
    // No dictionary here says how this word is said: one still on its way, a form the
    // dictionary lists only under its lemma, a language nobody built one for. The voice says
    // it, as it does for the same word on the page, and the card marks it as the voice's.
    const spoken = await ipa(voiceOf(data.source, data.accent ?? ''), [data.word])
      .then((said) => said[data.word] ?? '')
      .catch(() => '');
    if (!spoken) return answer;
    return {
      ...answer,
      state: answer.state === 'NoPack' || answer.state === 'None' ? 'IpaOnly' : answer.state,
      ipa: [spoken],
      symbols: await symbolsOf(spoken).catch(() => []),
      provenance: answer.provenance ?? { kind: 'synthesised' },
    };
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

/** The first word the dictionaries give for what was typed that has an entry of its own. */
async function fromTheDictionaries(text: string, typedIn: string, wantedIn: string): Promise<string> {
  for (const word of await wordFor(text, typedIn, wantedIn).catch(() => [])) {
    const entry = await lookUp(word, wantedIn, typedIn, '', '').catch(() => null);
    if (entry && entry.state !== 'None' && entry.state !== 'NoPack') return word;
  }
  return '';
}

/**
 * Lines as the engine translated them, by direction and text, the most recently used last. A
 * page is annotated again whenever it changes or is drawn again, with the same lines each time.
 */
const translatedLines = new Map<string, string>();
const LINES_KEPT = 512;
/** Lines waiting for the engine, in the order they were asked about. Only the latest are
 *  kept: lines of a page the reader has already left would otherwise hold up the one they
 *  are on, one translation at a time. */
const waitingLines = new Map<string, { from: string; target: string; text: string }>();
const LINES_WAITING = 64;
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
      while (waitingLines.size > LINES_WAITING) {
        const [oldest] = waitingLines.keys();
        waitingLines.delete(oldest);
      }
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

/** Words as the engine translated them, by direction and word, the most recently used last. */
const translatedWords = new Map<string, string>();
const WORDS_KEPT = 4096;
/** Words waiting for the engine. Only the latest are kept, for the page the reader is on. */
const waitingWords = new Map<string, { from: string; target: string; word: string }>();
const WORDS_WAITING = 256;
let translatingWords = false;

function translatedWord(key: string): string | undefined {
  const word = translatedWords.get(key);
  if (word === undefined) return undefined;
  translatedWords.delete(key);
  translatedWords.set(key, word);
  return word;
}

/**
 * Translate the words that are waiting, one direction at a time, and tell the page when there
 * is more to draw with, the way lines are (see [translateWaiting]).
 */
async function translateWordsWaiting(): Promise<void> {
  if (translatingWords) return;
  translatingWords = true;
  let arrived = 0;
  try {
    while (waitingWords.size > 0) {
      const [first] = waitingWords.values();
      const some = [...waitingWords.entries()]
        .filter(([, one]) => one.from === first.from && one.target === first.target)
        .slice(0, 32);
      try {
        const answers = await guessed(first.from, first.target, some.map(([, one]) => one.word));
        answered('the translator');
        some.forEach(([key], at) => {
          // Answered, even with nothing: a word the engine has no answer for is not asked again.
          translatedWords.set(key, answers[at] ?? '');
          if (answers[at]) arrived += 1;
        });
      } catch (e) {
        console.warn(`[Phonetix] Nothing translated ${first.from} to ${first.target}:`, e);
        noted('the translator', 'is not answering');
      }
      for (const [key] of some) waitingWords.delete(key);
      while (translatedWords.size > WORDS_KEPT) {
        const [oldest] = translatedWords.keys();
        translatedWords.delete(oldest);
      }
    }
  } finally {
    translatingWords = false;
  }
  if (arrived > 0) {
    await browser.storage.local.set({ linesTranslated: Date.now() }).catch(() => undefined);
  }
}

/** How each drawn translation with no transcription is said, in the language read into. */
async function translationSaid(batch: Batch, target: string, languages: Languages): Promise<Batch> {
  if (!target || target === languages.source) return batch;
  const wanted = batch.tokens
    .map((token, at) => ({ token, at }))
    .filter(({ token }) => token.inline && token.gloss && !token.glossIpa);
  if (wanted.length === 0) return batch;
  const words = [...new Set(wanted.map(({ token }) => token.gloss as string))];
  let spoken: Record<string, string> = {};
  try {
    spoken = await ipa(target, words);
    answered('the synthesiser');
  } catch (e) {
    console.warn(`[Phonetix] The ${target} voice did not answer:`, e);
    noted('the synthesiser', 'is not answering');
    return batch;
  }
  const results = wanted
    .map(({ token, at }) => ({ token: at, ipa: spoken[token.gloss as string] }))
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
 * The page does not wait for it. The first translation in a direction can mean fetching its
 * models, tens of megabytes, and a page held for that was a page with nothing on it: what the
 * dictionaries answered is drawn at once, the rest goes to the engine behind the page, and the
 * page is drawn again once the answers are back.
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
  const results: { token: number; gloss: string }[] = [];
  let asked = false;
  for (const miss of wanted) {
    const token = batch.tokens[miss.token];
    if (!token) continue;
    // By the language of the word rather than of the page, for the same reason the voice is:
    // an English line on a Spanish page is translated out of English or not at all.
    const from = token.lang || source;
    if (from === target) continue;
    const key = lineKey(from, target, token.spelling);
    const gloss = translatedWord(key);
    if (gloss !== undefined) {
      if (gloss) results.push({ token: miss.token, gloss });
    } else if (!waitingWords.has(key)) {
      waitingWords.set(key, { from, target, word: token.spelling });
      while (waitingWords.size > WORDS_WAITING) {
        const [oldest] = waitingWords.keys();
        waitingWords.delete(oldest);
      }
      asked = true;
    }
  }
  if (asked) void translateWordsWaiting();
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
