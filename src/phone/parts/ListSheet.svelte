<script lang="ts">
  // A long list to choose from, over the whole screen, with a search at its top.
  //
  // Standing while it is open, so the phone's way back closes it rather than the app.
  import { onMount, untrack } from 'svelte';
  import { SAYS } from '@/data/wording';
  import { standing } from '@/ui/controls/sheets.svelte';
  import Close from 'virtual:icons/lucide/x';
  import Options from './Options.svelte';

  interface Props {
    title: string;
    options: { value: string; label: string; about?: string }[];
    /** One value, chosen by picking it, which closes the list; or several, each picked or put
     *  back on its own while the list stays open. */
    chosen: string | string[];
    /** From how many options the list gets a search. */
    searchFrom?: number;
    change: (value: string) => void;
    close: () => void;
  }

  let { title, options, chosen, searchFrom = 12, change, close }: Props = $props();

  let typed = $state('');
  /** What was chosen when the list opened, which comes first so the list opens on it. Taken
   *  once: a row that jumped to the top as it was ticked would leave the reader's finger on
   *  another one. */
  const first = untrack(() => (Array.isArray(chosen) ? [...chosen] : [chosen]));
  let shown = $derived.by(() => {
    const wanted = typed.trim().toLowerCase();
    if (!wanted) return [...options.filter((it) => first.includes(it.value)), ...options.filter((it) => !first.includes(it.value))];
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
          if (!Array.isArray(chosen)) close();
        }}
      />
    </div>
    {#if shown.length === 0}<p class="none">{SAYS['nothing-found']}</p>{/if}
  </div>
</div>
