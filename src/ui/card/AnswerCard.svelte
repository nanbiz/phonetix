<script lang="ts">
  // The answer surface: what a reader gets when they stop at a word.
  //
  // The top row names the word under the cursor and says what it is being read as and where
  // the answer came from, because everything under it is only as good as that. Then what it
  // means, then how it is said, and the pronunciation is the interactive part: every symbol is
  // a button, and the sound a reader asks about is described on a line of its own fixed
  // height, so exploring a transcription never resizes the card under the cursor.
  //
  // The markup is the surface page's own and the stylesheet is generated from it, so this
  // card and the Compose card are the same card.
  import { headline as headlineOf, type Answer, type IpaSymbol } from '@/core/answer';
  import { named } from '@/data/languages';
  import { accentsOf } from '@/data/accents';
  import IconLink from '@/ui/controls/IconLink.svelte';
  import { WIKTIONARY } from './icons';
  import PlayButton from './PlayButton.svelte';
  import SoundLine from './SoundLine.svelte';
  import SourceMark from './SourceMark.svelte';

  interface Props {
    answer: Answer;
    /** A recording of a person saying the word, where one was found. */
    recorded?: boolean;
    /** The accent the reader is being read this language in, where they chose one. */
    accent?: string;
    /** The sound whose description the detail line is showing. */
    opened?: IpaSymbol | null;
    /** A picture of the mouth making that sound, where the host could fetch one. */
    diagram?: string | null;
    /** A recording of a person saying one sound, where the table has one. */
    onPlaySymbol?: (url: string) => void;
    /** A sound the reader asked about. */
    onSymbol?: (symbol: string) => void;
    onPlay?: () => void;
    onOpen?: (url: string) => void;
  }

  let {
    answer,
    recorded = false,
    accent = '',
    opened = null,
    diagram = null,
    onPlaySymbol,
    onSymbol,
    onPlay,
    onOpen,
  }: Props = $props();

  /** Which colour a symbol's rule takes: the page rules vowels, consonants and the rest apart. */
  function symbolClass(kind: string): string {
    return kind === 'vowel' ? 'sym v' : kind === 'consonant' ? 'sym c' : 'sym o';
  }

  /** Where a word's own page is, which is the same URL the phone builds. */
  const wiktionary = (it: Answer) =>
    `https://en.wiktionary.org/wiki/${encodeURIComponent(it.lemma ?? it.spelling)}`;

  let lead = $derived(headlineOf(answer));
  let chooses = $derived(answer.readings.length >= 2);
  // The core split the transcription and said what each sound is; a card that split it
  // again would be a second opinion about where one sound ends.
  let symbols = $derived(answer.symbols);
  // Each sense with what it is marked as, so a reader is told a sense is archaic or regional
  // rather than meeting it as though it were the ordinary one.
  let rest = $derived(
    chooses
      ? []
      : answer.glosses.slice(1).map((gloss, at) => ({
          gloss,
          marks: answer.marks[at + 1] ?? [],
        }))
  );
  /** What the leading sense is marked as, which belongs beside the answer itself. */
  let leadMarks = $derived(chooses ? [] : (answer.marks[0] ?? []));
  // A machine's answer is labelled one, and so is an English gloss standing in for an answer
  // the dictionary did not reach: unmarked, it reads as the translation rather than as the
  // anchor it is.
  //
  // Taken from where the answer came from rather than guessed at from how far it got: the two
  // are different questions, and the state only happened to answer this one for as long as
  // there was no engine to be a second source.
  let guessed = $derived(
    answer.provenance?.kind === 'guess' || answer.state === 'Guess' || answer.state === 'Phrase'
  );
  /** Which engine, where a machine answered: a reader is owed which one. */
  let engine = $derived(answer.provenance?.kind === 'guess' ? answer.provenance.engine : '');
  // What is being read, and in which accent where the reader chose one: the two are one fact,
  // so they share one neutral pill and the source keeps its own colour beside it.
  let readAs = $derived(
    (() => {
      const name = accentsOf(answer.source).find((row) => row.id === accent)?.name ?? '';
      const code = answer.source.toUpperCase();
      return { label: name ? `${code} · ${name}` : code, name };
    })()
  );
  /** Where the answer came from, in the two words a reader can act on. */
  let from = $derived(
    answer.provenance?.kind === 'dictionary'
      ? { label: 'dictionary', why: `From ${answer.provenance.pack}, which a person wrote`, how: 'from-dictionary' }
      : answer.provenance?.kind === 'guess'
        ? { label: engine || 'machine', why: `Translated by ${engine || 'a machine'}: no dictionary holds this`, how: 'from-machine' }
        : answer.provenance?.kind === 'synthesised'
          ? { label: 'espeak', why: 'Synthesised by espeak: no dictionary has this word', how: 'from-machine' }
          : null
  );
  // Several words a reader selected. There is no transcription of a clause worth showing and
  // no grammar to give, so the card is the selection and what it means.
  let phrase = $derived(answer.state === 'Phrase');
  let anchored = $derived(answer.says.length === 0 && answer.glosses.length > 0);
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

