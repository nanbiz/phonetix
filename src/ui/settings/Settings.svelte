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
  import {
    aboutOf,
    DETAIL_CHOICES,
    ENDS,
    labelOf,
    LAYER_CHOICES,
    ROWS,
    SAYS,
  } from '@/data/wording';
  import type { Offered } from '@/host/packs';
  import Heart from 'virtual:icons/pixelarticons/heart';

  import Picker from '@/ui/controls/Picker.svelte';
  import Slider from '@/ui/controls/Slider.svelte';
  import Toggle from '@/ui/controls/Toggle.svelte';
  import Segmented from '@/ui/controls/Segmented.svelte';
  import Choice from './Choice.svelte';
  import Frequency from './Frequency.svelte';
  import NavRow from './NavRow.svelte';
  import Packs from './Packs.svelte';
  import Row from './Row.svelte';
  import Screen from './Screen.svelte';
  import Switchboard from './Switchboard.svelte';
  import Trouble from './Trouble.svelte';
  import Field from '@/ui/controls/Field.svelte';
  import AnswerCard from '@/ui/card/AnswerCard.svelte';
  import type { Answer } from '@/core/answer';

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
    /** The word for something the reader wants to say, asked of the host: the engine and the
     *  dictionaries live there, and this view holds neither. The language to answer in is
     *  passed with it, since what a reader is learning is not always what the page in front
     *  of them is written in - the settings view is often open with no page at all. */
    say?: (
      text: string,
      source: string
    ) => Promise<{ answer: Answer | null; missing: boolean } | null>;
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
    say,
  }: Props = $props();

  /** What the reader asked for in their own language, what came back, and whether the
   *  machine is still thinking about it. */
  let wanted = $state('');
  let said = $state<Answer | null>(null);
  let asking = $state(false);
  let nothing = $state(false);
  /** Whether there is no model for the direction at all, which is a different thing to be
   *  told than that no word came back. */
  let unmodelled = $state(false);

  async function ask(text: string) {
    wanted = text;
    said = null;
    nothing = false;
    unmodelled = false;
    if (!text || !say || !learning) return;
    asking = true;
    const came = await say(text, learning).catch(() => null);
    asking = false;
    said = came?.answer ?? null;
    unmodelled = came?.missing ?? false;
    nothing = said === null && !unmodelled;
  }

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

  // What is drawn over a word, and what each choice does, from data/choices.json: the words
  // are the choice, and they are the same words on the phone.
  const layers = LAYER_CHOICES;
  let layerName = $derived(labelOf(LAYER_CHOICES, settings.layer));

  // The languages a reader can pick between, by the name they know them under.
  const named = Object.entries(LANGUAGES)
    .map(([code, row]) => ({ code, english: row.english, native: row.native }))
    .sort((a, b) => a.english.localeCompare(b.english));

  let readInto = $derived([
    { value: '', label: SAYS['nothing-yet'] },
    ...named.map((it) => ({ value: it.code, label: `${it.english} · ${it.native}` })),
  ]);
  let pageIs = $derived([
    { value: '', label: 'what the page says' },
    ...named.map((it) => ({ value: it.code, label: it.english })),
  ]);

  /** How much dictionary there is behind this page, which is what the answers will be worth. */
  let words = $derived(
    (() => {
      const pack = packs.offered.find((row) => row.lang === reading);
      if (!pack || !packs.held.includes(reading)) return '';
      return pack.entries >= 1000
        ? `${Math.round(pack.entries / 1000)}k words`
        : `${pack.entries} words`;
    })()
  );

  /** Which language the reader is learning, for a question asked away from a page: what the
   *  page in front of them is in where there is one, and otherwise whatever they keep a
   *  dictionary for. A reader with a Spanish pack is learning Spanish. */
  let learning = $derived(
    reading || packs.held.find((lang) => lang !== settings.target) || ''
  );

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
      : ''}{words ? ` · ${words}` : ''}{settings.target
      ? ` · read into ${nameOf(settings.target)}`
      : ''}"
    open={() => (view = accents.length > 0 ? 'accent' : 'more')}
  />

  <!-- The bar a reader comes back to, on a row of its own. -->
  <Frequency {curve} density={settings.density} change={(at) => change('density', at)} />

  <NavRow
    name={ROWS.layer.name}
    row="layer"
    about={layerName}
    open={() => (view = 'layer')}
  />

  <div class="rows">
    <Row name={ROWS.target.name} row="target">
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
      name={ROWS.accent.name}
      row="accent"
      about="{nameOf(reading)} · {accentName || SAYS['dictionary-accent']}"
      open={() => (view = 'accent')}
    />
  {/if}

  <NavRow
    name={ROWS.dictionaries.name}
    row="dictionaries"
    about={offered > 0
      ? `${held} of ${offered} here, ${packs.open.length} open`
      : settings.host
        ? `nothing on offer at ${settings.host}`
        : SAYS['no-source']}
    open={() => (view = 'packs')}
  />

  <!-- The other direction. Everything above answers a word somebody else wrote; this one
       answers a word the reader is looking for. -->
  <NavRow
    name={ROWS.say.name}
    row="say"
    about={learning && settings.target && learning !== settings.target
      ? `into ${nameOf(learning)}`
      : SAYS['say-no-language']}
    open={() => (view = 'say')}
  />

  <NavRow
    name={ROWS.more.name}
    row="more"
    about={ROWS.more.about}
    open={() => (view = 'more')}
  />

  <p class="made">
    Made with <span class="heart"><Heart class="r-icon" /></span>
    {#if version}<span class="version">· v{version}</span>{/if}
  </p>
</Screen>

<Screen
  name="layer"
  on={view}
  title={ROWS.layer.name}
  back={() => (view = 'main')}
>
  <Choice
    options={layers}
    chosen={settings.layer}
    change={(value) => change('layer', value as Layer)}
  />
</Screen>

<Screen
  name="accent"
  on={view}
  title="{nameOf(reading)} {ROWS.accent.name.toLowerCase()}"
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
      <span>{SAYS['dictionary-accent']}</span>
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
  title={ROWS.dictionaries.name}
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

<Screen name="say" on={view} title={ROWS.say.name} back={() => (view = 'main')}>
  <p class="about">{ROWS.say.about}</p>
  <div class="rows">
    <!-- The field carries the whole row: the screen it is on is named after it already, and
         a row that repeats its own screen's title is the title twice. -->
    <Row name="" row="say-field">
      {#snippet wide()}
        <Field
          value={wanted}
          label={ROWS.say.name}
          placeholder={SAYS['say-placeholder']}
          change={(text) => void ask(text)}
        />
      {/snippet}
    </Row>
  </div>
  {#if !learning || !settings.target || learning === settings.target}
    <p class="about">{SAYS['say-no-language']}</p>
  {/if}
  <!-- The answer is the card the rest of the product answers with, so what a machine gave
       back can be judged the same way: how it is said, what it means back, and the mark that
       says a machine said it. -->
  {#if said}
    <div class="answer">
      <AnswerCard answer={said} />
    </div>
  {:else if asking}
    <p class="about">…</p>
  {:else if unmodelled && wanted}
    <p class="about">{SAYS['say-no-model']}</p>
  {:else if nothing && wanted}
    <p class="about">{SAYS['say-nothing']}</p>
  {/if}
</Screen>

<Screen name="more" on={view} title={ROWS.more.name} back={() => (view = 'main')}>
  <div class="rows">
    <Row
      name={ROWS.source.name}
      row="source"
      about={ROWS.source.about}
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
      name={ROWS.narrow.name}
      row="narrow"
      about={aboutOf(DETAIL_CHOICES, settings.narrow ? 'narrow' : 'broad')}
    >
      {#snippet control()}
        <Segmented
          choices={DETAIL_CHOICES}
          chosen={settings.narrow ? 'narrow' : 'broad'}
          change={(value) => change('narrow', value === 'narrow')}
        />
      {/snippet}
    </Row>

    <Row
      name={ROWS.stress.name}
      row="stress"
      about={ROWS.stress.about}
    >
      {#snippet control()}
        <Toggle
          on={!settings.hideStress}
          label="stress marks"
          change={(on) => change('hideStress', !on)}
        />
      {/snippet}
    </Row>

    <Row name={ROWS.delay.name} row="delay" says="{settings.delay} ms">
      {#snippet wide()}
        <Slider
          value={settings.delay}
          max={1000}
          step={50}
          label="rest before a card opens"
          ends={ENDS.delay}
          change={(ms) => change('delay', ms)}
        />
      {/snippet}
    </Row>

    <Row name={ROWS.animations.name} row="animations" about={ROWS.animations.about}>
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
