<script lang="ts">
  // The phone app's own screen: the settings, drawn from what the app says and changed through
  // it. One screen at a time, with the phone's way back leading out of each.
  import { darkSide, DEFAULTS, type Settings } from '@/settings/shape';
  import { themeOf } from '@/ui/theme';
  import { ROWS, SAYS } from '@/data/wording';
  import { interfaceFor, markDocument, speakIn, type Spoken } from '@/data/interface';
  import type { Offered } from '@/host/packs';
  import { ACCENTS } from '@/data/accents';
  import { covered, uncover } from '@/ui/controls/sheets.svelte';
  import Back from 'virtual:icons/lucide/chevron-left';
  import { ask, whenChanged } from './bridge';
  import Home from './screens/Home.svelte';
  import Apps from './screens/Apps.svelte';
  import Packs from './screens/Packs.svelte';
  import Accents from './screens/Accents.svelte';
  import Languages from './screens/Languages.svelte';
  import LookUp from './screens/LookUp.svelte';
  import Appearance from './screens/Appearance.svelte';

  let settings = $state<Settings | null>(null);
  let view = $state('main');
  /** The packs that are an accent's words rather than a language's dictionary. */
  const ACCENT_PACKS = new Set(
    Object.entries(ACCENTS).flatMap(([lang, rows]) => rows.map((it) => it.id).filter((id) => id !== lang))
  );
  let packs = $state<{ held: string[]; offered: Offered[] }>({ held: [], offered: [] });
  let fetching = $state<string | null>(null);
  let permissions = $state({ reading: false, overlay: false });
  /** Whether the device is set to dark, which only the app can say: the web view answers
   *  prefers-color-scheme as light whatever the phone is set to. */
  let device = $state(false);
  let version = $state('');
  let trouble = $state<string[]>([]);
  let apps = $state<{ pkg: string; label: string }[]>([]);

  /** Each screen's title, by the row it opens from, in the language the screen is in. */
  const TITLED: Record<string, string> = {
    apps: 'apps',
    packs: 'dictionaries',
    languages: 'group-languages',
    lookup: 'cards',
    accents: 'accents',
    appearance: 'appearance',
  };

  /** The interface's words in each language, bundled beside this page and read when needed. */
  const TABLES = import.meta.glob<Spoken>('../../public/interface/*.json', { import: 'default' });
  /** The language the screen is drawn in; drawn again whole when it changes. */
  let spoken = $state('en');

  async function speak(mine: string): Promise<void> {
    const lang = interfaceFor(mine);
    if (lang === spoken) return;
    const table = TABLES[`../../public/interface/${lang}.json`];
    speakIn(lang, table ? await table().catch(() => null) : null);
    markDocument();
    spoken = lang;
  }

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
    await speak(settings.target);
    packs = told.packs ?? { held: [], offered: [] };
    // The dictionaries, not the accents' packs listed beside them, which come with an accent.
    packs = { ...packs, offered: packs.offered.filter((it) => !ACCENT_PACKS.has(it.lang)) };
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
    if (name === 'target') void speak(settings.target);
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
    open(TITLED[wanted] ? wanted : 'main');
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

{#key spoken}
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
        <button class="icon-button" data-does="back" aria-label={SAYS['back']} onclick={() => open('main')}>
          <Back />
        </button>
        <h1 class="title">{ROWS[TITLED[view]]?.name}</h1>
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
      {:else if view === 'languages'}
        <Languages {settings} {change} />
      {:else if view === 'lookup'}
        <LookUp {settings} {change} />
      {:else if view === 'accents'}
        <Accents {settings} {change} />
      {:else if view === 'appearance'}
        <Appearance {settings} {device} {change} />
      {/if}
    {/if}
  {/if}
</main>
{/key}
