<script lang="ts">
  import Chevron from "virtual:icons/line-md/chevron-up";

  let {
    topic,
    headEntry,
    elements,
    selectedElement,
    onElementChange,
  }: {
    topic: string;
    headEntry: string;
    selectedElement: string;
    elements: { [key: string]: string };
    onElementChange: (value: string) => void;
  } = $props();

  let expanded = $state(false);
</script>

<div class="flex flex-col gap-2">
  <h1 class="text-lg font-medium text-white">{topic}</h1>
  <div class="relative group" role="button" tabindex="0">
    <button class="w-full font-medium px-4 py-3 rounded-lg border hover:border-blue-400 flex justify-between items-center" onclick={() => (expanded = !expanded)}>
      <span class="truncate">
        {headEntry}
      </span>
      <Chevron class="h-5 w-5 transform transition-transform {expanded ? '' : 'rotate-180'}" />
    </button>

    {#if expanded}
      <div
        class="absolute z-10 w-full mt-2 origin-top rounded-lg shadow-lg border border-gray-200 overflow-hidden">
        {#each Object.entries(elements) as [element_key, element_value]}
          <button
            class="w-full px-4 py-3 text-left hover:bg-gray-700 transition-colors
                     {selectedElement === element_key ? 'bg-gray-700 ' : 'bg-gray-800'}"
            onclick={() => {
              onElementChange(element_key);
              expanded = false;
            }}
          >
            {element_value}
          </button>
        {/each}
      </div>
    {/if}
  </div>
</div>
