<script lang="ts">
  // What is still missing before a word's meaning can be shown, and the way to do it.
  //
  // Not a gate across the view. Every language the product knows how to pronounce travels with
  // it, so a reader who has configured nothing is already answered: what a page says, in the
  // sounds of its own language. Meanings are the part that needs more - a language to read
  // into, and a dictionary for the language being read, which is built from a dump and is far
  // larger than anything that can be carried.
  //
  // Gone once both are there, because then there is nothing to say.
  import type { Snippet } from 'svelte';
  import { ROWS } from '@/data/wording';

  interface Props {
    /** Whether anything is still missing. Nothing is drawn when nothing is. */
    missing: boolean;
    /** The steps, as rows, so each carries the control that does it: a reader should finish a
     *  step where they read it rather than be sent somewhere for it. */
    children: Snippet;
  }

  let { missing, children }: Props = $props();
</script>

{#if missing}
  <section class="start" data-row="meanings">
    <h4 class="head">{ROWS.meanings.name}</h4>
    <p class="about">{ROWS.meanings.about}</p>
    <div class="rows">{@render children()}</div>
  </section>
{/if}
