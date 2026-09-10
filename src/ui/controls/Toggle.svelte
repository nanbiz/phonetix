<script lang="ts">
  // A switch: on or off, and nothing in between.
  //
  // A checkbox wearing a switch rather than a div taught to behave like one, so the keyboard,
  // the screen reader and the browser's own focus ring come with it. The look is the design
  // page's `.toggle`, which is where the phone's switch comes from too.

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
  class="toggle"
  aria-label={label}
  checked={on}
  onchange={(event) => change((event.currentTarget as HTMLInputElement).checked)}
/>
