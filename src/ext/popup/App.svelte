<script lang="ts">
  // The toolbar popup: the three things the extension does, each with what it needs.
  //
  // Over the page, how its words are said, on as many of them as the reader wants. Pointing at
  // a word, a card that says it and translates it into theirs where it is in another language.
  // And on a key, a panel that translates into whichever language they pick in it. Everything
  // set once and left (accents, colours) is a screen behind a row.
  //
  // Drawn from the phone app's parts, so the two settings screens are one design. A setting is
  // written the moment it changes: every page watches the same keys and redraws itself.
  import { current, darkSide, set, setSite, type Settings } from '@/settings';
  import { named } from '@/data/languages';
  import { DARK_CHOICES, LAYER_CHOICES, labelOf, ROWS, SAYS } from '@/data/wording';
  import { sendMessage } from '@/host/messages';
  import type { Layer } from '@/ext/content/inline';
  import { THEME, themeOf } from '@/ui/theme';
  import Back from 'virtual:icons/lucide/chevron-left';
  import Chevron from 'virtual:icons/lucide/chevron-right';
  import Mic from 'virtual:icons/lucide/mic';
  import LanguagesIcon from 'virtual:icons/lucide/languages';
  import LookUp from 'virtual:icons/lucide/mouse-pointer-2';
  import ReplaceIcon from 'virtual:icons/lucide/sparkles';
  import TranslatorIcon from 'virtual:icons/lucide/message-square-text';
  import Group from '@/phone/parts/Group.svelte';
  import Item from '@/phone/parts/Item.svelte';
  import Switch from '@/phone/parts/Switch.svelte';
  import Segments from '@/phone/parts/Segments.svelte';
  import ListSheet from '@/phone/parts/ListSheet.svelte';
  import Pronunciation from './Pronunciation.svelte';
  import Appearance from '@/phone/screens/Appearance.svelte';
  import Density from './Density.svelte';
  import Languages from '@/phone/screens/Languages.svelte';

  let settings = $state<Settings | null>(null);
  /** What each position of the density bar means, from the core. */
  let curve = $state<number[]>([]);
  /** The site the reader is on, so it can be switched off on its own, and its tab. */
  let site = $state('');
  let siteIcon = $state('');
  let tabId = $state<number | undefined>(undefined);
  /** The keys that open the translate panel, as the browser has them bound. */
  let shortcut = $state('');
  /** Whether there is a keyboard to press the keys on, as far as a page can tell: a pointer that
   *  hovers comes with one - a desktop, or an iPad with its keyboard and trackpad - where a
   *  touch screen alone has no keys, and keys shown there are keys nobody can press. */
  const keyboard = matchMedia('(any-hover: hover)').matches;
  /** And the keys that switch Phonetix on or off. */
  let switching = $state('');
  /** And the keys that open the translator hearing what to translate. */
  let speaking = $state('');
  /** Which screen is showing. */
  let view = $state<'main' | 'accents' | 'appearance' | 'languages'>('main');
  /** The dictionaries on their way and how far each has got, as the host tells it. */
  let arriving = $state<Record<string, number>>({});

  const icon = browser.runtime.getURL('/icon/48.png');
  const version = browser.runtime.getManifest().version;
  const device = window.matchMedia('(prefers-color-scheme: dark)').matches;

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
    // Alt+Shift+T where the reader bound nothing else: the page hears it (content/index.ts).
    shortcut = commands.find((it) => it.name === 'translator')?.shortcut || 'Alt+Shift+T';
    switching = commands.find((it) => it.name === 'switch-on-off')?.shortcut ?? '';
    speaking = commands.find((it) => it.name === 'speak')?.shortcut ?? '';
    curve = await curving;
  }

  async function follow() {
    arriving = ((await browser.storage.local.get('arriving')).arriving as Record<string, number>) ?? {};
    browser.storage.onChanged.addListener((changes, area) => {
      if (area === 'local' && 'arriving' in changes) {
        arriving = (changes.arriving.newValue as Record<string, number> | undefined) ?? {};
      }
    });
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
  void follow();



  let drawing = $derived(settings !== null && settings.layer !== 'off');
  /** What can take a word's place on the page: nothing, or one of the two, never both. */
  const replacing = (['off', 'sound', 'meaning'] as const).map(
    (value) => LAYER_CHOICES.find((it) => it.value === value) ?? { value, label: value }
  );
  let here = $derived(settings !== null && site !== '' && !settings.off.includes(site));
  /** The languages the reader reads, their own first, and the one they learn. */
  let languageNames = $derived.by(() => {
    if (!settings) return '';
    const reading = [settings.target, ...settings.known]
      .filter((lang, at, all) => lang && lang !== settings?.learn && all.indexOf(lang) === at)
      .map(named)
      .join(' · ');
    const learning = settings.learn ? SAYS['learning-it'].replace('%s', named(settings.learn)) : '';
    return [reading, learning].filter(Boolean).join(', ');
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
      <span class="top-on" data-row="on">
        {#if keyboard}
          <button class="keys" data-does="shortcut-on-off" onclick={shortcuts}>
            {#if switching}
              {#each switching.split('+') as key, at (at)}<kbd>{key}</kbd>{/each}
            {:else}
              {SAYS['set-key']}
            {/if}
          </button>
        {/if}
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

    {#each Object.entries(arriving) as [lang, share] (lang)}
      <!-- Until a language's dictionary is here its words are only said, and on a slow line
           that is minutes: what is on its way, and how far. -->
      <div class="arriving" data-row="arriving">
        <span>Getting the {named(lang)} dictionary</span>
        <progress max="1" value={share}></progress>
        <span class="arriving-share">{Math.round(share * 100)}%</span>
      </div>
    {/each}

    <!-- The languages the reader has: what the card and the replaced words both follow, so
         above both. -->
    <Group>
      <Item
        name={ROWS['group-languages'].name}
        row="languages"
        value={languageNames}
        open={() => (view = 'languages')}
      >
        {#snippet glyph()}<LanguagesIcon class="row-icon" />{/snippet}
      </Item>
    </Group>

    <div class:resting={!settings.on || !here && site !== ''}>
      <!-- Pointing at a word opens a card on it. -->
      <Group>
        <Item name={ROWS.cards.name} row="cards">
          {#snippet glyph()}<LookUp class="row-icon" />{/snippet}
          {#snippet control()}
            <Switch
              on={settings?.cards ?? true}
              label={ROWS.cards.name}
              change={(on) => change('cards', on)}
            />
          {/snippet}
        </Item>
      </Group>

      <Group>
        <!-- Named like Look up, on a row of its own with its icon: the two things the
             extension does over a page. One thing takes a word's place, so the page reads as
             running text: how the word is said, or what it means. The card over a word says
             both either way. -->
        <div class="item" data-row="inline-name">
          <span class="item-text">
            <span class="item-name" data-name><ReplaceIcon class="row-icon" />{ROWS.inline.name}</span>
          </span>
        </div>
        <div class="item wide" data-row="inline">
          <Segments
            choices={replacing}
            chosen={settings.layer === 'sound' || settings.layer === 'off' ? settings.layer : 'meaning'}
            label={ROWS.inline.name}
            change={(value) => change('layer', value as Layer)}
          />
        </div>
        {#if drawing}
          <Density {curve} density={settings.density} change={(at) => change('density', at)} />
        {/if}
      </Group>
    </div>

    <!-- The translator answers whatever is asked of it, with Phonetix off or this site switched
         off too, so it sits outside what dims when they are. -->
    <Group>
      <div class="item" data-row="translator">
        <span class="item-text">
          <!-- Its two ways in, each with its key, the keys in one column: the panel, and the
               panel opened hearing what to translate. -->
          <span class="ways">
            <span class="item-name" data-name><TranslatorIcon class="row-icon" />{ROWS.translator.name}</span>
            {#if keyboard}
            <button class="keys" data-does="shortcut" onclick={shortcuts}>
              {#if shortcut}
                {#each shortcut.split('+') as key, at (at)}<kbd>{key}</kbd>{/each}
              {:else}
                {SAYS['set-key']}
              {/if}
            </button>
            <span class="way"><Mic class="row-icon" />{SAYS['dictate']}</span>
            <button class="keys" data-does="shortcut-speak" aria-label={SAYS['speak']} onclick={shortcuts}>
              {#if speaking}
                {#each speaking.split('+') as key, at (at)}<kbd>{key}</kbd>{/each}
              {:else}
                {SAYS['set-key']}
              {/if}
            </button>
            {/if}
          </span>
        </span>
        <button
          class="button"
          data-does="open-panel"
          disabled={tabId === undefined}
          onclick={openPanel}>{SAYS['open-panel']}</button
        >
      </div>
    </Group>

    <!-- Everything set once and left, side by side: two screens a reader opens rarely. -->
    <Group>
      <div class="duo">
        <button class="item" data-row="pronunciation" onclick={() => (view = 'accents')}>
          <span class="item-text">
            <span class="item-name" data-name>{ROWS.ipa.name}</span>
            <span class="item-value" data-about
              >{settings.narrow ? SAYS['detailed'] : SAYS['simple']}</span
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
      <h1 class="title"
        >{view === 'accents'
          ? ROWS.ipa.name
          : view === 'languages'
            ? ROWS['group-languages'].name
            : ROWS.appearance.name}</h1
      >
    </header>
    <div data-view={view}>
      {#if view === 'languages'}
        <Languages {settings} {change} />
      {:else if view === 'accents'}
        <Pronunciation {settings} {change} />
      {:else}
        <Appearance {settings} {device} {change} />
      {/if}
    </div>
  {/if}

{/if}
