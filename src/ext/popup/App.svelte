<script lang="ts">
  // The toolbar popup: the three things the extension does, each with what it needs.
  //
  // Over the page, how its words are said, on as many of them as the reader wants. Pointing at
  // a word, a card that says it and, where it is not the reader's own language, translates it.
  // And on a key, a panel that translates into the language the reader is learning. Everything
  // set once and left (accents, colours) is a screen behind a row.
  //
  // Drawn from the phone app's parts, so the two settings screens are one design. A setting is
  // written the moment it changes: every page watches the same keys and redraws itself.
  import { current, darkSide, set, setSite, type Settings } from '@/settings';
  import { accentFor } from '@/settings/shape';
  import { accentsOf } from '@/data/accents';
  import { LANGUAGES, named } from '@/data/languages';
  import { DARK_CHOICES, labelOf, ROWS, SAYS } from '@/data/wording';
  import { sendMessage } from '@/host/messages';
  import { THEME, themeOf } from '@/ui/theme';
  import Back from 'virtual:icons/pixelarticons/chevron-left';
  import Chevron from 'virtual:icons/pixelarticons/chevron-right';
  import Group from '@/phone/parts/Group.svelte';
  import Item from '@/phone/parts/Item.svelte';
  import Switch from '@/phone/parts/Switch.svelte';
  import ListSheet from '@/phone/parts/ListSheet.svelte';
  import Pronunciation from './Pronunciation.svelte';
  import Appearance from '@/phone/screens/Appearance.svelte';
  import Density from './Density.svelte';

  let settings = $state<Settings | null>(null);
  /** What each position of the density bar means, from the core. */
  let curve = $state<number[]>([]);
  /** The site the reader is on, so it can be switched off on its own, and its tab. */
  let site = $state('');
  let siteIcon = $state('');
  let tabId = $state<number | undefined>(undefined);
  /** The keys that open the translate panel, as the browser has them bound. */
  let shortcut = $state('');
  /** Which screen is showing, and which language list is open over it. */
  let view = $state<'main' | 'accents' | 'appearance'>('main');
  let choosing = $state<'' | 'target' | 'learning'>('');

  const icon = browser.runtime.getURL('/icon/48.png');
  const version = browser.runtime.getManifest().version;
  const device = window.matchMedia('(prefers-color-scheme: dark)').matches;

  const everyLanguage = Object.entries(LANGUAGES)
    .map(([code, it]) => ({
      value: code,
      label: it.english,
      about: it.native !== it.english ? it.native : undefined,
    }))
    .sort((a, b) => a.label.localeCompare(b.label));

  async function load() {
    // Asked first and waited for last: the bar's positions come from the host, which may still
    // be waking, and nothing else here needs them.
    const curving = sendMessage('curve', {}).catch(() => [] as number[]);
    settings = await current();
    // The page the reader is on, which is not always the active tab: where the popup is a tab
    // of its own (Firefox on a phone, or opened as one) the active tab is the popup, and the
    // page is the one the reader was on last. Through `browser` rather than `chrome`: on
    // Firefox the chrome namespace is callback-only, and awaiting it yields nothing.
    const readable = (url?: string) => Boolean(url && /^https?:/.test(url));
    let [tab] = (await browser.tabs.query({ active: true, currentWindow: true }))
      .filter((it) => readable(it.url));
    if (!tab) {
      [tab] = (await browser.tabs.query({}))
        .filter((it) => readable(it.url))
        .sort((a, b) => (b.lastAccessed ?? 0) - (a.lastAccessed ?? 0));
    }
    if (tab?.url) {
      site = new URL(tab.url).hostname;
      siteIcon = tab.favIconUrl ?? '';
      tabId = tab.id;
    }
    const commands = await browser.commands.getAll().catch(() => []);
    shortcut = commands.find((it) => it.name === 'ask-for-a-word')?.shortcut ?? '';
    curve = await curving;
  }

  function change<K extends keyof Settings>(name: K, value: Settings[K]) {
    if (!settings) return;
    settings = { ...settings, [name]: value };
    // Drawn in the palette being chosen, so choosing one shows what it looks like.
    if (name === 'theme' || name === 'dark') {
      document.documentElement.className = themeOf(darkSide(settings, device), settings.theme);
    }
    void set(name, value);
  }

  /** Open the translate panel on the page, which is where it is drawn, and get out of its way. */
  async function openPanel() {
    if (tabId === undefined) return;
    await browser.tabs.sendMessage(tabId, { phonetix: 'askForAWord', data: {} }).catch(() => null);
    window.close();
  }

  /** Where the browser lets a reader bind an extension's keys. */
  async function shortcuts() {
    const commands = browser.commands as typeof browser.commands & {
      openShortcutSettings?: () => Promise<void>;
    };
    if (commands.openShortcutSettings) await commands.openShortcutSettings();
    else await browser.tabs.create({ url: 'chrome://extensions/shortcuts' });
    window.close();
  }

  void load();



  let drawing = $derived(settings !== null && settings.layer !== 'off');
  let here = $derived(settings !== null && site !== '' && !settings.off.includes(site));
  /** The language being learned, which is never the reader's own. */
  let learning = $derived(
    settings && settings.learning !== settings.target ? settings.learning : ''
  );
  let accentName = $derived.by(() => {
    if (!settings) return '';
    const lang = settings.target;
    const id = accentFor(settings, lang);
    const name = accentsOf(lang).find((it) => it.id === id)?.name ?? SAYS['dictionary-accent'];
    return name.charAt(0).toUpperCase() + name.slice(1);
  });
  let themeName = $derived(
    settings ? (settings.theme || THEME).charAt(0).toUpperCase() + (settings.theme || THEME).slice(1) : ''
  );
