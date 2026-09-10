<script lang="ts">
  // A small square button that is a mark rather than a word.
  //
  // The mark is a string of SVG from the icon set that drew it, so a button is one element and
  // one rule wherever it appears.

  interface Props {
    /** The mark, as the set's own SVG. */
    icon: string;
    /** What it does, said in words for a reader who cannot see the mark. */
    label: string;
    /** The name it reports itself under, so a check can tell two buttons apart. */
    name?: string;
    press: () => void;
  }

  let { icon, label, name = '', press }: Props = $props();
</script>

<button
  class="icon-btn"
  title={label}
  aria-label={label}
  data-does={name || undefined}
  onclick={press}
>{@html icon}</button>
