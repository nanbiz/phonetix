<script lang="ts">
  // A small square button that is a mark rather than a word.
  //
  // The mark is a component from the icon set that drew it, so a button is one element and one
  // rule wherever it appears.
  import type { Component } from 'svelte';

  interface Props {
    /** The mark, as the set draws it. */
    icon: Component;
    /** What it does, said in words for a reader who cannot see the mark. */
    label: string;
    /** The name it reports itself under, so a check can tell two buttons apart. */
    name?: string;
    press: () => void;
  }

  let { icon, label, name = '', press }: Props = $props();
  const Icon = $derived(icon);
</script>

<button
  class="icon-btn"
  title={label}
  aria-label={label}
  data-does={name || undefined}
  onclick={press}
><Icon /></button>
