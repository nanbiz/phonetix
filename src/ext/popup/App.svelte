<script lang="ts">
  // The settings view: what the reader has chosen, and what the host has.
  //
  // It reads the settings and the host's state once, and writes a setting the moment it is
  // changed: every surface watches the same keys, so a page annotates itself again without
  // being told by this one.
  import Settings from '@/ui/settings/Settings.svelte';
  import { current, set, setSite, type Settings as Chosen } from '@/settings';
  import { sendMessage } from '@/host/messages';
  import type { Offered } from '@/host/packs';

  let settings = $state<Chosen | null>(null);
  let curve = $state<number[]>([]);
  let packs = $state<{ held: string[]; open: string[]; offered: Offered[] }>({
    held: [],
    open: [],
    offered: [],
  });
  /** Which dictionary is being fetched, so its row says so rather than looking dead. */
  let fetching = $state<string | null>(null);
  /** The site the reader is looking at, so it can be switched off on its own. */
  let site = $state('');
  /** What the page being read is in, which decides which accents there are to choose. */
  let pageLang = $state('');
  /** The site's own mark, which the browser has already fetched for the tab. */
  let siteIcon = $state('');

  /** This extension's mark and the build a reader is looking at, from the manifest. */
  const icon = chrome.runtime.getURL('icon/48.png');
  const version = chrome.runtime.getManifest().version;

  async function load() {
    settings = await current();
    // The page the reader is on, which is not always the active tab: the settings view can
    // itself be open as a tab, and a view that then described itself would offer a site
    // switch for the extension and accents for nothing.
    const active = await chrome.tabs.query({ active: true, currentWindow: true });
    const readable = (url?: string) => Boolean(url && /^https?:/.test(url));
    let tab = active.find((it) => readable(it.url));
    if (!tab) {
      const all = await chrome.tabs.query({});
      tab = all
        .filter((it) => readable(it.url))
        .sort((a, b) => (b.lastAccessed ?? 0) - (a.lastAccessed ?? 0))[0];
    }
    site = tab?.url ? new URL(tab.url).hostname : '';
    siteIcon = tab?.favIconUrl ?? '';
    // Asked of the page rather than guessed: it is the one that read itself.
    pageLang = tab?.id
      ? await chrome.tabs
          .sendMessage(tab.id, { phonetix: 'pageLanguage', data: {} })
          .then((r: { ok?: string } | undefined) => r?.ok ?? '')
          .catch(() => '')
      : '';
    // The bar's meaning and the machine's dictionaries both come from the host: a settings
    // view that decided either of them itself would be a second opinion.
    curve = await sendMessage('curve', {}).catch(() => []);
    packs = await sendMessage('packs', {}).catch(() => ({ held: [], open: [], offered: [] }));
  }

  function change<K extends keyof Chosen>(name: K, value: Chosen[K]) {
    if (settings) settings = { ...settings, [name]: value };
    void set(name, value).then(() => {
      // A reader who has just said where their dictionaries live means now: without this the
      // list they were typing the address for stays empty until the popup is opened again.
      if (name === 'host') return load();
    });
  }

  async function get(lang: string) {
    fetching = lang;
    await sendMessage('getPack', { lang }).catch(() => null);
    fetching = null;
    await load();
  }

  async function forget(lang: string) {
    await sendMessage('forgetPack', { lang }).catch(() => false);
    await load();
  }

  void load();

</script>

<!-- No heading of its own: the switchboard at the top of the view names the product beside
     the switch that answers what a reader came to ask. -->
<main class="panel">
  {#if settings}
    <Settings
      {settings}
      {curve}
      {packs}
      {change}
      {get}
      {forget}
      {fetching}
      {site}
      {pageLang}
      {icon}
      {siteIcon}
      {version}
      onSite={(on) => {
        if (settings) void setSite(settings, site, on).then(load);
      }}
    />
  {/if}
</main>


