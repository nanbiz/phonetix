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
    /** Nothing to set here yet, because what it sets only applies once something else is on.
     *  Shown rather than hidden, so the reader can see the screen exists and what turns it
     *  on. */
    disabled?: boolean;
    /** A row belonging to the switch above it rather than standing beside it: the same row,
     *  set in from the edge and in the smaller type the rest of a row's detail is in. */
    small?: boolean;
    open: () => void;
  }

  let { name, about = '', row = '', disabled = false, small = false, open }: Props = $props();
</script>

<button class="nav" class:small data-row={row || undefined} {disabled} onclick={open}>
  <span class="n-name" data-name>{name}</span>
  {#if about}<span class="n-sub" data-about>{about}</span>{/if}
  <span class="chev" aria-hidden="true">›</span>
</button>
