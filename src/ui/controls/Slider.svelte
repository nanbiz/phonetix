<script lang="ts">
  // A range, with what its two ends mean written under it.
  //
  // The ends are named because a bar with no ends is a bar whose direction a reader has to
  // discover by dragging it.

  interface Props {
    value: number;
    min?: number;
    max: number;
    step?: number;
    label: string;
    /** What the left and right ends of the bar mean. */
    ends?: [string, string];
    change: (value: number) => void;
  }

  let { value, min = 0, max, step = 1, label, ends, change }: Props = $props();

  /**
   * How much of the bar is filled, as a share of it.
   *
   * Drawn here rather than left to the browser: a native range fills its track up to the
   * middle of the handle, so a bar dragged all the way to the end is still short of the end
   * by half a handle - it says "every word" above a bar that is visibly not full.
   */
  let filled = $derived(max > min ? ((value - min) / (max - min)) * 100 : 0);
</script>

<input
  class="slider"
  type="range"
  style="--filled: {filled}%"
  {min}
  {max}
  {step}
  {value}
  aria-label={label}
  oninput={(event) => change(Number((event.currentTarget as HTMLInputElement).value))}
/>
{#if ends}
  <span class="ticks"><span>{ends[0]}</span><span>{ends[1]}</span></span>
{/if}