<article class="card">
  <div class="card-handle"></div>
  <header class="card-head">
    <!-- The word the reader is on, whatever else the card could or could not find out. -->
    <div class="card-top">
      <span class="word">{answer.spelling}</span>
      {#if !phrase}
        <span class="pill" title={readAs.name
          ? `Read as ${named(answer.source)}, ${readAs.name} accent`
          : `Read as ${named(answer.source)}`}>{readAs.label}</span>
      {/if}
      {#if from}
        <span class="pill {from.how}" title={from.why}>{from.label}</span>
      {/if}
      <span class="spacer"></span>
      {#if !phrase}
        <!-- The word's own entry, and it is here whether or not a dictionary answered: the
             reader who got nothing is the one most likely to want it. -->
        <IconLink
          icon={WIKTIONARY}
          label="Wiktionary"
          url={wiktionary(answer)}
          name="Wiktionary"
          open={onOpen}
        />
      {/if}
    </div>

    {#if nothing}
      <!-- One sentence and no empty rows. -->
      <p class="note">{missing}</p>
    {:else}
      <div class="headline">
        {#if chooses}
          <!-- Nothing leads: the reader is choosing between the readings below, and a
               headline would be the card choosing for them. -->
          <span class="tr quiet">{answer.spelling} is more than one word</span>
        {:else}
          <span class="tr">{lead ?? answer.spelling}</span>
          <!-- Which kind of word it is, beside what it means rather than on a row of its own:
               alone on a line a single chip reads as a leftover. -->
          {#if answer.pos && !phrase}<span class="chip">{answer.pos}</span>{/if}
          {#each leadMarks as mark (mark)}<span class="chip mark">{mark}</span>{/each}
          {#if guessed}
            <span class="badge guess" title={engine ? `guessed by ${engine}` : 'a machine'}
              >guess</span>
          {:else if anchored}
            <span class="badge">in English</span>
          {/if}
        {/if}
      </div>

      {#if symbols.length > 0}
        <div class="ipa-row">
          <span class="ipa">
            <span class="delim">/</span><!--
            Symbol by symbol, because each one is a button: a reader who does not know a
            sound is one tap from what it is.
         --><!-- No space between them: a transcription is one word and reads as one.
         -->{#each symbols as symbol, i (i)}<button
                class="{symbolClass(symbol.kind)}{opened?.token === symbol.token ? ' active' : ''}"
                title={symbol.name}
                onclick={() => onSymbol?.(symbol.token)}>{symbol.token}</button>{/each}<span
              class="delim">/</span>
          </span>
          <PlayButton
            label={recorded ? `hear ${answer.spelling}` : `say ${answer.spelling}`}
            onplay={() => onPlay?.()}
          />
          <SourceMark kind={recorded ? 'recording' : 'synthesised'} />
        </div>

        <SoundLine
          about={opened}
          {diagram}
          onPlay={onPlaySymbol}
          {onOpen}
        />
      {/if}

      {#if phrase}
        <!-- What was selected, under what it means: a clause is long enough that a reader
             needs to see which of it was answered. -->
        <div class="gram"><span class="g-sub">{answer.spelling}</span></div>
      {/if}

      {#if !chooses && !phrase && answer.lemma}
        <!-- For a form the lemma gets the prominence: "gehen" is what a learner commits to
             memory and "ging" is what they happened to meet. -->
        <div class="gram">
          <span class="lemma">{answer.lemma}</span>
          <!-- Which form, where the dump named it: "plural of perro" says the relation, and
               the spelling alone leaves a reader to work it out. -->
          {#if answer.lemma}
            <span class="g-sub">
              {answer.form ? `${answer.form} of ${answer.lemma}` : `form: ${answer.spelling}`}
            </span>
          {/if}
        </div>
      {/if}
    {/if}
  </header>

  {#if !nothing && (chooses || answer.example || rest.length > 0)}
    <div class="card-body">
      {#if chooses}
        <!-- A reader chooses by meaning, so each reading leads with what it means and
             carries its part of speech at the end of its own row. -->
        <div class="others">
          {#each answer.readings as reading, i (i)}
            <div class="gram">
              <span class="lemma">{reading.says[0] ?? reading.glosses[0] ?? answer.spelling}</span>
              {#if reading.pos}<span class="chip">{reading.pos}</span>{/if}
            </div>
          {/each}
        </div>
      {/if}
      {#if answer.example}
        <!-- One line and only where the dump had one: an invented sentence would settle
             which sense applies, wrongly. -->
        <p class="ex"><q>{answer.example}</q></p>
      {/if}
      {#if rest.length > 0}
        <!-- The senses that did not apply, two of them: the full list made a card into a
             scroll. -->
        <div class="others">
          {#each rest.slice(0, 2) as sense, i (i)}
            <!-- No space before the gloss: the marks are chips beside it, and a text node
                 that begins with one reads as an indent in a list of senses. -->
            <p class="note">{#each sense.marks as mark (mark)}<span class="chip mark"
                >{mark}</span>{" "}{/each}{sense.gloss}</p>
          {/each}
          {#if rest.length > 3}
            <button class="btn-text">{rest.length - 2} more senses</button>
          {:else if rest.length === 3}
            <button class="btn-text">1 more sense</button>
          {/if}
        </div>
      {/if}
    </div>
  {/if}

  {#if !nothing}
    <footer class="card-foot">
      <!-- Nothing where there is nothing to say: an arrow between two blanks is a line
           saying only that a line was drawn, and one language read into itself is the same
           word twice, which is what a reader sees until they choose a language to read into. -->
      <span>{answer.source && answer.target && answer.source !== answer.target
        ? `${named(answer.source)} → ${named(answer.target)}` : ''}</span>
      <!-- The way onward is the mark on the top row, where a reader who wants the whole entry
           looks: a second Wiktionary button down here was the same link twice. -->
      <span class="actions">{answer.provenance?.kind === 'dictionary'
        ? answer.provenance.pack : ''}</span>
    </footer>
  {/if}
</article>
