<script lang="ts">
  // The phone's settings screen, which is the product's settings screen.
  //
  // The same component the extension's popup draws, filled from the app instead of from a
  // browser: what the reader has chosen, what the app has been allowed to do, and which
  // dictionaries are here. Nothing about the layout of this screen is decided twice.
  import Settings from '@/ui/settings/Settings.svelte';
  import { darkSide, DEFAULTS, type Settings as Chosen } from '@/settings/shape';
  import { themeOf } from '@/ui/theme';
  import type { Offered } from '@/host/packs';
  import { ask, whenChanged } from './bridge';

  let settings = $state<Chosen | null>(null);
  /** Which screen the reader is on, told to the app so the device's own way back leaves that
   *  screen rather than the app, and taken from it when they use it. */
  let view = $state('main');
  let curve = $state<number[]>([]);
  let packs = $state<{ held: string[]; open: string[]; offered: Offered[] }>({
    held: [],
    open: [],
    offered: [],
  });
  let fetching = $state<string | null>(null);
  let permissions = $state({ reading: false, overlay: false });
  let version = $state('');
  let trouble = $state<string[]>([]);

  /** The palette on the document itself, and which side of it: the tokens are declared per
   *  theme and mode, so an element naming no theme has no colours at all. Which side is the
   *  reader's own answer where they gave one, and the device's where they did not. */
  function paint(chosen: Chosen) {
    const device = window.matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.className = themeOf(darkSide(chosen, device), chosen.theme);
  }

  async function load() {
    const told = await ask<{
      settings: Partial<Chosen>;
      curve: number[];
      packs: { held: string[]; open: string[]; offered: Offered[] };
      permissions: { reading: boolean; overlay: boolean };
      version: string;
      trouble: string[];
    }>('state');
    settings = { ...DEFAULTS, ...told.settings };
    curve = told.curve ?? [];
    packs = told.packs ?? { held: [], open: [], offered: [] };
    permissions = told.permissions ?? { reading: false, overlay: false };
    version = told.version ?? '';
    trouble = told.trouble ?? [];
    paint(settings);
  }

  function change<K extends keyof Chosen>(name: K, value: Chosen[K]) {
    if (settings) settings = { ...settings, [name]: value };
    // Drawn in what is being chosen, so choosing shows what it looks like.
    if (settings && (name === 'theme' || name === 'dark')) paint(settings);
    void ask('set', { name, value }).then(() => {
      // Where the dictionaries come from decides what is on offer, so the list is asked for
      // again the moment a reader says where that is.
      if (name === 'host') return load();
    });
  }

  async function get(lang: string) {
    fetching = lang;
    await ask('getPack', { lang }).catch(() => null);
    fetching = null;
    await load();
  }

  async function forget(lang: string) {
    await ask('forgetPack', { lang }).catch(() => null);
    await load();
  }

  // The app tells this view when something it did not do has changed: a permission granted in
  // the system's own settings, an app chosen on the app's own screen.
  whenChanged(() => void load());
  // The app's own back gesture: it hands it to this view, which leaves one screen.
  window.phonetixBack = () => {
    if (view === 'main') return false;
    view = 'main';
    return true;
  };
  $effect(() => {
    void ask('view', { view });
  });
  void load();
</script>

<main class="panel">
  {#if settings}
    <Settings
      where="phone"
      bind:view
      icon="./96.png"
      {settings}
      {curve}
      {packs}
      {change}
      {get}
      {forget}
      {fetching}
      {permissions}
      {version}
      {trouble}
      say={(text, source) =>
        ask<{ answer: null; missing: boolean }>('say', {
          text,
          source,
          target: settings?.target ?? '',
        }).catch(() => null)}
      onOpenReading={() => void ask('openReading')}
      onOpenOverlay={() => void ask('openOverlay')}
      onOpenApps={() => void ask('openApps')}
    />
  {/if}
</main>
