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

  /** Which of the rows shown the keyboard is on: the first that matches what was typed, and
   *  then wherever the arrows take it, so Enter picks a language without the pointer. None
   *  until something is typed or an arrow pressed. */
  let at = $derived<number | null>(typed.trim() ? 0 : null);
  let lit = $derived(at === null ? '' : (shown[Math.min(at, shown.length - 1)]?.value ?? ''));

  function pick(value: string): void {
    change(value);
    if (!Array.isArray(chosen)) close();
  }

  function keys(event: KeyboardEvent): void {
    const last = shown.length - 1;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      at = at === null ? 0 : Math.min(last, at + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      at = at === null ? 0 : Math.max(0, at - 1);
    } else if (event.key === 'Enter' && lit) {
      event.preventDefault();
      pick(lit);
    }
  }

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
    <input
      class="search"
      type="search"
      placeholder={SAYS['search']}
      bind:value={typed}
      onkeydown={keys}
    />
  {/if}
  <div class="sheet-list">
    <div class="card">
      <Options options={shown} {chosen} {lit} change={pick} />
    </div>
    {#if shown.length === 0}<p class="none">{SAYS['nothing-found']}</p>{/if}
  </div>
</div>
