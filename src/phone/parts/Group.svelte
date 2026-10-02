<script lang="ts">
  // Settings that belong together: a name above, and the rows on one card under it.
  import type { Snippet } from 'svelte';

  interface Props {
    /** What the group is about. A group without one is the rest of the screen. */
    name?: string;
    children: Snippet;
  }

  let { name = '', children }: Props = $props();
</script>

<section class="group">
  {#if name}<h2 class="group-name">{name}</h2>{/if}
  <div class="card">{@render children()}</div>
</section>
