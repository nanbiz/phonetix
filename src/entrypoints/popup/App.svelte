<script lang="ts">
  import Heart from "virtual:icons/pixelarticons/heart";

  import Toggles from "./components/Toggles.svelte";
  import Settings from "./components/Settings.svelte";
</script>

<!-- Fixed-height column: the header and footer stay put and only the middle
     scrolls, so the popup window keeps one size whatever opens inside it. -->
<main class="flex h-full flex-col">
  <Toggles />

  <div class="min-h-0 flex-1 overflow-y-auto px-4 pb-2">
    <Settings />
  </div>

  <div class="inline-flex shrink-0 items-center justify-center gap-1.5 py-2 text-sm">
    Made with
    <Heart class="size-5 text-red-500" />
    .
  </div>
</main>
