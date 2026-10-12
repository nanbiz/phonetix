<script lang="ts">
  // On or off. A checkbox drawn as a switch, so the keyboard and a screen reader still know
  // what it is.

  interface Props {
    on: boolean;
    /** What it switches, for a reader who cannot see the row it sits in. */
    label: string;
    change: (on: boolean) => void;
    /** Shown off and not pressed, where what it switches is part of something switched off. */
    disabled?: boolean;
    /** Kept as it is and not pressed, where turning it would leave nothing on. */
    locked?: boolean;
  }

  let { on, label, change, disabled = false, locked = false }: Props = $props();
</script>

<input
  type="checkbox"
  class="switch"
  aria-label={label}
  checked={on && !disabled}
  disabled={disabled || locked}
  onchange={(event) => change(event.currentTarget.checked)}
/>
