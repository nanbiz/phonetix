<script lang="ts">
  // One screen of the settings view: a way back, a title, and what is on it.
  //
  // Every screen stays in the document and the one being read is the one that is shown, so a
  // reader who comes back from the accents finds the bar where they left it.
  import type { Snippet } from 'svelte';

  interface Props {
    /** Which screen this is, and which one the reader is on. */
    name: string;
    on: string;
    title?: string;
    /** What the title says about this screen in a word, such as how many are held. */
    note?: string;
    back?: () => void;
    children: Snippet;
  }

  let { name, on, title = '', note = '', back, children }: Props = $props();
</script>

<section hidden={on !== name} data-view={name}>
  {#if back}
    <button class="back" onclick={back}>‹ Back</button>
  {/if}
  {#if title}
    <h4 class="head">{title}{#if note}<span class="h-note">{note}</span>{/if}</h4>
  {/if}
  {@render children()}
</section>
