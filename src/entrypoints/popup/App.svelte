<script lang="ts">
  import Heart from "virtual:icons/pixelarticons/heart";

  import Toggles from "./components/Toggles.svelte";
  import Settings from "./components/Settings.svelte";
  
</script>
 
<main class="flex flex-col items-center gap-6">
  <Toggles />
  <Settings/>

  <div style="min-height: 13em;"></div>
  <div class="inline-flex gap-1.5 mb-4 items-center">
    Made with
    <Heart class="size-6 text-red-500" />
    .
  </div>
</main>
