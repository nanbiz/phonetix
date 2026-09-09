<script lang="ts">
  // The answer surface: what a reader gets when they stop at a word.
  //
  // The order is what a reader wants, not what the data happens to hold: what it means, how
  // it is said, which word it is, then the senses that did not apply. The tapped spelling is
  // deliberately not the headline, because the page is already showing it under the cursor.
  //
  // The markup is the surface page's own and the stylesheet is generated from it, so this
  // card and the Compose card are the same card.
  import { headline as headlineOf, type Answer } from '@/core/answer';
  import { named } from '@/data/languages';
  import PlayButton from './PlayButton.svelte';
  import SourceMark from './SourceMark.svelte';

  interface Props {
    answer: Answer;
    /** A recording of a person saying the word, where one was found. */
    recorded?: boolean;
    /** A sound the reader asked about, which belongs one tap under the word. */
    onSymbol?: (symbol: string) => void;
    onPlay?: () => void;
    onOpen?: (url: string) => void;
  }

  let { answer, recorded = false, onSymbol, onPlay, onOpen }: Props = $props();

  /** Which colour a symbol takes: the page colours vowels, consonants and the rest apart. */
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
  let rest = $derived(chooses ? [] : answer.glosses.slice(1));
  // A machine's answer is labelled one, and so is an English gloss standing in for an answer
  // the dictionary did not reach: unmarked, it reads as the translation rather than as the
  // anchor it is.
  let guessed = $derived(answer.state === 'Guess');
  let anchored = $derived(answer.says.length === 0 && answer.glosses.length > 0);
  let nothing = $derived(lead === null && answer.ipa.length === 0);
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
  {#if nothing}
    <!-- One sentence and no empty rows. -->
    <header class="card-head"><p class="note">{missing}</p></header>
  {:else}
    <header class="card-head">
      <div class="headline">
        {#if chooses}
          <!-- Nothing leads: the reader is choosing between the readings below, and a
               headline would be the card choosing for them. -->
          <span class="tr quiet">{answer.spelling} is more than one word</span>
        {:else}
          <span class="tr">{lead ?? answer.spelling}</span>
          {#if guessed}
            <span class="badge guess">guess</span>
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
                class={symbolClass(symbol.kind)}
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
      {/if}

      {#if !chooses && (answer.lemma || answer.pos)}
        <!-- For a form the lemma gets the prominence: "gehen" is what a learner commits to
             memory and "ging" is what they happened to meet. -->
        <div class="gram">
          {#if answer.lemma}<span class="lemma">{answer.lemma}</span>{/if}
          {#if answer.pos}<span class="chip">{answer.pos}</span>{/if}
          {#if answer.lemma}<span class="g-sub">form: {answer.spelling}</span>{/if}
        </div>
      {/if}
    </header>

    {#if chooses || answer.example || rest.length > 0}
      <div class="card-body">
        {#if chooses}
          <!-- A reader chooses by meaning, so each reading leads with what it means and
               carries its part of speech at the end of its own row. -->
          <div class="others">
            {#each answer.readings as reading, i (i)}
              <div class="gram">
                <span class="lemma"
                  >{reading.says[0] ?? reading.glosses[0] ?? answer.spelling}</span
                >
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
              <p class="note">{sense}</p>
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

    <footer class="card-foot">
      <!-- Nothing where there is nothing to say: an arrow between two blanks is a line
           saying only that a line was drawn, and one language read into itself is the same
           word twice, which is what a reader sees until they choose a language to read into. -->
      <span>{answer.source && answer.target && answer.source !== answer.target
        ? `${named(answer.source)} → ${named(answer.target)}` : ''}</span>
      <span class="actions">
        <!-- Always here, whether or not a dictionary answered: a reader who got nothing is
             the one most likely to want it. -->
        <button class="btn-text" onclick={() => onOpen?.(wiktionary(answer))}>Wiktionary</button>
      </span>
    </footer>
  {/if}
</article>
