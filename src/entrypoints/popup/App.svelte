<script lang="ts">
  import Heart from "virtual:icons/pixelarticons/heart";

  import Toggles from "./components/Toggles.svelte";
  import Settings from "./components/Settings.svelte";

  // The running version, read from the manifest so it is always the installed build.
  const version = chrome.runtime.getManifest().version;
</script>

<!-- Natural flow: the popup sizes its height to the current screen (see app.css),
     so the column just stacks — header, the screen, then the footer. -->
<main class="flex flex-col">
  <Toggles />

  <div class="px-4 pb-2">
    <Settings />
  </div>

  <div class="flex items-center justify-center gap-1.5 py-2 text-sm">
    Made with
    <Heart class="size-5 text-red-500" />
    <span class="text-gray-500">· v{version}</span>
  </div>
</main>
