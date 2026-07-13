<script lang="ts">
  // Every choice visible at once: with a handful of options there is nothing to
  // collapse, and a list that opens cannot be clipped by the popup's box.
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
  <div class="flex gap-1 rounded-lg border border-gray-700 bg-gray-800 p-1">
    {#each Object.entries(elements) as [key, label] (key)}
      <button
        type="button"
        class="min-w-0 flex-1 truncate rounded-md px-2 py-1.5 text-xs font-medium transition-colors
               {selectedElement === key
                 ? 'bg-blue-600 text-white'
                 : 'text-gray-400 hover:bg-gray-700 hover:text-gray-200'}"
        title={label}
        onclick={() => onElementChange(key)}
      >
        {label}
      </button>
    {/each}
  </div>
</div>
