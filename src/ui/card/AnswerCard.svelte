<script lang="ts">
  // The answer surface: what a reader gets when they stop at a word.
  //
  // A card under the pointer is read in a glance, so it says four things and nothing else: the
  // word and what language it is read as, what it means, how it is said, and which form of
  // which word it is. A spelling that is several words leads with the likeliest and names the
  // others on one quiet line. Every symbol of the transcription is a button, and the sound a
  // reader asks about is described on a line that is there only once they ask.
  //
  // The markup is the surface page's own and the stylesheet is generated from it.
  import { headline as headlineOf, type Answer, type IpaSymbol } from '@/core/answer';
  import { named } from '@/data/languages';
  import { wiktionary } from '@/data/links';
  import { accentsOf } from '@/data/accents';
  import IconLink from '@/ui/controls/IconLink.svelte';
  import { WIKTIONARY } from './icons';
  import PlayButton from './PlayButton.svelte';
  import SoundLine from './SoundLine.svelte';

  interface Props {
    answer: Answer;
    /** A recording of a person saying the word, where one was found. */
    recorded?: boolean;
    /** The accent the reader is being read this language in, where they chose one. */
    accent?: string;
    /** The sound the reader asked about, described on its own line. */
    opened?: IpaSymbol | null;
    /** A picture of the mouth making that sound, where the host could fetch one. */
    diagram?: string | null;
    /** A recording of a person saying one sound, where the table has one. */
    onPlaySymbol?: (url: string) => void;
    /** Whether the card eases in, which is the reader's choice and off by default. */
    eased?: boolean;
    /** Which way the arrow points, where the card is anchored to a word at all. */
    points?: 'above' | 'below' | null;
    /** A sound the reader asked about. */
    onSymbol?: (symbol: string) => void;
    onPlay?: () => void;
    onOpen?: (url: string) => void;
    /** How far the dictionary for the word's language has got, where it is on its way. */
    arriving?: number | null;
  }

  let {
    answer,
    arriving = null,
    recorded = false,
    accent = '',
    opened = null,
    diagram = null,
    eased = false,
    points = null,
    onPlaySymbol,
    onSymbol,
    onPlay,
    onOpen,
  }: Props = $props();

  /** Which rule a symbol takes: the four kinds the table names are drawn apart. */
  function symbolClass(kind: string): string {
    if (kind === 'vowel') return 'sym v';
    if (kind === 'consonant') return 'sym c';
    if (kind === 'diacritic') return 'sym d';
    return 'sym o';
  }

  /** A word's own page, at its dictionary form where there is one, in the language it is
   *  being read as, since a spelling is an entry in several. */
  const entry = (it: Answer) => wiktionary(it.lemma ?? it.spelling, named(it.source));

  /**
   * What the dictionary marks a sense as, where that is something a reader acts on: which
   * gender a noun is, and whether a word is out of the ordinary to use. How the dump files a
   * verb's conjugation or a noun's declension ("strong", "weak", "transitive") is grammar
   * nobody reading a page asked for.
   */
  const WORTH = new Set([
    'masculine', 'feminine', 'neuter', 'common', 'colloquial', 'informal', 'formal', 'slang',
    'vulgar', 'offensive', 'derogatory', 'archaic', 'dated', 'obsolete', 'rare', 'dialectal',
    'regional', 'literary', 'poetic', 'humorous', 'figuratively', 'euphemistic',
  ]);

  // The meaning in a few words: a dictionary lists synonyms after the first, and three of them
  // in the type a headline is set in ran over two lines of a card read in a glance.
  let lead = $derived(short(headlineOf(answer)));

  /** As many of a meaning's comma-separated synonyms as fit in a headline, and always one. */
  function short(meaning: string | null): string | null {
    if (!meaning) return meaning;
    const parts = meaning.split(/,\s*/);
    let out = parts[0];
    for (const part of parts.slice(1)) {
      if (`${out}, ${part}`.length > 26) break;
      out = `${out}, ${part}`;
    }
    return out;
  }
  let phrase = $derived(answer.state === 'Phrase');
  // Several words a reader selected: no transcription of a clause is worth showing.
  let marks = $derived(
    phrase ? [] : [...new Set((answer.marks[0] ?? []).filter((mark) => WORTH.has(mark)))]
  );
  // The other words the spelling could be, where nothing decided between them: each by what it
  // means, on one line, so the card still leads with an answer rather than with a question.
  let others = $derived(
    answer.state === 'Homograph'
      ? answer.readings
          .slice(1)
          .map((reading) => reading.says[0] ?? reading.glosses[0] ?? '')
          .filter((said) => said && said !== lead)
          .slice(0, 2)
      : []
  );
  // A machine's answer is labelled one: unmarked, it reads as the dictionary's.
  let guessed = $derived(
    answer.provenance?.kind === 'guess' || answer.state === 'Guess' || phrase
  );
  let engine = $derived(answer.provenance?.kind === 'guess' ? answer.provenance.engine : '');
  // An English definition standing in for a word in the reader's language, where the join did
  // not reach one: unmarked, it reads as the translation rather than as the anchor it is.
  let anchored = $derived(
    !guessed && answer.says.length === 0 && answer.glosses.length > 0 && answer.target !== 'en'
  );
  // What is being read, and in which accent where the reader chose one: one fact, one pill.
  let readAs = $derived(
    (() => {
      const name = accentsOf(answer.source).find((row) => row.id === accent)?.name ?? '';
      const code = answer.source.toUpperCase();
      return { label: name ? `${code} · ${name}` : code, name };
    })()
  );
  // Which form of which word this is, as one line: "plural of Tier". A form the dump did not
  // name is still a form of its lemma.
  let form = $derived(
    phrase || !answer.lemma
      ? ''
      : answer.form
        ? `${answer.form} of ${answer.lemma}`
        : `form of ${answer.lemma}`
  );
  let nothing = $derived(lead === null && answer.ipa.length === 0 && !phrase);
  let missing = $derived(
    answer.state === 'NoPack'
      ? `No dictionary for ${named(answer.source)} yet`
      : answer.state === 'UnknownLang'
        ? 'Not sure what language this is'
        : answer.state === 'Loading'
          ? 'Looking it up'
          : `Nothing found for ${answer.spelling}`
  );
