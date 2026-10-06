<script lang="ts">
  // How many of the page's words are replaced by how they are said, on one bar.
  //
  // What it is set to is said in words beside its name: "one in 12" is a thing a reader can
  // picture, a slider position is not. The positions are the core's own curve, bent toward
  // geometric because frequency is felt in ratios: 1 in 2 to 1 in 4 is a world apart, 1 in 40
  // to 1 in 42 is nothing.
  import { ROWS, SAYS } from '@/data/wording';

  interface Props {
    /** What each position of the bar means, densest last, as the core decides it. */
    curve: number[];
    density: number;
    change: (density: number) => void;
  }

  let { curve, density, change }: Props = $props();

  /** The last position meaning this density: the curve ends in a run of positions that all
   *  mean every word, and the first of them stopped the bar short of its end. */
  let position = $derived(
    curve.length === 0
      ? 0
      : curve.reduce(
          (best, at, i) => (Math.abs(at - density) <= Math.abs(curve[best] - density) ? i : best),
          0
        )
  );

  let says = $derived(
    density <= 1 ? SAYS['every-word'] : SAYS['one-in'].replace('%s', String(density))
  );
</script>

<div class="item wide density" data-row="density">
  <div class="density-head">
    <span class="item-name" data-name>{ROWS.replaced.name}</span>
    <span class="density-says" data-about>{says}</span>
  </div>
  <input
    class="range"
    type="range"
    min="0"
    max={Math.max(0, curve.length - 1)}
    value={position}
    aria-label={ROWS.replaced.name}
    disabled={curve.length === 0}
    style="--at: {curve.length > 1 ? (position / (curve.length - 1)) * 100 : 0}%"
    oninput={(event) => change(curve[Number(event.currentTarget.value)] ?? density)}
  />
</div>