</script>

{#if settings}
  {#if view === 'main'}
    <header class="top">
      <img class="mark" src={icon} alt="" />
      <h1 class="title">Phonetix{#if version}<span class="version-tag">v{version}</span>{/if}</h1>
      <span data-row="on">
        <Switch on={settings.on} label={ROWS.on.name} change={(on) => change('on', on)} />
      </span>
    </header>

    {#if site}
      <Group>
        <Item
          name={site}
          row="site"
          value={settings.off.includes(site)
            ? SAYS['site-off']
            : SAYS['site-following'].replace('%s', settings.on ? SAYS['on'] : SAYS['off'])}
        >
          {#snippet lead()}
            {#if siteIcon}<img class="site-icon" src={siteIcon} alt="" />{/if}
          {/snippet}
          {#snippet control()}
            <Switch
              on={here}
              label={ROWS.site.name}
              change={(on) => {
                if (settings) void setSite(settings, site, on).then(load);
              }}
            />
          {/snippet}
        </Item>
      </Group>
    {/if}

    <div class:resting={!settings.on || !here && site !== ''}>
      <Group name={ROWS['group-page'].name}>
        <Item name={ROWS.inline.name} row="inline">
          {#snippet control()}
            <Switch
              on={drawing}
              label={ROWS.inline.name}
              change={(on) => change('layer', on ? 'sound' : 'off')}
            />
          {/snippet}
        </Item>
        {#if drawing}
          <Density {curve} density={settings.density} change={(at) => change('density', at)} />
        {/if}
      </Group>

      <!-- The two languages everything translates between: a word pointed at is put into the
           reader's own, and the translator works both ways between the two. -->
      <Group name={ROWS['group-languages'].name}>
        <div class="pair">
          <button class="language" data-row="mine" onclick={() => (choosing = 'target')}>
            <span class="language-role" data-name>{ROWS.mine.name}</span>
            <span class="language-name" data-about>{named(settings.target)}</span>
          </button>
          <span class="pair-between" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M6.99 11 3 15l3.99 4v-3H14v-2H6.99zM21 9l-3.99-4v3H10v2h7.01v3z" fill="currentColor" /></svg>
          </span>
          <button class="language" data-row="learning" onclick={() => (choosing = 'learning')}>
            <span class="language-role" data-name>{ROWS.learning.name}</span>
            <span class="language-name" class:empty={!learning} data-about>
              {learning ? named(learning) : SAYS['choose-language']}
            </span>
          </button>
        </div>
        <div class="item" data-row="translator">
          <span class="item-text">
            <span class="item-name" data-name>{ROWS.translator.name}</span>
            <button class="keys" data-does="shortcut" onclick={shortcuts}>
              {#if shortcut}
                {#each shortcut.split('+') as key, at (at)}<kbd>{key}</kbd>{/each}
              {:else}
                {SAYS['set-key']}
              {/if}
            </button>
          </span>
          <button
            class="button"
            data-does="open-panel"
            disabled={tabId === undefined}
            onclick={openPanel}>{SAYS['open-panel']}</button
          >
        </div>
      </Group>
    </div>

    <!-- Everything set once and left, side by side: two screens a reader opens rarely. -->
    <Group>
      <div class="duo">
        <button class="item" data-row="pronunciation" onclick={() => (view = 'accents')}>
          <span class="item-text">
            <span class="item-name" data-name>{ROWS.ipa.name}</span>
            <span class="item-value" data-about
              >{settings.narrow ? SAYS['detailed'] : SAYS['simple']} · {accentName}</span
            >
          </span>
          <Chevron class="item-chevron" />
        </button>
        <button class="item" data-row="theme" onclick={() => (view = 'appearance')}>
          <span class="item-text">
            <span class="item-name" data-name>{ROWS.appearance.name}</span>
            <span class="item-value" data-about
              >{themeName} · {labelOf(DARK_CHOICES, settings.dark)}</span
            >
          </span>
          <Chevron class="item-chevron" />
        </button>
      </div>
    </Group>

  {:else}
    <header class="top">
      <button class="icon-button" aria-label={SAYS['back']} onclick={() => (view = 'main')}>
        <Back />
      </button>
      <h1 class="title">{view === 'accents' ? ROWS.ipa.name : ROWS.appearance.name}</h1>
    </header>
    <div data-view={view}>
      {#if view === 'accents'}
        <Pronunciation {settings} {change} />
      {:else}
        <Appearance {settings} {device} {change} />
      {/if}
    </div>
  {/if}

  {#if choosing === 'target'}
    <ListSheet
      title={ROWS.mine.name}
      options={everyLanguage}
      chosen={settings.target}
      change={(value) => change('target', value)}
      close={() => (choosing = '')}
    />
  {:else if choosing === 'learning'}
    <ListSheet
      title={ROWS.learning.name}
      options={everyLanguage.filter((it) => it.value !== settings?.target)}
      chosen={learning}
      change={(value) => change('learning', value)}
      close={() => (choosing = '')}
    />
  {/if}
{/if}
