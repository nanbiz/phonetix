<script lang="ts">
  // One choice out of many, drawn by this product rather than by the operating system.
  //
  // A native menu is the platform's: on a phone it opens as a white dialog of radio buttons in
  // that device's own font and colours, over a surface drawn in the palette the reader chose,
  // and it cannot be searched - which matters at fifty-four languages. So the list is ours. It
  // is the same row, the same type and the same colours as everything else here, and a list
  // long enough to need one gets a filter.
  import { SAYS } from '@/data/wording';

  interface Props {
    /** What each option says and the value it stands for, in the order to show them. */
    options: { value: string; label: string }[];
    chosen: string;
    label: string;
    /** From how many options the list is worth filtering. */
    filterFrom?: number;
    change: (value: string) => void;
  }

  let { options, chosen, label, filterFrom = 12, change }: Props = $props();

  let open = $state(false);
  let typed = $state('');

  let name = $derived(options.find((it) => it.value === chosen)?.label ?? '');
  let shown = $derived(
    typed.trim() === ''
      ? options
      : options.filter((it) => it.label.toLowerCase().includes(typed.trim().toLowerCase()))
  );

  function pick(value: string) {
    open = false;
    typed = '';
    change(value);
  }
</script>

<button
  class="select"
  aria-label={label}
  aria-haspopup="listbox"
  onclick={() => {
    typed = '';
    open = true;
  }}
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
      <button class="btn-text" onclick={() => (open = false)}>{SAYS['close']}</button>
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
          <span class="c-name">{option.label}</span>
          {#if option.value === chosen}<span class="c-mark" aria-hidden="true">✓</span>{/if}
        </button>
      {/each}
      {#if shown.length === 0}
        <p class="sheet-none">{SAYS['nothing-found']}</p>
      {/if}
    </div>
  </div>
{/if}
