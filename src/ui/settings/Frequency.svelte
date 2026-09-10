<script lang="ts">
  // How much of the page is transcribed, on one bar.
  //
  // The bar is the control a reader comes back to, so it is a row of its own rather than one
  // setting among a dozen, and what it is set to is said in words beside its name: "one word
  // in 12" is a thing a reader can picture, a slider position is not.
  //
  // The positions are the core's own curve. Frequency is felt in ratios rather than in N - the
  // step from 1-in-2 to 1-in-4 is a world apart and 1-in-40 to 1-in-42 is nothing - so the
  // spacing is bent toward geometric, and it is bent in the one place both platforms read.
  import Slider from '@/ui/controls/Slider.svelte';

  interface Props {
    /** What each position of the bar means, densest last, as the core decides it. */
    curve: number[];
    density: number;
    change: (density: number) => void;
  }

  let { curve, density, change }: Props = $props();

  /** Where on the bar this density sits, which is what the slider is set to. */
  let position = $derived(
    curve.length === 0
      ? 0
      : curve.reduce(
          (best, at, i) =>
            Math.abs(at - density) < Math.abs(curve[best] - density) ? i : best,
          0
        )
  );

  let says = $derived(
    density <= 1 ? 'every word' : `one word in ${density} · ${Math.round(100 / density)}%`
  );
</script>

<div class="frequency" data-row="density">
  <div class="f-head">
    <span class="f-name" data-name>How often</span>
    <span class="f-says" data-about>{says}</span>
  </div>
  <Slider
    value={position}
    max={Math.max(0, curve.length - 1)}
    label="how often"
    ends={['a few words', 'every word']}
    change={(at) => change(curve[at] ?? density)}
  />
</div>
