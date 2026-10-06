<script lang="ts">
  // A choice out of a list, each option a row of the card it is drawn on, the ones in force
  // ticked. One value is one choice; a list of them is any number, each row a box of its own.
  import Check from 'virtual:icons/pixelarticons/check';

  interface Props {
    options: { value: string; label: string; about?: string }[];
    chosen: string | string[];
    change: (value: string) => void;
  }

  let { options, chosen, change }: Props = $props();

  let many = $derived(Array.isArray(chosen));
  const on = (value: string) => (Array.isArray(chosen) ? chosen.includes(value) : chosen === value);
</script>

<div role={many ? 'group' : 'radiogroup'}>
  {#each options as option (option.value)}
    <button
      class="item option"
      class:on={on(option.value)}
      role={many ? 'checkbox' : 'radio'}
      aria-checked={on(option.value)}
      data-choice={option.value}
      onclick={() => change(option.value)}
    >
      <span class="item-text">
        <span class="item-name">{option.label}</span>
        {#if option.about}<span class="item-value">{option.about}</span>{/if}
      </span>
      {#if on(option.value)}<Check class="option-check" />{/if}
    </button>
  {/each}
</div>
