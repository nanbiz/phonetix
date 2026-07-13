<script lang="ts">
  import {getCurrentTabId, sendMessage} from "@/lib/messaging"

  let hostname = $state("");
  let faviconUrl = $state("");
  let extension_enabled = $state(false);
  let current_website_enabled = $state(false);
  let stateInitialized = $state(false);

  (async () => {
    try {
      const extState = await storage.getItem<string>('local:extension_enabled');
      extension_enabled = extState ? JSON.parse(extState) : true;

      // Use the promise-based `browser` API: Firefox's `chrome.*` is callback-only,
      // so awaiting it yields nothing and the per-site row would never render.
      const tabs = await browser.tabs.query({ active: true, currentWindow: true });
      if (tabs[0]?.url) {
        const url = new URL(tabs[0].url);
        hostname = url.hostname;
        faviconUrl = tabs[0].favIconUrl || "";

        const websiteState = await storage.getItem<string>('local:websites_enabled');
        const parsedWebsites: Record<string, boolean> = websiteState ? JSON.parse(websiteState) : {};
        current_website_enabled = hostname in parsedWebsites ? parsedWebsites[hostname] : true;
      }
    } catch (error) {
      console.error("Initialization error:", error);
      extension_enabled = true;
      current_website_enabled = true;
    }
    stateInitialized = true;
  })();

  $effect(() => {
    const saveExtensionState = async (enabled: boolean) => {
      try {
        await storage.setItem('local:extension_enabled', JSON.stringify(enabled));
      } catch (error) {
        console.error("Error saving extension state:", error);
      }
    };
    saveExtensionState(extension_enabled);
  });

  $effect(() => {
    const saveWebsiteState = async (current_website_enabled: boolean) => {
      if (!hostname) return;

      try {
        const websites = await storage.getItem<string>('local:websites_enabled');
        const parsedWebsites: Record<string, boolean> = websites ? JSON.parse(websites) : {};
        parsedWebsites[hostname] = current_website_enabled;
        await storage.setItem('local:websites_enabled', JSON.stringify(parsedWebsites));
      } catch (error) {
        console.error("Error saving website state:", error);
      }
    };
    saveWebsiteState(current_website_enabled);
  });

  $effect(() => {
    (async () => sendMessage('extensionToggled', extension_enabled && current_website_enabled, await getCurrentTabId()))();
  })
</script>

<div class="flex flex-col gap-4 items-start w-full px-4 py-2">
  <div class="flex justify-between items-center w-full">
    <h1 class="text-4xl font-bold">Phonetix</h1>
    {#if stateInitialized}
    <input 
      type="checkbox" 
      class="toggle toggle-primary toggle-lg" 
      bind:checked={extension_enabled} 
    />
    {/if}
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
      {#if stateInitialized}
      <input 
        type="checkbox" 
        class="toggle toggle-primary toggle-md flex-none" 
        disabled={!extension_enabled} 
        bind:checked={current_website_enabled} 
      />
      {/if}
    </div>
  {/if}
</div>
