<script lang="ts">
  // One line of a settings screen: what it is, what it does, and what changes it.
  //
  // Every row on every screen is this one, so a row cannot end up laid out one way here and
  // another way two screens along.
  import type { Snippet } from 'svelte';

  interface Props {
    /** What the row is called, which is also what a reader reads it by. */
    name: string;
    /** The name this row reports itself under, so a check can find one row among many. */
    row?: string;
    /** A sentence under the name, where the name alone does not say enough. */
    about?: string;
    /** What the row is set to, said beside the name rather than by the control. */
    says?: string;
    /** Anything else the row identifies itself by, such as which language it is about. */
    marks?: Record<string, string>;
    /** The control itself. */
    control?: Snippet;
    /** A control that needs the whole width, such as a bar. */
    wide?: Snippet;
  }

  let { name, row = '', about = '', says = '', marks = {}, control, wide }: Props = $props();
</script>

<div class="row" data-row={row || undefined} {...marks}>
  <span class="r-name" data-name>{name}</span>
  {#if says}
    <span class="r-act" data-about>{says}</span>
  {/if}
  {#if about}
    <span class="r-sub" data-about={says ? undefined : ''}>{about}</span>
  {/if}
  {#if control && !says}
    <span class="r-act">{@render control()}</span>
  {/if}
  {#if wide}
    <span class="r-wide">{@render wide()}</span>
  {/if}
</div>
