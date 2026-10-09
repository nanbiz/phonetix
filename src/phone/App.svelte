<script lang="ts">
  // The phone app's own screen: the settings, drawn from what the app says and changed through
  // it. One screen at a time, with the phone's way back leading out of each.
  import { darkSide, DEFAULTS, type Settings } from '@/settings/shape';
  import { themeOf } from '@/ui/theme';
  import { ROWS, SAYS } from '@/data/wording';
  import type { Offered } from '@/host/packs';
  import { covered, uncover } from '@/ui/controls/sheets.svelte';
  import Back from 'virtual:icons/lucide/chevron-left';
  import { ask, whenChanged } from './bridge';
  import Home from './screens/Home.svelte';
  import Apps from './screens/Apps.svelte';
  import Packs from './screens/Packs.svelte';
  import Accents from './screens/Accents.svelte';
  import Appearance from './screens/Appearance.svelte';

  let settings = $state<Settings | null>(null);
  let view = $state('main');
  let packs = $state<{ held: string[]; offered: Offered[] }>({ held: [], offered: [] });
  let fetching = $state<string | null>(null);
  let permissions = $state({ reading: false, overlay: false });
  /** Whether the device is set to dark, which only the app can say: the web view answers
   *  prefers-color-scheme as light whatever the phone is set to. */
  let device = $state(false);
  let version = $state('');
  let trouble = $state<string[]>([]);
  let apps = $state<{ pkg: string; label: string }[]>([]);

  const TITLES: Record<string, string> = {
    apps: ROWS.apps.name,
    packs: ROWS.dictionaries.name,
    accents: ROWS.accents.name,
    appearance: ROWS.appearance.name,
  };

  /** The palette on the document itself: the tokens are declared per palette and side. */
  function paint(chosen: Settings) {
    document.documentElement.className = themeOf(darkSide(chosen, device), chosen.theme);
  }

  /** How many changes this screen has sent, so an answer to a question asked before the last
   *  of them does not put back what the reader has just changed. */
  let edits = 0;

  async function load() {
    const before = edits;
    const told = await ask<{
      settings: Partial<Settings>;
      packs: { held: string[]; offered: Offered[] };
      permissions: { reading: boolean; overlay: boolean };
      device: boolean;
      version: string;
      trouble: string[];
    }>('state');
    if (edits === before || !settings) settings = { ...DEFAULTS, ...told.settings };
    packs = told.packs ?? { held: [], offered: [] };
    permissions = told.permissions ?? { reading: false, overlay: false };
    device = told.device ?? false;
    version = told.version ?? '';
    trouble = told.trouble ?? [];
    paint(settings);
  }

  function change<K extends keyof Settings>(name: K, value: Settings[K]) {
    if (!settings) return;
    edits++;
    settings = { ...settings, [name]: value };
    if (name === 'theme' || name === 'dark') paint(settings);
    void ask('set', { name, value });
  }

  function toggleApp(pkg: string) {
    if (!settings) return;
    const apps = settings.apps.includes(pkg)
      ? settings.apps.filter((it) => it !== pkg)
      : [...settings.apps, pkg];
    edits++;
    settings = { ...settings, apps };
    void ask('toggleApp', { pkg });
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

  function open(next: string) {
    view = next;
    window.scrollTo(0, 0);
    if (next === 'apps' && apps.length === 0) {
      void ask<{ apps: { pkg: string; label: string }[] }>('apps').then((told) => {
        apps = told.apps ?? [];
      });
    }
  }

  // Something changed that this screen did not change: a permission granted in the system's
  // settings.
  whenChanged(() => void load());
  window.phonetixOpen = (wanted: string) => {
    if (!settings) return false;
    open(TITLES[wanted] ? wanted : 'main');
    return true;
  };
  // The phone's own way back: whatever stands over the screen first, then the screen.
  window.phonetixBack = () => {
    if (uncover()) return true;
    if (view === 'main') return false;
    open('main');
    return true;
  };
  $effect(() => {
    void ask('view', { view: covered() ? 'sheet' : view });
  });
  void load();
</script>

<main class="screen" data-view={view}>
  {#if settings}
    {#if view === 'main'}
      <Home
        {settings}
        {change}
        {permissions}
        {trouble}
        {version}
        held={packs.held.length}
        offered={packs.offered.length}
        {open}
        onOpenReading={() => void ask('openReading')}
        onOpenOverlay={() => void ask('openOverlay')}
      />
    {:else}
      <header class="top">
        <button class="icon-button" aria-label={SAYS['back']} onclick={() => open('main')}>
          <Back />
        </button>
        <h1 class="title">{TITLES[view]}</h1>
      </header>
      {#if view === 'apps'}
        <Apps
          {settings}
          {apps}
          allApps={(on) => change('allApps', on)}
          toggle={toggleApp}
        />
      {:else if view === 'packs'}
        <Packs held={packs.held} offered={packs.offered} {fetching} {get} {forget} />
      {:else if view === 'accents'}
        <Accents {settings} {change} />
      {:else if view === 'appearance'}
        <Appearance {settings} {device} {change} />
      {/if}
    {/if}
  {/if}
</main>
