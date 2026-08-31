<script lang="ts">

  /** The extension switch is the default for any site you have not decided on.
   *  The site switch is a decision about this site, and it wins — so a site can
   *  be on while the default is off, and the other way round. A site with no
   *  decision simply follows the default, and toggling it back to the default
   *  drops the decision again. */
  let hostname = $state("");
  let faviconUrl = $state("");
  let defaultEnabled = $state(true);
  let siteOverride = $state<boolean | null>(null);
  let stateInitialized = $state(false);

  let siteEnabled = $derived(siteOverride ?? defaultEnabled);

  const logoUrl = chrome.runtime.getURL('icon/48.png');

  (async () => {
    try {
      const extState = await storage.getItem<string>('local:extension_enabled');
      defaultEnabled = extState ? JSON.parse(extState) : true;

      // Firefox's `chrome.*` is callback-only, so awaiting it yields nothing and
      // the per-site row would never render. `browser.*` is the promise-based API.
      const tabs = await browser.tabs.query({ active: true, currentWindow: true });
      if (tabs[0]?.url) {
        const url = new URL(tabs[0].url);
        hostname = url.hostname;
        faviconUrl = tabs[0].favIconUrl || "";

        const websiteState = await storage.getItem<string>('local:websites_enabled');
        const sites: Record<string, boolean> = websiteState ? JSON.parse(websiteState) : {};
        siteOverride = hostname in sites ? sites[hostname] : null;
      }
    } catch (error) {
      console.error("Initialization error:", error);
    }
    stateInitialized = true;
  })();

  async function setDefault(enabled: boolean) {
    defaultEnabled = enabled;
    await storage.setItem('local:extension_enabled', JSON.stringify(enabled));
  }

  async function setSite(enabled: boolean) {
    // Matching the default is not a decision, it is the absence of one.
    siteOverride = enabled === defaultEnabled ? null : enabled;

    const websiteState = await storage.getItem<string>('local:websites_enabled');
    const sites: Record<string, boolean> = websiteState ? JSON.parse(websiteState) : {};
    if (siteOverride === null) delete sites[hostname];
    else sites[hostname] = siteOverride;
    await storage.setItem('local:websites_enabled', JSON.stringify(sites));
  }
</script>

<div class="flex w-full flex-col gap-3 px-4 py-3">
  <div class="flex w-full items-center justify-between gap-3">
    <div class="flex min-w-0 items-center gap-2.5">
      <img class="size-9 flex-none rounded-lg" src={logoUrl} alt="" />
      <div class="min-w-0">
        <h1 class="text-3xl font-bold leading-none">Phonetix</h1>
        <p class="mt-0.5 text-xs text-gray-500">Default for sites you have not set</p>
      </div>
    </div>
    {#if stateInitialized}
      <input
        type="checkbox"
        class="toggle toggle-primary toggle-lg flex-none"
        checked={defaultEnabled}
        onchange={(e) => setDefault((e.currentTarget as HTMLInputElement).checked)}
      />
    {/if}
  </div>

  {#if hostname}
    <div class="flex w-full items-center gap-3">
      <img
        class="h-7 w-7 flex-none object-contain"
        src={faviconUrl}
        alt=""
        onerror={function (this: HTMLImageElement) {
          this.style.visibility = "hidden";
        }}
      />

      <div class="min-w-0 flex-1">
        <span class="block truncate text-sm font-medium">{hostname}</span>
        <span class="text-xs text-gray-500">
          {siteOverride === null ? 'following the default' : siteOverride ? 'always on here' : 'always off here'}
        </span>
      </div>

      {#if stateInitialized}
        <input
          type="checkbox"
          class="toggle toggle-primary toggle-md flex-none"
          checked={siteEnabled}
          onchange={(e) => setSite((e.currentTarget as HTMLInputElement).checked)}
        />
      {/if}
    </div>
  {/if}
</div>
