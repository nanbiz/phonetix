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
    /** What a reader would otherwise need that sentence for, behind a question mark: there for
     *  whoever wants it, taking no room from the row. */
    hint?: string;
    /** What the row is set to, said beside the name rather than by the control. */
    says?: string;
    /** Anything else the row identifies itself by, such as which language it is about. */
    marks?: Record<string, string>;
    /** The control itself. */
    control?: Snippet;
    /** A control that needs the whole width, such as a bar. */
    wide?: Snippet;
  }

  let { name, row = '', about = '', hint = '', says = '', marks = {}, control, wide }:
    Props = $props();

  /** Whether the explanation behind the question mark is showing. A title attribute is a
   *  hover, and a finger cannot hover: on a phone the mark was a decoration that answered
   *  nothing at all. */
  let asked = $state(false);
</script>

<div class="row" data-row={row || undefined} {...marks}>
  <!-- A row on a screen named after it says its name once: the screen's own title is the
       name, and repeating it under itself is the title twice. -->
  {#if name}
    <span class="r-name" data-name>
      {name}{#if hint}<button
          class="hint"
          title={hint}
          aria-label={hint}
          aria-expanded={asked}
          onclick={() => (asked = !asked)}
        >?</button>{/if}
    </span>
  {/if}
  {#if says}
    <span class="r-act" data-about>{says}</span>
  {/if}
  {#if about}
    <span class="r-sub" data-about={says ? undefined : ''}>{about}</span>
  {/if}
  {#if hint && asked}
    <span class="r-sub asked">{hint}</span>
  {/if}
  {#if control && !says}
    <span class="r-act">{@render control()}</span>
  {/if}
  {#if wide}
    <span class="r-wide">{@render wide()}</span>
  {/if}
</div>
