<script lang="ts">
  // One choice out of a list, each option a row of the card it is drawn on, the one in force
  // ticked.
  import Check from 'virtual:icons/pixelarticons/check';

  interface Props {
    options: { value: string; label: string; about?: string }[];
    chosen: string;
    change: (value: string) => void;
  }

  let { options, chosen, change }: Props = $props();
</script>

<div role="radiogroup">
  {#each options as option (option.value)}
    <button
      class="item option"
      class:on={option.value === chosen}
      role="radio"
      aria-checked={option.value === chosen}
      data-choice={option.value}
      onclick={() => change(option.value)}
    >
      <span class="item-text">
        <span class="item-name">{option.label}</span>
        {#if option.about}<span class="item-value">{option.about}</span>{/if}
      </span>
      {#if option.value === chosen}<Check class="option-check" />{/if}
    </button>
  {/each}
</div>
