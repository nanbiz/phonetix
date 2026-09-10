<script lang="ts">
  // A row that opens a screen of its own, and says what is on it.
  //
  // The settings a reader changes once - which accent, which dictionaries - are behind one of
  // these rather than in front of the two they change daily.

  interface Props {
    name: string;
    /** What is set there now, so the screen behind it does not have to be opened to find out. */
    about?: string;
    /** The name this row reports itself under. */
    row?: string;
    open: () => void;
  }

  let { name, about = '', row = '', open }: Props = $props();
</script>

<button class="nav" data-row={row || undefined} onclick={open}>
  <span class="n-name" data-name>{name}</span>
  {#if about}<span class="n-sub" data-about>{about}</span>{/if}
  <span class="chev" aria-hidden="true">›</span>
</button>
