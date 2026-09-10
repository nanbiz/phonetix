<script lang="ts">
  // What a reader changes, and nothing else.
  //
  // One screen at a time: the two things that change daily - what is drawn over a word and how
  // often - are on the first, and an accent, a dictionary and the details of how a page is read
  // are each a screen behind a row. A wall of twelve controls is a wall a reader reads every
  // time they came to move one bar.
  //
  // Nothing here draws a control or lays out a row itself. The rows are Row, the screens are
  // Screen and the controls are the ones in src/ui/controls, drawn in the generated tokens the
  // card and the phone are drawn in, so this view and the rest of the product are one design.
  import { ACCENTS, accentsOf } from '@/data/accents';
  import { LANGUAGES, named as nameOf } from '@/data/languages';
  import type { Layer } from '@/ext/content/inline';
  import { accentFor, setAccent, type Settings } from '@/settings';
  import type { Offered } from '@/host/packs';
  import Heart from 'virtual:icons/pixelarticons/heart';

  import Picker from '@/ui/controls/Picker.svelte';
  import Slider from '@/ui/controls/Slider.svelte';
  import Toggle from '@/ui/controls/Toggle.svelte';
  import Segmented from '@/ui/controls/Segmented.svelte';
  import Frequency from './Frequency.svelte';
  import NavRow from './NavRow.svelte';
  import Packs from './Packs.svelte';
  import Row from './Row.svelte';
  import Screen from './Screen.svelte';
  import Switchboard from './Switchboard.svelte';
  import Trouble from './Trouble.svelte';

  interface Props {
    settings: Settings;
    /** The densities the bar's positions mean, from the core, so the number a reader sees is
     *  the number the annotation is decided by. */
    curve: number[];
    /** Which languages have a pack here, which are open, and what can be fetched. */
    packs: { held: string[]; open: string[]; offered: Offered[] };
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
    /** The site the reader is on, so it can be switched off without switching everything off. */
    site?: string;
    /** What the page being read turned out to be in, which decides the accents on offer. */
    pageLang?: string;
    onSite?: (on: boolean) => void;
    /** Fetch a language's dictionary, or give one up. */
    get?: (lang: string) => void;
    forget?: (lang: string) => void;
    /** Which language is being fetched right now, so the row can say so. */
    fetching?: string | null;
    /** The extension's own mark and the site's, from wherever the browser keeps them. */
    icon?: string;
    siteIcon?: string;
    /** Which build this is, so a reader can say what they are looking at. */
    version?: string;
    /** What the host says is not working, which is nothing at all when everything answers. */
    trouble?: string[];
  }

  let {
    settings,
    curve,
    packs,
    change,
    get,
    forget,
    fetching = null,
    site = '',
    pageLang = '',
    onSite,
    icon = '',
    siteIcon = '',
    version = '',
    trouble = [],
  }: Props = $props();

  /** Which screen the reader is on. */
  let view = $state('main');

  let here = $derived(site !== '' && !settings.off.includes(site));
  /** What the page is being read as: what the reader chose, or what the page says it is. */
  let reading = $derived(settings.source || pageLang || '');
  // What the page is in decides which accents there are to choose between.
  let accents = $derived(accentsOf(reading));
  /** Which accent this page's language is read in, which is the one worth showing. */
  let chosen = $derived(accentFor(settings, reading));
  let accentName = $derived(accents.find((row) => row.id === chosen)?.name ?? '');
  /** An accent with no per-word dictionary behind it is a rule applied to every word. Say so,
   *  rather than let it look like the same kind of thing as the others. */
  let ruleBased = $derived(accents.find((row) => row.id === chosen)?.rule ?? false);

  // The accent for the language on screen is the one worth showing; the rest are a standing
  // preference a reader sets once and rarely revisits, so they are a list under it rather than
  // a screen of their own.
  let elsewhere = $derived(
    Object.entries(ACCENTS)
      .filter(([lang, offered]) => lang !== reading && offered.length > 1)
      .map(([lang, offered]) => ({
        lang,
        offered,
        chosen: accentFor(settings, lang),
      }))
      .sort((a, b) => nameOf(a.lang).localeCompare(nameOf(b.lang)))
  );

  const layers: { value: Layer; label: string }[] = [
    { value: 'off', label: 'nothing' },
    { value: 'gloss', label: 'meaning' },
    { value: 'gloss+ipa', label: 'both' },
    { value: 'ipa', label: 'sound' },
    { value: 'replace', label: 'in place' },
  ];

  // The languages a reader can pick between, by the name they know them under.
  const named = Object.entries(LANGUAGES)
    .map(([code, row]) => ({ code, english: row.english, native: row.native }))
    .sort((a, b) => a.english.localeCompare(b.english));

  let readInto = $derived([
    { value: '', label: 'nothing yet' },
    ...named.map((it) => ({ value: it.code, label: `${it.english} · ${it.native}` })),
  ]);
  let pageIs = $derived([
    { value: '', label: 'what the page says' },
    ...named.map((it) => ({ value: it.code, label: it.english })),
  ]);

  let held = $derived(packs.held.length);
  let offered = $derived(packs.offered.length);
