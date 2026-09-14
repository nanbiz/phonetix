<script lang="ts">
  // The word a reader is looking for, over the page they are reading.
  //
  // The other direction, and the same question the phone's mark answers: everything else here
  // is about a word somebody else wrote, and this one is a word that exists only in the
  // reader's head. It is a panel over the page rather than a screen of its own, because
  // asking should not take them away from what they were reading.
  //
  // Answered as it is typed. What language it was typed in is worked out rather than assumed,
  // so a reader can ask in whatever language the word came to them in.
  import type { Answer } from '@/core/answer';
  import { LANGUAGES, named as nameOf } from '@/data/languages';
  import { SAYS, ROWS } from '@/data/wording';
  import AnswerCard from '@/ui/card/AnswerCard.svelte';
  import Picker from '@/ui/controls/Picker.svelte';
  import { offering } from '@/settings/shape';

  interface Props {
    /** The language the answer comes back in, which the reader picks here. */
    learning: string;
    /** Which languages to offer first: the ones this reader has a dictionary for. */
    held?: string[];
    /** And ahead of those, the ones they have asked in lately. */
    recent?: string[];
    /** Ask for a word, in the language it was typed in. */
    ask: (
      text: string,
      learning: string
    ) => Promise<{ answer: Answer | null; missing: boolean } | null>;
    onLearning: (lang: string) => void;
    close: () => void;
  }

  let { learning, held = [], recent = [], ask, onLearning, close }: Props = $props();

  /** Which language the answer comes back in, as this panel has it: the reader can change it
   *  while the panel is up, and what they pick is remembered by whoever opened it. */
  let into = $state(learning);
  let wanted = $state('');
  let said = $state<Answer | null>(null);
  let asking = $state(false);
  let nothing = $state(false);
  let waiting: ReturnType<typeof setTimeout> | null = null;

  /** What the list offers, and in which order: see [offering]. The order is taken once, from
   *  what was remembered when the panel opened, so picking a language does not reshuffle the
   *  list under the reader who is looking at it. */
  const first = learning;
  let offered = $derived(
    offering(first, recent, held, Object.keys(LANGUAGES)).map((lang) => ({
      value: lang,
      label: nameOf(lang),
    }))
  );

  async function answer(text: string, into: string) {
    if (!text.trim() || !into) return;
    asking = true;
    nothing = false;
    const came = await ask(text.trim(), into).catch(() => null);
    asking = false;
    said = came?.answer ?? null;
    nothing = said === null;
  }

  /** Asked as it is typed: the question is short and the answer is wanted while it is being
   *  written. The last keystroke wins - what is being typed now is not a question yet. */
  function typed(text: string) {
    wanted = text;
    if (waiting) clearTimeout(waiting);
    waiting = setTimeout(() => void answer(text, into), 450);
  }
</script>

<div class="panel ask" data-ask>
  <div class="rows">
    <!-- No title over the panel: the control on this row says which language the answer comes
         back in, the field under it says what to type, and a panel two rows tall has nothing
         to spare for being named as well. -->
    <div class="row" data-row="say-into">
      <span class="r-wide ask-top">
        <Picker
          options={offered}
          chosen={into}
          label={SAYS['say-into']}
          empty={SAYS['choose-language']}
          change={(lang) => {
            into = lang;
            onLearning(lang);
            if (wanted) void answer(wanted, lang);
          }}
        />
        <button class="btn-text ask-close" data-does="close" onclick={close}>
          {SAYS['close']}
        </button>
      </span>
    </div>
    <div class="row">
      <span class="r-wide">
        <label class="field">
          <!-- svelte-ignore a11y_autofocus -->
          <input
            aria-label={ROWS.say.name}
            placeholder={SAYS['say-placeholder']}
            value={wanted}
            autofocus
            oninput={(event) => typed((event.currentTarget as HTMLInputElement).value)}
          />
        </label>
      </span>
    </div>
  </div>
  {#if said}
    <div class="answer"><AnswerCard answer={said} /></div>
  {:else if asking}
    <p class="about">…</p>
  {:else if nothing && wanted}
    <p class="about">{SAYS['say-nothing']}</p>
  {/if}
</div>
