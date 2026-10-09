<script lang="ts">
  // One choice out of a few, all of them in view. A choice that is a way of writing something
  // shows it written that way beside its name.

  interface Props {
    choices: { value: string; label: string; example?: string }[];
    chosen: string;
    /** What the choice is about, for a screen reader. */
    label: string;
    change: (value: string) => void;
  }

  let { choices, chosen, label, change }: Props = $props();
</script>

<div class="segments" role="radiogroup" aria-label={label}>
  {#each choices as choice (choice.value)}
    <button
      type="button"
      role="radio"
      class:on={chosen === choice.value}
      aria-checked={chosen === choice.value}
      data-choice={choice.value}
      onclick={() => change(choice.value)}
      >{#if choice.example}<span class="segment-example ipa" aria-hidden="true">{choice.example}</span
        >{/if}{choice.label}</button
    >
  {/each}
</div>
