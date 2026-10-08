<script lang="ts">
  import { onMount, untrack } from 'svelte';
  // A word or a phrase translated between the reader's language and the one they are learning,
  // asked over the page they are reading.
  //
  // The phone's panel, drawn the same way: the two languages head it as one bar with an arrow
  // between them for the way the question is answered. Which language it was typed in is worked
  // out rather than assumed, so a word in either comes back in the other, and the arrow turns it
  // round where it was worked out wrong. Each language opens a list to choose another. A word is
  // answered with everything it can mean, the commonest first; a phrase with its translation.
  import type { PanelAnswer } from '@/host/messages';
  import { LANGUAGES, named as nameOf } from '@/data/languages';
  import { SAYS, ROWS } from '@/data/wording';
  import { offering } from '@/settings/shape';

  interface Props {
    /** The reader's own language. */
    mine: string;
    /** The one they are learning, which they pick here and which is remembered. */
    learning: string;
    /** Which languages to offer first: the ones this reader has a dictionary for. */
    held?: string[];
    /** And ahead of those, the ones they have asked in lately. */
    recent?: string[];
    ask: (
      text: string,
      mine: string,
      learning: string,
      turned: boolean | undefined
    ) => Promise<PanelAnswer | null>;
    onMine: (lang: string) => void;
    onLearning: (lang: string) => void;
    close: () => void;
  }

  let { mine, learning, held = [], recent = [], ask, onMine, onLearning, close }: Props = $props();

  // The values it opened with, on purpose: the reader changes them here while the panel is up,
  // and what they pick is remembered by whoever opened it.
  let ours = $state(untrack(() => mine));
  let theirs = $state(untrack(() => learning));
  let wanted = $state('');
  let said = $state<PanelAnswer | null>(null);
  let asking = $state(false);
  /** Which way the last question was answered: from the reader's language when true. */
  let forward = $state(true);
  /** The way the reader set by hand with the arrow; a new question is worked out afresh. */
  let turned = $state<boolean | undefined>(undefined);
  let waiting: ReturnType<typeof setTimeout> | null = null;
  /** Which language's list is open, if either. */
  let choosing = $state<'mine' | 'learning' | null>(null);
  let typedFilter = $state('');
  /** The questions asked, so an answer arriving late for an earlier one is not drawn. */
  let asks = 0;

  let field: HTMLInputElement | undefined = $state();
  let filter: HTMLInputElement | undefined = $state();

  // Focused the moment it is drawn: the panel is opened to type in, and a field mounted into a
  // shadow root after the page has loaded is not reached by autofocus.
  onMount(() => field?.focus());

  /** What each list offers, and in which order: the one chosen, the ones asked in lately, the
   *  ones a dictionary is held for, and the rest. Taken from what was remembered when the panel
   *  opened, so picking a language does not reshuffle the list under the reader. */
  const firstMine = untrack(() => mine);
  const firstLearning = untrack(() => learning);
  const all = Object.keys(LANGUAGES);
  let offered = $derived(
    (choosing === 'mine'
      ? offering(firstMine, [], [], all)
      : offering(firstLearning, recent, held, all)
    ).map((lang) => ({ value: lang, label: nameOf(lang) }))
  );
  let shown = $derived(
    typedFilter.trim() === ''
      ? offered
      : offered.filter((it) => it.label.toLowerCase().includes(typedFilter.trim().toLowerCase()))
  );

  async function answer() {
    const text = wanted.trim();
    // An emptied field is a question taken back, and the answer goes with it.
    if (!text) {
      said = null;
      asking = false;
      return;
    }
    const turn = ++asks;
    asking = true;
    const came = await ask(text, ours, theirs, turned).catch(() => null);
    if (turn !== asks) return;
    asking = false;
    said = came;
    if (came) forward = came.forward;
  }

  /** Asked as it is typed: the question is short and the answer is wanted while it is being
   *  written. The last keystroke wins - what is being typed now is not a question yet. */
  function typed(text: string) {
    wanted = text;
    turned = undefined;
    if (waiting) clearTimeout(waiting);
    waiting = setTimeout(() => void answer(), 450);
  }

  function turn() {
    turned = !forward;
    forward = turned;
    void answer();
  }

  function choose(which: 'mine' | 'learning') {
    if (choosing === which) {
      choosing = null;
      return;
    }
    choosing = which;
    typedFilter = '';
    queueMicrotask(() => filter?.focus());
  }

  function pick(lang: string) {
    if (choosing === 'mine') {
      ours = lang;
      onMine(lang);
    } else {
      theirs = lang;
      onLearning(lang);
    }
    choosing = null;
    // Back to the question, since a language is chosen to ask in it.
    queueMicrotask(() => field?.focus());
    void answer();
  }

  /** The way out takes away the one thing that is open: the list before the panel. */
  function keys(event: KeyboardEvent) {
    if (event.key !== 'Escape') return;
    event.stopPropagation();
    if (choosing) {
      choosing = null;
      field?.focus();
    } else {
      close();
    }
  }

  let from = $derived(forward ? ours : theirs);
  let into = $derived(forward ? theirs : ours);
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="panel ask"
  data-ask
  data-from={from}
  data-into={into}
  onkeydown={keys}
