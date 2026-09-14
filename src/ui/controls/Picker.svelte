<script lang="ts">
  // One choice out of many, drawn by this product rather than by the operating system.
  //
  // A native menu is the platform's: on a phone it opens as a white dialog of radio buttons in
  // that device's own font and colours, over a surface drawn in the palette the reader chose,
  // and it cannot be searched - which matters at fifty-four languages. So the list is ours. It
  // is the same row, the same type and the same colours as everything else here, and a list
  // long enough to need one gets a filter.
  import { SAYS } from '@/data/wording';
  import { standing } from './sheets.svelte';

  interface Props {
    /** What each option says and the value it stands for, in the order to show them, and a
     *  line under it where the name alone does not say enough. */
    options: { value: string; label: string; about?: string }[];
    chosen: string;
    label: string;
    /** What the control says while nothing is chosen, where that can happen. */
    empty?: string;
    /** From how many options the list is worth filtering. */
    filterFrom?: number;
    change: (value: string) => void;
  }

  let { options, chosen, label, empty = '', filterFrom = 12, change }: Props = $props();

  let open = $state(false);
  let typed = $state('');

  let name = $derived(options.find((it) => it.value === chosen)?.label ?? empty);
  let shown = $derived(
    typed.trim() === ''
      ? options
      : options.filter((it) => it.label.toLowerCase().includes(typed.trim().toLowerCase()))
  );

  /** How to stop being what the way back closes, once this list is down. */
  let listed: (() => void) | null = null;

  function show() {
    typed = '';
    open = true;
    // While the list is up it is what the way back closes: on a phone, going back from an
    // open list used to close the app. Said here rather than in an effect - an effect that
    // both reads this and writes it from its own cleanup is a loop, and Svelte stops the
    // whole view when it finds one: every control on the screen went dead.
    listed = standing(close);
  }

  function close() {
    open = false;
    typed = '';
    listed?.();
    listed = null;
  }

  function pick(value: string) {
    close();
    change(value);
  }
</script>

<button
  class="select"
  aria-label={label}
  aria-haspopup="listbox"
  aria-expanded={open}
  onclick={() => (open ? close() : show())}
>
  {name}
</button>

{#if open}
  <!-- Over the whole view rather than under the row: a list of this length has nowhere to
       hang, and on a phone the view is the screen. -->
  <div class="sheet" role="dialog" aria-label={label} data-sheet>
    <div class="sheet-head">
      {#if options.length >= filterFrom}
        <label class="field">
          <input
            aria-label={label}
            placeholder={SAYS['search']}
            bind:value={typed}
          />
        </label>
      {:else}
        <span class="sheet-name">{label}</span>
      {/if}
      <button class="btn-text" onclick={close}>{SAYS['close']}</button>
    </div>
    <div class="sheet-list" role="listbox">
      {#each shown as option (option.value)}
        <button
          class="choice {option.value === chosen ? 'on' : ''}"
          data-choice={option.value}
          role="option"
          aria-selected={option.value === chosen}
          onclick={() => pick(option.value)}
        >
          <span class="c-what">
            <span class="c-name">{option.label}</span>
            {#if option.about}<span class="c-about">{option.about}</span>{/if}
          </span>
          {#if option.value === chosen}<span class="c-mark" aria-hidden="true">✓</span>{/if}
        </button>
      {/each}
      {#if shown.length === 0}
        <p class="sheet-none">{SAYS['nothing-found']}</p>
      {/if}
    </div>
  </div>
{/if}