</script>

<article class="card{eased ? ' eased' : ''}{points ? ` points ${points}` : ''}">
  <div class="card-handle"></div>
  <header class="card-head">
    <div class="card-top">
      <span class="word">{answer.spelling}</span>
      {#if !phrase && answer.source}
        <span class="pill" title={readAs.name
          ? `Read as ${named(answer.source)}, ${readAs.name} accent`
          : `Read as ${named(answer.source)}`}>{readAs.label}</span>
      {/if}
      <span class="spacer"></span>
      {#if !phrase}
        <IconLink
          icon={WIKTIONARY}
          label="Wiktionary"
          url={entry(answer)}
          name="Wiktionary"
          open={onOpen}
        />
      {/if}
    </div>

    {#if nothing}
      <p class="note">{missing}</p>
    {:else}
      {#if lead && lead !== answer.spelling}
        <div class="headline">
          <span class="tr">{lead}</span>
          {#if answer.pos && !phrase}<span class="chip">{answer.pos}</span>{/if}
          {#each marks as mark (mark)}<span class="chip mark">{mark}</span>{/each}
          {#if guessed}
            <span class="badge guess" title={engine ? `Translated by ${engine}` : 'Translated by a machine'}
              >guess</span>
          {:else if anchored}
            <span class="badge">in English</span>
          {/if}
        </div>
      {/if}

      {#if answer.ipa.length > 0 && !phrase}
        <div class="ipa-row">
          <span class="ipa">
            <span class="delim">/</span><!--
            Symbol by symbol, because each one is a button. No space between them: a
            transcription is one word and reads as one.
         -->{#if answer.symbols.length > 0}{#each answer.symbols as symbol, i (i)}<button
                class="{symbolClass(symbol.kind)}{opened?.token === symbol.token ? ' active' : ''}"
                title={symbol.name}
                onclick={() => onSymbol?.(symbol.token)}>{symbol.token}</button>{/each}{:else}<!--
              Whole, where the table could not say what its sounds are.
           -->{answer.ipa[0]}{/if}<span
              class="delim">/</span>
          </span>
          <PlayButton
            label={recorded ? `hear ${answer.spelling}` : `say ${answer.spelling}`}
            onplay={() => onPlay?.()}
          />
        </div>
        {#if opened}
          <SoundLine about={opened} {diagram} onPlay={onPlaySymbol} {onOpen} />
        {/if}
      {/if}

      {#if phrase}
        <!-- What was selected, under what it means, so a reader sees which of it was answered. -->
        <div class="gram"><span class="g-sub">{answer.spelling}</span></div>
      {/if}

      {#if form}
        <div class="gram" data-form><span class="one-line">{form}</span></div>
      {/if}

      {#if arriving !== null}
        <!-- Until the dictionary is here the word is only said, and a card with no meaning on
             it says why and how long. -->
        <div class="gram" data-arriving>
          <span class="one-line"
            >Getting the {named(answer.source)} dictionary · {Math.round(arriving * 100)}%</span>
        </div>
      {/if}

      {#if others.length > 0}
        <div class="gram" data-others>
          <span>or</span><span class="lemma one-line">{others.join(' · ')}</span>
        </div>
      {/if}
    {/if}
  </header>
</article>
