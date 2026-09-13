<script lang="ts">
  // What is still missing before anything can work, and the way to do it.
  //
  // A fresh install can answer nothing: it has no language to read into and nowhere to fetch a
  // dictionary from. Both of those used to sit below the controls that depend on them, and
  // nothing said so - a reader met a bar for how much of the page to annotate, set it, and saw
  // no change, because there was nothing to annotate with.
  //
  // Nothing is drawn once the steps are done, so this is a thing a reader passes through once
  // rather than a permanent header.
  import type { Snippet } from 'svelte';
  import { ROWS } from '@/data/wording';

  interface Props {
    /** How many steps are still to be done. Nothing is drawn at none. */
    left: number;
    /** The steps themselves, as rows, so each carries the control that does it: a reader
     *  should finish a step where they read it rather than be sent somewhere for it. */
    children: Snippet;
  }

  let { left, children }: Props = $props();
</script>

{#if left > 0}
  <section class="start" data-row="start">
    <h4 class="head">
      {ROWS.start.name}<span class="h-note">{left} left</span>
    </h4>
    <p class="about">{ROWS.start.about}</p>
    <div class="rows">{@render children()}</div>
  </section>
{/if}
