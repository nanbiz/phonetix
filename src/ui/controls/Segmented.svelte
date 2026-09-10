<script lang="ts">
  // One choice out of a few, all of them visible.
  //
  // Buttons in a row rather than a menu: with four or five short answers, showing them is
  // faster to read than opening something to find out what they are.

  interface Props<T extends string> {
    /** What each segment says and the value it stands for. */
    choices: { value: T; label: string }[];
    chosen: T;
    change: (value: T) => void;
  }

  let { choices, chosen, change }: Props<string> = $props();
</script>

<span class="seg">
  {#each choices as choice (choice.value)}
    <button
      type="button"
      class={chosen === choice.value ? 'on' : ''}
      data-choice={choice.value}
      aria-pressed={chosen === choice.value}
      onclick={() => change(choice.value)}
    >{choice.label}</button>
  {/each}
</span>
