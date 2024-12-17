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
    chrome.runtime.sendMessage({type: "enabled", payload: extension_enabled && website_enabled})
  })
</script>

<div class="grid grid-cols-5 gap-2 place-items-center min-w-full">

<h1 class="text-6xl m-5 col-span-4">Phonetix</h1>
<input type="checkbox" class="toggle toggle-primary toggle-lg" bind:checked={extension_enabled} />
{#if hostname}
  <img
    class="object-contain h-10 justify-self-end"
    src={faviconUrl}
    alt="website icon"
    onerror={function (this: HTMLImageElement) {
      this.style.visibility = "hidden";
    }}
  />

  <h1 class="text-xl text-ellipsis overflow-hidden max-w-[50vw] justify-self-stretch col-span-3" style="font-size: 2">{hostname}</h1>

  <input type="checkbox" class="toggle toggle-primary toggle-m" disabled={!extension_enabled} bind:checked={website_enabled} />
  <div class="website_toggle"></div>
{/if}
  </div>