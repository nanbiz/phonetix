<script lang="ts">
  // On or off. A checkbox drawn as a switch, so the keyboard and a screen reader still know
  // what it is.

  interface Props {
    on: boolean;
    /** What it switches, for a reader who cannot see the row it sits in. */
    label: string;
    change: (on: boolean) => void;
  }

  let { on, label, change }: Props = $props();
</script>

<input
  type="checkbox"
  class="switch"
  aria-label={label}
  checked={on}
  onchange={(event) => change(event.currentTarget.checked)}
/>
