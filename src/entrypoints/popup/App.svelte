<script lang="ts">
  import Heart from "virtual:icons/pixelarticons/heart";

  import Toggles from "./components/Toggles.svelte";
  import Settings from "./components/Settings.svelte";

  // The running version, read from the manifest so it is always the installed build.
  const version = chrome.runtime.getManifest().version;
</script>

<!-- Fixed-height column: the header and footer stay put and only the middle
     scrolls, so the popup window keeps one size whatever opens inside it. -->
<main class="flex h-full flex-col">
  <Toggles />

  <div class="min-h-0 flex-1 overflow-y-auto px-4 pb-2">
    <Settings />
  </div>

  <div class="flex shrink-0 items-center justify-center gap-1.5 py-2 text-sm">
    Made with
    <Heart class="size-5 text-red-500" />
    <span class="text-gray-500">· v{version}</span>
  </div>
</main>