</script>

<Screen name="main" on={view}>
  <!-- Whether this is on, and whether it is on here. The first thing in the popup because it
       is the first thing a reader opens it for. -->
  <Switchboard
    {icon}
    {siteIcon}
    {site}
    on={settings.on}
    {here}
    decided={settings.off.includes(site)}
    change={(on) => change('on', on)}
    onSite={(on) => onSite?.(on)}
  />

  <Trouble {trouble} />

  <!-- What this page is being read as, and into what. Everything below is a choice about
       that, and a reader whose page was read as the wrong language has no other way to find
       out why the answers are nonsense. -->
  <NavRow
    name={reading ? nameOf(reading) : 'Not sure what this page is'}
    row="page"
    about="{settings.source ? 'set by you' : 'what the page says'}{accentName
      ? ` · ${accentName}`
      : ''}{settings.target ? ` · read into ${nameOf(settings.target)}` : ''}"
    open={() => (view = accents.length > 0 ? 'accent' : 'more')}
  />

  <!-- The bar a reader comes back to, on a row of its own. -->
  <Frequency {curve} density={settings.density} change={(at) => change('density', at)} />

  <div class="rows">
    <Row name="Show over a word" row="layer">
      {#snippet wide()}
        <Segmented
          choices={layers}
          chosen={settings.layer}
          change={(value) => change('layer', value as Layer)}
        />
      {/snippet}
    </Row>

    <Row name="I read into" row="target">
      {#snippet control()}
        <Picker
          options={readInto}
          chosen={settings.target}
          label="I read into"
          change={(value) => change('target', value)}
        />
      {/snippet}
    </Row>
  </div>

  {#if accents.length > 0}
    <!-- Only where there is something real to offer: a voice that exists, or a rule that holds
         for the whole vocabulary. A list of accents that all sound the same is a list of
         promises. -->
    <NavRow
      name="Accent"
      row="accent"
      about="{nameOf(reading)} · {accentName || 'as the dictionary gives it'}"
      open={() => (view = 'accent')}
    />
  {/if}

  <NavRow
    name="Dictionaries"
    row="dictionaries"
    about={offered > 0
      ? `${held} of ${offered} here, ${packs.open.length} open`
      : settings.host
        ? `nothing on offer at ${settings.host}`
        : 'no source for them yet'}
    open={() => (view = 'packs')}
  />

  <NavRow
    name="How it reads"
    row="more"
    about="transcriptions, stress, how long a rest opens a card"
    open={() => (view = 'more')}
  />

  <p class="made">
    Made with <span class="heart"><Heart class="r-icon" /></span>
    {#if version}<span class="version">· v{version}</span>{/if}
  </p>
</Screen>

<Screen
  name="accent"
  on={view}
  title="{nameOf(reading)} accent"
  back={() => (view = 'main')}
>
  <div class="choices">
    <!-- What the dictionary lists first, which is what most readers want and what a language
         with no accents to choose between gets anyway. -->
    <button
      class="choice {chosen === '' ? 'on' : ''}"
      data-accent=""
      onclick={() => change('accents', setAccent(settings, reading, ''))}
    >
      <span>As the dictionary gives it</span>
      {#if chosen === ''}<span aria-hidden="true">✓</span>{/if}
    </button>
    {#each accents as accent (accent.id)}
      <button
        class="choice {chosen === accent.id ? 'on' : ''}"
        data-accent={accent.id}
        onclick={() => change('accents', setAccent(settings, reading, accent.id))}
      >
        <span>{accent.name}</span>
        {#if chosen === accent.id}<span aria-hidden="true">✓</span>{/if}
      </button>
    {/each}
    {#if ruleBased}
      <p class="r-sub">
        No per-word dictionary exists for this accent. It is derived from its pronunciation
        rules, applied to every word.
      </p>
    {/if}
  </div>

  {#if elsewhere.length > 0}
    <!-- Every other language that offers a choice, so setting one for a page does not throw
         away the one already set for another. -->
    <div class="rows">
      {#each elsewhere as other (other.lang)}
        <Row name={nameOf(other.lang)} row="accent-elsewhere" marks={{ 'data-lang': other.lang }}>
          {#snippet control()}
            <Picker
              options={[
                // Short, because the row already says which language it is about and the
                // control is a menu rather than a sentence.
                { value: '', label: 'dictionary' },
                ...other.offered.map((it) => ({ value: it.id, label: it.name })),
              ]}
              chosen={other.chosen}
              label="{nameOf(other.lang)} accent"
              change={(value) => change('accents', setAccent(settings, other.lang, value))}
            />
          {/snippet}
        </Row>
      {/each}
    </div>
  {/if}
</Screen>

<Screen
  name="packs"
  on={view}
  title="Dictionaries"
  note="{held} of {offered} here"
  back={() => (view = 'main')}
>
  <Packs
    {packs}
    {fetching}
    {get}
    {forget}
    host={settings.host}
    onHost={(said) => change('host', said)}
  />
</Screen>

<Screen name="more" on={view} title="How it reads" back={() => (view = 'main')}>
  <div class="rows">
    <Row
      name="This page is in"
      row="source"
      about="Each block is read on its own, so a page in two languages already reads correctly.
             Set this only where that goes wrong."
    >
      {#snippet control()}
        <Picker
          options={pageIs}
          chosen={settings.source}
          label="this page is in"
          change={(value) => change('source', value)}
        />
      {/snippet}
    </Row>

    <Row
      name="Transcriptions"
      row="narrow"
      about={settings.narrow
        ? 'every detail of how it is said'
        : 'the sounds that tell words apart'}
    >
      {#snippet control()}
        <Segmented
          choices={[
            { value: 'broad', label: 'broad' },
            { value: 'narrow', label: 'narrow' },
          ]}
          chosen={settings.narrow ? 'narrow' : 'broad'}
          change={(value) => change('narrow', value === 'narrow')}
        />
      {/snippet}
    </Row>

    <Row
      name="Stress marks"
      row="stress"
      about="over a word; the card always shows them"
    >
      {#snippet control()}
        <Toggle
          on={!settings.hideStress}
          label="stress marks"
          change={(on) => change('hideStress', !on)}
        />
      {/snippet}
    </Row>

    <Row name="Rest before a card opens" row="delay" says="{settings.delay} ms">
      {#snippet wide()}
        <Slider
          value={settings.delay}
          max={1000}
          step={50}
          label="rest before a card opens"
          ends={['instant', '1000 ms']}
          change={(ms) => change('delay', ms)}
        />
      {/snippet}
    </Row>

    <Row name="Animations" row="animations" about="the card eases in; off is instant">
      {#snippet control()}
        <Toggle
          on={settings.animations}
          label="animations"
          change={(on) => change('animations', on)}
        />
      {/snippet}
    </Row>
  </div>

  <!-- What actually answers a word, in the order it is asked. A reader deciding whether to
       trust what a card says is deciding it on this, and the card's own pill names which of
       these answered each time. -->
  <div class="rows">
    <Row name="What answers a word" row="how">
      {#snippet wide()}
        <ol class="steps">
          <li>
            <b>A dictionary</b>, where one is held for the language: entries a person wrote,
            with the senses, the forms and how each is said.
          </li>
          <li>
            <b>A translator</b>, for what no dictionary holds - a long compound, a phrase you
            selected. It runs on this machine, and what it answers is marked as a guess.
          </li>
          <li>
            <b>A synthesiser</b>, for how a word is said when no dictionary has it. Its
            transcriptions carry its own mark, so they are never mistaken for a person's.
          </li>
        </ol>
      {/snippet}
    </Row>
  </div>
</Screen>
