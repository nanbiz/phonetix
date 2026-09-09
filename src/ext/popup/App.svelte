<script lang="ts">
  // The settings view: what the reader has chosen, and what the host has.
  //
  // It reads the settings and the host's state once, and writes a setting the moment it is
  // changed: every surface watches the same keys, so a page annotates itself again without
  // being told by this one.
  import Settings from '@/ui/settings/Settings.svelte';
  import { current, set, type Settings as Chosen } from '@/settings';
  import { sendMessage } from '@/host/messages';
  import '@/ui/tokens.css';
  import '@/ui/settings/settings.css';

  let settings = $state<Chosen | null>(null);
  let curve = $state<number[]>([]);
  let packs = $state<{ held: string[]; open: string[] }>({ held: [], open: [] });

  async function load() {
    settings = await current();
    // The bar's meaning and the machine's dictionaries both come from the host: a settings
    // view that decided either of them itself would be a second opinion.
    curve = await sendMessage('curve', {}).catch(() => []);
    packs = await sendMessage('packs', {}).catch(() => ({ held: [], open: [] }));
  }

  function change<K extends keyof Chosen>(name: K, value: Chosen[K]) {
    if (settings) settings = { ...settings, [name]: value };
    void set(name, value);
  }

  void load();

  // The theme the tokens are keyed by. A settings view is the extension's own surface, so it
  // follows the reader's browser rather than a page.
  const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
</script>

<main class="theme-paper mode-{dark ? 'dark' : 'light'}">
  {#if settings}
    <Settings {settings} {curve} {packs} {change} />
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
