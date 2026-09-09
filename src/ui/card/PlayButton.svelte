<script lang="ts">
  // The round button that plays a word or a sound.
  //
  // Filled with the accent rather than outlined, because it is the one thing on the card
  // that does something to the world, and a reader looking for it should not have to find
  // it. The triangle is nudged right inside its circle: centred by its bounding box it reads
  // as sitting left of centre.
  interface Props {
    /** What pressing it will play, for a reader who cannot see the card. */
    label: string;
    onplay?: () => void;
  }

  let { label, onplay }: Props = $props();
</script>

<button class="audio" aria-label={label} onclick={() => onplay?.()}>
  <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 2l14 8-14 8z" /></svg>
</button>
