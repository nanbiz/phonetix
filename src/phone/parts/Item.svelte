<script lang="ts">
  // One row on a card: its name, what it is set to, and what changes it.
  //
  // With `open` the whole row is a button leading somewhere and ends in a chevron; otherwise
  // the control at its end is what is pressed. With both, the row's words and chevron lead
  // somewhere and the control at its end is pressed on its own: a switch for the thing, and
  // a screen of what it does.
  import type { Snippet } from 'svelte';
  import Chevron from 'virtual:icons/lucide/chevron-right';

  interface Props {
    name: string;
    /** The name this row reports itself under, so a check can find it. */
    row?: string;
    /** What it is set to, under the name. */
    value?: string;
    /** A picture at the start of the row, such as an app's icon. */
    lead?: Snippet;
    /** A small picture on the name's line, before it, saying what the row is about. */
    glyph?: Snippet;
    /** The control at the end of the row. */
    control?: Snippet;
    open?: () => void;
  }

  let { name, row = '', value = '', lead, glyph, control, open }: Props = $props();
</script>

{#if open && control}
  <div class="item split" data-row={row || undefined}>
    <button class="item-open" data-does="open" onclick={open}>
      {#if lead}{@render lead()}{/if}
      <span class="item-text">
        <span class="item-name" data-name>{#if glyph}{@render glyph()}{/if}{name}</span>
        {#if value}<span class="item-value" data-about>{value}</span>{/if}
      </span>
      <Chevron class="item-chevron" />
    </button>
    {@render control()}
  </div>
{:else if open}
  <button class="item" data-row={row || undefined} onclick={open}>
    {#if lead}{@render lead()}{/if}
    <span class="item-text">
      <span class="item-name" data-name>{#if glyph}{@render glyph()}{/if}{name}</span>
      {#if value}<span class="item-value" data-about>{value}</span>{/if}
    </span>
    <Chevron class="item-chevron" />
  </button>
{:else}
  <label class="item" data-row={row || undefined}>
    {#if lead}{@render lead()}{/if}
    <span class="item-text">
      <span class="item-name" data-name>{#if glyph}{@render glyph()}{/if}{name}</span>
      {#if value}<span class="item-value" data-about>{value}</span>{/if}
    </span>
    {#if control}{@render control()}{/if}
  </label>
{/if}
