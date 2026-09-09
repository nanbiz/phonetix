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
  import '@/ui/tokens.css';
  import '@/ui/settings/settings.css';

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

  async function load() {
    settings = await current();
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    site = tab?.url ? new URL(tab.url).hostname : '';
    // The bar's meaning and the machine's dictionaries both come from the host: a settings
    // view that decided either of them itself would be a second opinion.
    curve = await sendMessage('curve', {}).catch(() => []);
    packs = await sendMessage('packs', {}).catch(() => ({ held: [], open: [], offered: [] }));
  }

  function change<K extends keyof Chosen>(name: K, value: Chosen[K]) {
    if (settings) settings = { ...settings, [name]: value };
    void set(name, value);
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

  // The theme the tokens are keyed by. A settings view is the extension's own surface, so it
  // follows the reader's browser rather than a page.
  const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
</script>

<main class="theme-paper mode-{dark ? 'dark' : 'light'}">
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
      onSite={(on) => {
        if (settings) void setSite(settings, site, on).then(load);
      }}
    />
  {/if}
</main>

<style>
  main {
    background: var(--color-page-bg);
    padding: var(--space-3);
    min-width: 340px;
    font-family: var(--font-ui);
    font-size: var(--font-size-body);
    color: var(--color-ink);
  }
</style>
