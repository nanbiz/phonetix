<script lang="ts">
  // One choice out of many: a real select, so the browser's own list, its keyboard and its
  // search-by-typing come with it. A menu cut out of divs has none of that and has to be
  // taught all of it.

  interface Props {
    /** What each option says and the value it stands for, in the order to show them. */
    options: { value: string; label: string }[];
    chosen: string;
    label: string;
    change: (value: string) => void;
  }

  let { options, chosen, label, change }: Props = $props();
</script>

<!-- The frame carries the caret, because a select cannot draw one of its own: what the reader
     presses is still the menu, and the browser opens it the way it opens any other. -->
<span class="select">
  <select
    aria-label={label}
    value={chosen}
    onchange={(event) => change((event.currentTarget as HTMLSelectElement).value)}
  >
    {#each options as option (option.value)}
      <option value={option.value}>{option.label}</option>
    {/each}
  </select>
</span>
