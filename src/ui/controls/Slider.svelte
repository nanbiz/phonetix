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
</script>

<input
  class="slider"
  type="range"
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
