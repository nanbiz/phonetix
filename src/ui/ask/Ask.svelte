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
  import type { Heard, PanelAnswer } from '@/host/messages';
  import type { Listening } from '@/host/speech';
  import { LANGUAGES, named as nameOf } from '@/data/languages';
  import { SAYS, ROWS } from '@/data/wording';
  import { offering } from '@/settings/shape';
  import Arrow from 'virtual:icons/lucide/arrow-right';
  import Mic from 'virtual:icons/lucide/mic';
  import MicOff from 'virtual:icons/lucide/mic-off';
  import Thinking from 'virtual:icons/lucide/loader-circle';

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
    /** Hear the question said rather than typed, in this language. */
    hear?: (lang: string) => Promise<Heard>;
    /** End what is being heard now. */
    stopHearing?: () => void;
    /** Be told what the microphone is doing while a question is said; returns how to stop
     *  being told. */
    watchHearing?: (told: (now: Listening | null) => void) => () => void;
    /** Hear a question from the moment it is drawn, as the microphone's key opens it. */
    listenNow?: boolean;
    /** Told how to press the microphone, for its key on a panel already open. */
    onMic?: (press: () => void) => void;
  }

  let {
    mine,
    learning,
    held = [],
    recent = [],
    ask,
    onMine,
    onLearning,
    close,
    hear,
    stopHearing,
    watchHearing,
    listenNow = false,
    onMic,
  }: Props = $props();

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
  onMount(() => {
    field?.focus();
    onMic?.(() => void speak());
    if (untrack(() => listenNow)) void speak();
    return watchHearing?.((now) => (hearing = now));
  });

  /** What the microphone is doing: nothing, recording, or writing down what it heard, with how
   *  much of the model has arrived the first time. */
  let hearing = $state<Listening | null>(null);
  /** Whether a press is waiting on the microphone: asking for it until the host says it hears. */
  let pressed = $state(false);
  /** Whether the reader would not let the extension hear them. */
  let refused = $state(false);

  async function speak() {
    if (!hear) return;
    if (pressed) {
      // Pressed again while it hears: that is the end of the question.
      if (hearing?.phase !== 'thinking') stopHearing?.();
      return;
    }
    pressed = true;
    // Said in the language the arrow comes from: the reader turns it to speak the other.
    const heard = await hear(from).catch(() => ({ kind: 'nothing' as const }));
    pressed = false;
    hearing = null;
    refused = heard.kind === 'refused';
    if (heard.kind !== 'said') return;
    wanted = heard.text;
    // Answered the way the arrow points, since that is the language it was said in.
    turned = forward;
    field?.focus();
    void answer();
  }

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
      <Arrow />
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

  <div class="ask-field">
    <input
      bind:this={field}
      aria-label={ROWS.say.name}
      placeholder={SAYS['say-placeholder']}
      value={wanted}
      oninput={(event) => typed(event.currentTarget.value)}
    />
    {#if hear}
      {@const phase = pressed ? (hearing?.phase ?? 'asking') : null}
      <button
        class="ask-mic {phase ?? ''} {refused && !phase ? 'refused' : ''}"
        data-does="speak"
        data-hearing={phase ?? (refused ? 'refused' : 'idle')}
        aria-label={SAYS['speak']}
        aria-pressed={phase !== null}
        style={hearing?.share !== undefined ? `--arrived: ${Math.round(hearing.share * 100)}%` : ''}
        onclick={() => void speak()}
      >
        {#if phase === 'thinking'}<Thinking />{:else if refused && !phase}<MicOff />{:else}<Mic />{/if}
      </button>
    {/if}
  </div>

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
