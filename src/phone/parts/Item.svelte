<script lang="ts">
  // One row on a card: its name, what it is set to, and what changes it.
  //
  // With `open` the whole row is a button leading somewhere and ends in a chevron; otherwise
  // the control at its end is what is pressed.
  import type { Snippet } from 'svelte';
  import Chevron from 'virtual:icons/pixelarticons/chevron-right';

  interface Props {
    name: string;
    /** The name this row reports itself under, so a check can find it. */
    row?: string;
    /** What it is set to, under the name. */
    value?: string;
    /** A picture at the start of the row, such as an app's icon. */
    lead?: Snippet;
    /** The control at the end of the row. */
    control?: Snippet;
    open?: () => void;
  }

  let { name, row = '', value = '', lead, control, open }: Props = $props();
</script>

{#if open}
  <button class="item" data-row={row || undefined} onclick={open}>
    {#if lead}{@render lead()}{/if}
    <span class="item-text">
      <span class="item-name" data-name>{name}</span>
      {#if value}<span class="item-value" data-about>{value}</span>{/if}
    </span>
    <Chevron class="item-chevron" />
  </button>
{:else}
  <label class="item" data-row={row || undefined}>
    {#if lead}{@render lead()}{/if}
    <span class="item-text">
      <span class="item-name" data-name>{name}</span>
      {#if value}<span class="item-value" data-about>{value}</span>{/if}
    </span>
    {#if control}{@render control()}{/if}
  </label>
{/if}
