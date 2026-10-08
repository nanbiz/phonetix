<script lang="ts">
  // The first screen: whether the button is out, the two languages it works between, what
  // its card shows, where it waits, and the way to everything set once and left.
  import { LANGUAGES, named } from '@/data/languages';
  import { accentFor } from '@/settings/shape';
  import { accentsOf } from '@/data/accents';
  import { THEME } from '@/ui/theme';
  import { DARK_CHOICES, DETAIL_CHOICES, labelOf, ROWS, SAYS, SIDE_CHOICES } from '@/data/wording';
  import type { Layer } from '@/ext/content/inline';
  import type { Settings } from '@/settings/shape';
  import Check from 'virtual:icons/pixelarticons/check';
  import Group from '../parts/Group.svelte';
  import Item from '../parts/Item.svelte';
  import Switch from '../parts/Switch.svelte';
  import Segments from '../parts/Segments.svelte';
  import ListSheet from '../parts/ListSheet.svelte';
  import Board from '../parts/Board.svelte';

  interface Props {
    settings: Settings;
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
    permissions: { reading: boolean; overlay: boolean };
    trouble: string[];
    version: string;
    /** How many dictionaries are on offer and how many are here. */
    held: number;
    offered: number;
    open: (view: string) => void;
    onOpenReading: () => void;
    onOpenOverlay: () => void;
  }

  let {
    settings,
    change,
    permissions,
    trouble,
    version,
    held,
    offered,
    open,
    onOpenReading,
    onOpenOverlay,
  }: Props = $props();

  let ready = $derived(permissions.reading && permissions.overlay);
  /** The language being learned, which is never the reader's own. */
  let learning = $derived(settings.learning !== settings.target ? settings.learning : '');

  /** Which language list is open over the screen, if one is. */
  let choosing = $state<'' | 'target' | 'learning' | 'known'>('');

  /** The languages left as they are besides the reader's own, by name. */
  let knownNames = $derived(
    settings.known.filter((lang) => lang !== settings.target).map(named).join(', ')
  );

  /** Tick a language the reader reads as it is, or put it back among the translated ones. */
  function toggleKnown(lang: string) {
    const known = settings.known.includes(lang)
      ? settings.known.filter((it) => it !== lang)
      : [...settings.known, lang];
    change('known', known);
  }

  const everyLanguage = Object.entries(LANGUAGES)
    .map(([code, it]) => ({
      value: code,
      label: it.english,
      about: it.native !== it.english ? it.native : undefined,
    }))
    .sort((a, b) => a.label.localeCompare(b.label));

  /** The card's two switches, stored as the one mode they always were. */
  let sound = $derived(settings.layer === 'sound' || settings.layer === 'both');
  let meaning = $derived(settings.layer === 'meaning' || settings.layer === 'both');
  function layerOf(withSound: boolean, withMeaning: boolean): Layer {
    if (withSound && withMeaning) return 'both';
    if (withSound) return 'sound';
    if (withMeaning) return 'meaning';
    return 'off';
  }

  /** The reader's screen, so the board is drawn its shape. */
  const across = globalThis.screen?.width || 9;
  const down = globalThis.screen?.height || 19.5;

  function capital(text: string): string {
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  let accentName = $derived.by(() => {
    const lang = settings.learning;
    const id = accentFor(settings, lang);
    return accentsOf(lang).find((it) => it.id === id)?.name ?? capital(SAYS['dictionary-accent']);
  });
  let themeName = $derived(
    (settings.theme || THEME).charAt(0).toUpperCase() + (settings.theme || THEME).slice(1)
  );
</script>

<header class="top">
  <img class="mark" src="./96.png" alt="" />
  <h1 class="title">Phonetix</h1>
  {#if ready}
    <span data-row="on"><Switch on={settings.on} label={ROWS.on.name} change={(on) => change('on', on)} /></span>
  {/if}
</header>

{#if !ready}
  <Group name={ROWS.setup.name}>
    {#each [
      { row: 'setup-reading', done: permissions.reading, go: onOpenReading },
      { row: 'setup-overlay', done: permissions.overlay, go: onOpenOverlay },
    ] as step (step.row)}
      <div class="item" data-row={step.row}>
        <span class="item-text"><span class="item-name" data-name>{ROWS[step.row].name}</span></span>
        {#if step.done}
          <span aria-label="done"><Check class="option-check" /></span>
        {:else}
          <button class="button" data-does="allow" onclick={step.go}>{SAYS['allow']}</button>
        {/if}
      </div>
    {/each}
  </Group>
{/if}

{#each trouble as said (said)}
  <p class="trouble" role="status" data-row="trouble">{said}</p>
{/each}

<div class:resting={ready && !settings.on}>

  <Group name={ROWS['group-shows'].name}>
    <div class="shows">
      <label class="show" class:on={sound} data-row="ipa">
        <input
          type="checkbox"
          checked={sound}
          onchange={(event) => change('layer', layerOf(event.currentTarget.checked, meaning))}
        />
        <span class="show-picture ipa" aria-hidden="true">/ə/</span>
        <span class="show-name" data-name>{ROWS.ipa.name}</span>
      </label>
      <label class="show" class:on={meaning} data-row="translate">
        <input
          type="checkbox"
          checked={meaning}
          onchange={(event) => change('layer', layerOf(sound, event.currentTarget.checked))}
        />
        <span class="show-picture" aria-hidden="true">文A</span>
        <span class="show-name" data-name>{ROWS.translate.name}</span>
      </label>
    </div>
    {#if sound}
      <div class="item" data-row="narrow">
        <span class="item-text"><span class="item-name" data-name>{ROWS.narrow.name}</span></span>
        <Segments
          choices={DETAIL_CHOICES}
          chosen={settings.narrow ? 'narrow' : 'broad'}
          label={ROWS.narrow.name}
          change={(value) => change('narrow', value === 'narrow')}
        />
      </div>
      <Item name={ROWS.stress.name} row="stress">
        {#snippet control()}
          <Switch
            on={!settings.hideStress}
            label={ROWS.stress.name}
            change={(on) => change('hideStress', !on)}
          />
        {/snippet}
      </Item>
    {/if}
    <!-- What translation works between, under the switch it belongs to, the way the
         transcription's rows are under theirs. -->
    {#if meaning}
    <div class="pair">
      <button class="language" data-row="mine" onclick={() => (choosing = 'target')}>
        <span class="language-role" data-name>{ROWS.mine.name}</span>
        <span class="language-name" class:empty={!settings.target} data-about>
          {settings.target ? named(settings.target) : SAYS['choose-language']}
        </span>
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
    <Item
      name={ROWS.known.name}
      row="known"
      value={knownNames || SAYS['nothing-else']}
      open={() => (choosing = 'known')}
    />
    {/if}
  </Group>

  <Group name={ROWS['group-button'].name}>
    <div class="item wide" data-row="side">
      <Segments
        choices={SIDE_CHOICES}
        chosen={settings.side}
        label={ROWS.side.name}
        change={(value) => change('side', value)}
      />
    </div>
    {#if settings.side !== 'free'}
      <Item name={ROWS['rest-pin'].name} row="rest-pin">
        {#snippet control()}
          <Switch on={settings.pin} label={ROWS['rest-pin'].name} change={(on) => change('pin', on)} />
        {/snippet}
      </Item>
      {#if settings.pin}
        <div class="item wide" data-row="rest">
          <Board
            y={settings.restY}
            side={settings.side}
            {across}
            {down}
            put={(side, y) => {
              if (side !== settings.side) change('side', side);
              change('restY', y);
            }}
          />
        </div>
      {/if}
    {/if}
  </Group>

  <Group>
    <Item
      name={ROWS.apps.name}
      row="apps"
      value={settings.allApps
        ? SAYS['every-app']
        : SAYS['apps-chosen'].replace('%s', String(settings.apps.length))}
      open={() => open('apps')}
    />
    {#if offered > 0}
      <Item
        name={ROWS.dictionaries.name}
        row="dictionaries"
        value={SAYS['packs-held'].replace('%s', String(held))}
        open={() => open('packs')}
      />
    {/if}
    <Item name={ROWS.accents.name} row="accent" value={accentName} open={() => open('accents')} />
    <Item
      name={ROWS.appearance.name}
      row="theme"
      value="{themeName} · {labelOf(DARK_CHOICES, settings.dark)}"
      open={() => open('appearance')}
    />
  </Group>
</div>

{#if version}<p class="version">v{version}</p>{/if}

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
    options={everyLanguage.filter((it) => it.value !== settings.target)}
    chosen={learning}
    change={(value) => change('learning', value)}
    close={() => (choosing = '')}
  />
{:else if choosing === 'known'}
  <ListSheet
    title={ROWS.known.name}
    options={everyLanguage.filter((it) => it.value !== settings.target)}
    chosen={settings.known}
    change={toggleKnown}
    close={() => (choosing = '')}
  />
{/if}