>
  <div class="ask-pair" data-row="pair">
    <button
      class="ask-lang {choosing === 'mine' ? 'on' : ''}"
      data-lang="mine"
      aria-haspopup="listbox"
      aria-expanded={choosing === 'mine'}
      onclick={() => choose('mine')}>{nameOf(ours)}</button
    >
    <button
      class="ask-turn {forward ? '' : 'back'}"
      data-does="turn"
      aria-label={SAYS['turn']}
      onclick={turn}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true"
        ><path
          d="M4 12h15m-6-6 6 6-6 6"
          fill="none"
          stroke="currentColor"
          stroke-width="2.4"
          stroke-linecap="round"
          stroke-linejoin="round"
        /></svg
      >
    </button>
    <button
      class="ask-lang {choosing === 'learning' ? 'on' : ''}"
      data-lang="learning"
      aria-haspopup="listbox"
      aria-expanded={choosing === 'learning'}
      onclick={() => choose('learning')}
      >{theirs ? nameOf(theirs) : SAYS['choose-language']}</button
    >
  </div>

  {#if choosing}
    <div class="sheet" role="dialog" data-sheet>
      <div class="sheet-head">
        <label class="field">
          <input
            bind:this={filter}
            aria-label={SAYS['search']}
            placeholder={SAYS['search']}
            bind:value={typedFilter}
          />
        </label>
      </div>
      <div class="sheet-list" role="listbox">
        {#each shown as option (option.value)}
          {@const chosen = option.value === (choosing === 'mine' ? ours : theirs)}
          <button
            class="choice {chosen ? 'on' : ''}"
            data-choice={option.value}
            role="option"
            aria-selected={chosen}
            onclick={() => pick(option.value)}
          >
            <span class="c-what"><span class="c-name">{option.label}</span></span>
            {#if chosen}<span class="c-mark" aria-hidden="true">✓</span>{/if}
          </button>
        {/each}
        {#if shown.length === 0}
          <p class="sheet-none">{SAYS['nothing-found']}</p>
        {/if}
      </div>
    </div>
  {/if}

  <label class="ask-field">
    <input
      bind:this={field}
      aria-label={ROWS.say.name}
      placeholder={SAYS['say-placeholder']}
      value={wanted}
      oninput={(event) => typed(event.currentTarget.value)}
    />
  </label>

  {#if said && said.kind === 'meanings'}
    <div class="ask-said ask-means" data-said="meanings">
      {#each said.meanings as meant, at (at)}
        <div class="ask-mean">
          <div>
            <span class="m-word">{meant.word}</span>{#if meant.ipa}<span class="m-ipa"
                >/{meant.ipa}/</span
              >{/if}
          </div>
          {#if meant.pos || meant.hint}
            <div class="m-about">{[meant.pos, meant.hint].filter(Boolean).join(' · ')}</div>
          {/if}
        </div>
      {/each}
    </div>
  {:else if said && said.kind === 'line'}
    <div class="ask-said ask-line" data-said="line">
      {said.line}{#if said.ipa}<span class="l-ipa">/{said.ipa}/</span>{/if}
    </div>
  {:else if asking}
    <p class="ask-note">…</p>
  {:else if said && said.kind === 'nothing' && wanted.trim()}
    <p class="ask-note" data-said="nothing">{SAYS['say-nothing']}</p>
  {/if}
</div>
