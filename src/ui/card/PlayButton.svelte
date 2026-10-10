<script lang="ts">
  // The round button that plays a word or a sound.
  //
  // Filled with the accent rather than outlined, because it is the one thing on the card
  // that does something to the world, and a reader looking for it should not have to find
  // it. The triangle is nudged right inside its circle: centred by its bounding box it reads
  // as sitting left of centre. While the sound plays it shows the sound instead, until the
  // sound has been heard; a second press while it plays does nothing.
  import Play from 'virtual:icons/lucide/play';
  import Playing from 'virtual:icons/lucide/audio-lines';

  interface Props {
    /** What pressing it will play, for a reader who cannot see the card. */
    label: string;
    /** Play it; a promise resolves once it has been heard. */
    onplay?: () => Promise<void> | void;
  }

  let { label, onplay }: Props = $props();
  let playing = $state(false);

  async function press(): Promise<void> {
    if (playing) return;
    const going = onplay?.();
    if (!going) return;
    playing = true;
    try {
      await going;
    } catch {
      // A sound that could not be played has stopped playing all the same.
    } finally {
      playing = false;
    }
  }
</script>

<button class="audio{playing ? ' playing' : ''}" aria-label={label} onclick={() => void press()}>
  {#if playing}<Playing />{:else}<Play />{/if}
</button>
