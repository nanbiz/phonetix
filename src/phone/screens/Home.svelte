<script lang="ts">
  // The first screen: whether the button is out, how its card writes a sound, the languages
  // the reader knows, where the button waits, and the way to everything set once and left.
  import { LANGUAGES, named } from '@/data/languages';
  import { accentFor } from '@/settings/shape';
  import { accentsOf } from '@/data/accents';
  import { THEME } from '@/ui/theme';
  import { DARK_CHOICES, DETAIL_CHOICES, labelOf, ROWS, SAYS, SIDE_CHOICES } from '@/data/wording';
  import type { Settings } from '@/settings/shape';
  import Check from 'virtual:icons/lucide/check';
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
  /** Which language list is open over the screen, if one is. */
  let choosing = $state<'' | 'target' | 'known'>('');

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

  /** The reader's screen, so the board is drawn its shape. */
  const across = globalThis.screen?.width || 9;
  const down = globalThis.screen?.height || 19.5;


  let accentName = $derived.by(() => {
    const lang = settings.learning;
    const id = accentFor(settings, lang);
    return accentsOf(lang).find((it) => it.id === id)?.name ?? '';
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

  <!-- The card always says both how a word sounds and what it means, so what is set here is
       how the one is written and what the other is translated into. -->
  <Group name={ROWS.ipa.name}>
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
  </Group>

  <!-- The languages the reader knows: the one everything else is translated into, and the
       others read as they are. Which language a page is in is the page's business, and the
       one a word is asked for in is chosen in the panel that asks, so neither is set here. -->
  <Group name={ROWS.translate.name}>
    <Item
      name={ROWS.mine.name}
      row="mine"
      value={settings.target ? named(settings.target) : SAYS['choose-language']}
      open={() => (choosing = 'target')}
    />
    <Item
      name={ROWS.known.name}
      row="known"
      value={knownNames || SAYS['nothing-else']}
      open={() => (choosing = 'known')}
    />
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
{:else if choosing === 'known'}
  <ListSheet
    title={ROWS.known.name}
    options={everyLanguage.filter((it) => it.value !== settings.target)}
    chosen={settings.known}
    change={toggleKnown}
    close={() => (choosing = '')}
  />
{/if}
