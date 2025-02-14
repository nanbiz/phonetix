<script lang="ts">

  let extension_enabled = $state(true);
  let website_enabled = $state(true);
  let hostname = $state("");
  let faviconUrl = $state("");

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", fetchTabDetails);
  } else {
    fetchTabDetails();
  }

  function fetchTabDetails() {
    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (tabs.length > 0) {
        const url = tabs[0].url || "";
        try {
          hostname = new URL(url).hostname;
        } catch (e) {
          hostname = "";
        }
        faviconUrl = tabs[0].favIconUrl || "";
      }
    });
  }

  $effect(() => {
    chrome.runtime.sendMessage({ type: "enabled", payload: extension_enabled && website_enabled });
  });
</script>

<div class="flex flex-col gap-4 items-start w-full px-4 py-2">
  <div class="flex justify-between items-center w-full">
    <h1 class="text-4xl font-bold">Phonetix</h1>
    <input type="checkbox" class="toggle toggle-primary toggle-lg" bind:checked={extension_enabled} />
  </div>

  {#if hostname}
    <div class="flex items-center gap-4 w-full">
      <img
        class="w-8 h-8 object-contain flex-none"
        src={faviconUrl}
        alt="website icon"
        onerror={function (this: HTMLImageElement) {
          this.style.visibility = "hidden";
        }}
      />

      <span class="text-lg font-medium flex-1 min-w-0 truncate">
        {hostname}
      </span>

      <input type="checkbox" class="toggle toggle-primary toggle-md flex-none" disabled={!extension_enabled} bind:checked={website_enabled} />
    </div>
  {/if}
</div>
