<script lang="ts">
  // A native select, because the popup is a fixed-size scrolling box: an option
  // list built out of divs is clipped by that box, while the browser renders a
  // select's list as its own window, free of the popup's bounds.
  let {
    topic,
    elements,
    selectedElement,
    onElementChange,
  }: {
    topic: string;
    selectedElement: string;
    elements: { [key: string]: string };
    onElementChange: (value: string) => void;
  } = $props();
</script>

<div class="flex flex-col gap-1">
  <h2 class="text-sm font-medium text-gray-300">{topic}</h2>
  <select
    class="w-full appearance-none rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm font-medium text-white hover:border-blue-400 focus:border-blue-400 focus:outline-none"
    value={selectedElement}
    onchange={(e) => onElementChange((e.currentTarget as HTMLSelectElement).value)}
  >
    {#each Object.entries(elements) as [key, label] (key)}
      <option value={key}>{label}</option>
    {/each}
  </select>
</div>
