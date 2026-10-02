<script lang="ts">
  // A long list to choose from, over the whole screen, with a search at its top.
  //
  // Standing while it is open, so the phone's way back closes it rather than the app.
  import { onMount } from 'svelte';
  import { SAYS } from '@/data/wording';
  import { standing } from '@/ui/controls/sheets.svelte';
  import Close from 'virtual:icons/pixelarticons/close';
  import Options from './Options.svelte';

  interface Props {
    title: string;
    options: { value: string; label: string; about?: string }[];
    chosen: string;
    /** From how many options the list gets a search. */
    searchFrom?: number;
    change: (value: string) => void;
    close: () => void;
  }

  let { title, options, chosen, searchFrom = 12, change, close }: Props = $props();

  let typed = $state('');
  let shown = $derived.by(() => {
    const wanted = typed.trim().toLowerCase();
    // What is chosen comes first, so the list opens on it.
    if (!wanted) return [...options.filter((it) => it.value === chosen), ...options.filter((it) => it.value !== chosen)];
    return options.filter(
      (it) => it.label.toLowerCase().includes(wanted) || it.value.toLowerCase() === wanted
    );
  });

  onMount(() => standing(close));
</script>

<div class="sheet" role="dialog" aria-label={title} data-sheet>
  <header class="sheet-top">
    <h2 class="sheet-title">{title}</h2>
    <button class="icon-button" aria-label={SAYS['close']} onclick={close}>
      <Close />
    </button>
  </header>
  {#if options.length >= searchFrom}
    <input class="search" type="search" placeholder={SAYS['search']} bind:value={typed} />
  {/if}
  <div class="sheet-list">
    <div class="card">
      <Options
        options={shown}
        {chosen}
        change={(value) => {
          change(value);
          close();
        }}
      />
    </div>
    {#if shown.length === 0}<p class="none">{SAYS['nothing-found']}</p>{/if}
  </div>
</div>
