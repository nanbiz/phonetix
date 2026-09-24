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
  import { THEME, THEMES } from '@/ui/theme';
  import { SIDES } from '@/ui/palettes';
  import { accentFor, readInto, setAccent, type Settings } from '@/settings/shape';
  import {
    DARK_CHOICES,
    SIDE_CHOICES,
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
  import Resting from './Resting.svelte';
  import Packs from './Packs.svelte';
  import Row from './Row.svelte';
  import Screen from './Screen.svelte';
  import Switchboard from './Switchboard.svelte';
  import Trouble from './Trouble.svelte';
  import Field from '@/ui/controls/Field.svelte';
  import AnswerCard from '@/ui/card/AnswerCard.svelte';
  import type { Answer } from '@/core/answer';

  interface Props {
    /**
     * Which surface this is drawn on.
     *
     * One view for the whole product: the rows a surface cannot do are the ones it does not
     * draw. A browser has sites and a pointer; a phone has apps, permissions and a mark to
     * drag. Everything else - the mode, the language, how often, the palette, the word for
     * something - is the same question on both, asked once here.
     */
    where?: 'browser' | 'phone';
    /** Which screen the reader is on. Bound, so the surface around this view can leave a
     *  screen when the device's own way back is used. */
    view?: string;
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
    /** Whether the device is set to dark, which the surface around this one knows: a web
     *  view inside an app is told light whatever the phone says. */
    device?: boolean;
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
    /** What the phone has been allowed to do, which is what decides whether it can answer a
     *  word at all. Nothing on a browser, which asks for neither. */
    permissions?: { reading: boolean; overlay: boolean };
    onOpenReading?: () => void;
    onOpenOverlay?: () => void;
    /** How many apps are read, for the row that opens the system's own list of them. */
    onOpenApps?: () => void;
  }

  let {
    where = 'browser',
    view = $bindable('main'),
    settings,
    curve,
    packs,
    change,
    get,
    forget,
    fetching = null,
    site = '',
    pageLang = '',
    device = false,
    onSite,
    icon = '',
    siteIcon = '',
    version = '',
    trouble = [],
    say,
    permissions,
    onOpenReading,
    onOpenOverlay,
    onOpenApps,
  }: Props = $props();

  /** Whether the phone may read a screen and draw over it. A browser needs neither and is
   *  ready the moment it is installed. */
  let ready = $derived(
    where === 'browser' || Boolean(permissions?.reading && permissions?.overlay)
  );

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



  /** What a word is replaced by, as the reader now sets it: two switches, either or both.
   *
   *  Stored as the one mode it always was, so nothing else in the product has to know that
   *  the screen asks for it in two pieces. Neither of them on is the replacing off, because a
   *  replacement of nothing is nothing. */
  let saying = $derived(settings.layer === 'sound' || settings.layer === 'both');
  let meaning = $derived(settings.layer === 'meaning' || settings.layer === 'both');
  function layerOf(sound: boolean, gloss: boolean): Layer {
    if (sound && gloss) return 'both';
    if (sound) return 'sound';
    if (gloss) return 'meaning';
    return 'off';
  }

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


  /** The shape of the screen being placed on, which on a phone is the reader's own. */
  let screenAcross = $state(9);
  let screenDown = $state(19.5);
  $effect(() => {
    const across = globalThis.screen?.width ?? 0;
    const down = globalThis.screen?.height ?? 0;
    if (across > 0 && down > 0 && where === 'phone') {
      screenAcross = across;
      screenDown = down;
    }
  });

  // The languages a reader can pick between, by the name they know them under.
  const named = Object.entries(LANGUAGES)
    .map(([code, row]) => ({ code, english: row.english, native: row.native }))
    .sort((a, b) => a.english.localeCompare(b.english));

  /** A language by its English name and its own, once where the two are the same. */
  function bothNames(it: { english: string; native: string }): string {
    return it.native && it.native !== it.english ? `${it.english} · ${it.native}` : it.english;
  }

  /** The languages to learn: every one but the reader's own. */
  let learningChoices = $derived([
    ...(settings.learning ? [] : [{ value: '', label: SAYS['choose-language'] }]),
    ...named
      .filter((it) => it.code !== settings.target)
      .map((it) => ({ value: it.code, label: bothNames(it) })),
  ]);

  let languages = $derived([
    ...(settings.target ? [] : [{ value: '', label: SAYS['choose-language'] }]),
    ...named.map((it) => ({ value: it.code, label: bothNames(it) })),
  ]);
  /** Which side of a palette is being drawn: what the reader asked for, or what the device
   *  is set to where they left it to the device. */
  let side = $derived(settings.dark === 'system' ? (device ? 'dark' : 'light') : settings.dark);

  /**
   * The palettes on offer, which are the ones that have the side in force.
   *
   * Four of the eight are light only or dark only. Offering a light palette to a reader
   * reading in the dark is offering them a choice that cannot be honoured: they pick it and
   * the screen stays as it was, or comes back light in the middle of a dark evening.
   */
  let themes = $derived(
    THEMES.filter((name) => (SIDES[name] ?? []).includes(side)).map((name) => ({
      value: name,
      label: name.charAt(0).toUpperCase() + name.slice(1),
    }))
  );
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
  let into = $derived(readInto(settings));
  /** Which languages this reader could be learning: the ones they keep a dictionary for,
   *  other than the one they read into. */
  let learnable = $derived(packs.held.filter((lang) => lang !== into));
  /** The one they said they are asking in, where there is more than one to choose between. */
  let asked = $state('');
  /**
   * Which language a word is asked for in.
   *
   * The page in front of them where there is one. Otherwise a language they keep a dictionary
   * for - and where they keep several, the one they chose, because guessing at the first of
   * them is how a reader with German and Spanish open was answered in the direction they were
   * not asking about, and told there was no model for it.
   */
  let learning = $derived(reading || (learnable.includes(asked) ? asked : learnable[0]) || '');

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
    ready={ready}
    {here}
    decided={settings.off.includes(site)}
    change={(on) => change('on', on)}
    onSite={(on) => onSite?.(on)}
    onSay={() => (view = 'say')}
    onReady={() => (permissions?.reading ? onOpenOverlay?.() : onOpenReading?.())}
  />

  <Trouble {trouble} />

  {#if where === 'phone'}
    <!-- Where the button sits, under the switch that puts it there: the switch above is the
         button, so this belongs to it rather than standing beside it as a question of its
         own. -->
    <h4 class="head">{ROWS['group-button'].name}</h4>
    <div class="rows">
      <NavRow
        name={ROWS.position.name}
        row="rest"
        small
        about={settings.side === 'free'
          ? SAYS['rest-loose']
          : settings.pin
            ? SAYS['rest-edge'].replace(
                '%s',
                labelOf(SIDE_CHOICES, settings.side).toLowerCase()
              )
            : labelOf(SIDE_CHOICES, settings.side)}
        open={() => (view = 'rest')}
      />
    </div>
  {/if}

  <!-- What the overlay puts over a word: one switch each, and whether it is on at all is the
       press held on the button rather than a third switch here. On the phone, what the card
       and the panel show. -->
  {#if where === 'phone'}<h4 class="head">{ROWS['group-shows'].name}</h4>{/if}
  <div class="rows">
    <Row name={ROWS.ipa.name} row="ipa" about={ROWS.ipa.about}>
      {#snippet control()}
        <Toggle
          on={saying}
          label="how it sounds"
          change={(on) => change('layer', layerOf(on, meaning))}
        />
      {/snippet}
    </Row>

    <Row name={ROWS.translate.name} row="translate" about={ROWS.translate.about}>
      {#snippet control()}
        <Toggle
          on={meaning}
          label="translating"
          change={(on) => change('layer', layerOf(saying, on))}
        />
      {/snippet}
    </Row>

    <!-- The language the words are turned into, asked for only by the modes that turn them
         into one. The mode is already that question's first half, and a switch beside it
         saying the same thing again was a second way to say no. -->
    {#if where === 'browser' && (settings.layer === 'meaning' || settings.layer === 'both')}
      <Row
        name={ROWS.target.name}
        row="target"
        about={reading && settings.target === reading
          ? SAYS['already-in'].replace('%s', nameOf(reading))
          : ROWS.target.about}
      >
        {#snippet control()}
          <Picker
            options={languages}
            chosen={settings.target}
            label={ROWS.target.name}
            change={(value) => change('target', value)}
          />
        {/snippet}
      </Row>
    {/if}
  </div>

  {#if where === 'phone'}
    <h4 class="head">{ROWS['group-languages'].name}</h4>
    <div class="rows">
      <!-- The two languages the side button works between: what a word is translated into,
           and what the panel translates to and from. Always here, because the panel uses
           both whatever the switches say. -->
      <Row name={ROWS.mine.name} row="mine">
        {#snippet control()}
          <Picker
            options={languages}
            chosen={settings.target}
            label={ROWS.mine.name}
            change={(value) => change('target', value)}
          />
        {/snippet}
      </Row>
      <Row name={ROWS.learning.name} row="learning">
        {#snippet control()}
          <Picker
            options={learningChoices}
            chosen={settings.learning}
            label={ROWS.learning.name}
            change={(value) => change('learning', value)}
          />
        {/snippet}
      </Row>
    </div>
  {/if}

  <!-- The bar a reader comes back to, on a row of its own - and only where it decides
       anything: with nothing being replaced there is no how often for it to be. -->
  {#if where === 'browser' && settings.layer !== 'off'}
    <Frequency {curve} density={settings.density} change={(at) => change('density', at)} />
  {/if}

  <div class="rows">
    <NavRow
      name={ROWS.advanced.name}
      row="advanced"
      about={ROWS.advanced.about}
      open={() => (view = 'more')}
    />
  </div>

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

<!-- This page: what it is being read as, and how that language is read. One screen, because
     those are one question - a reader who opens it has noticed something is wrong with how
     this page is being answered. -->
<Screen
  name="page"
  on={view}
  title={reading ? nameOf(reading) : where === 'phone' ? ROWS.accent.name : ROWS.source.name}
  note={words}
  back={() => (view = 'main')}
>
  <div class="rows">
    <Row name="" row="source" about={ROWS.source.about}>
      {#snippet control()}
        <Picker
          options={pageIs}
          chosen={settings.source}
          label={ROWS.source.name}
          change={(value) => change('source', value)}
        />
      {/snippet}
    </Row>
  </div>

  {#if accents.length > 0}
  <h4 class="head">{ROWS.accent.name}<span class="h-note">{nameOf(reading)}</span></h4>
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
  {/if}

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
  />
</Screen>

<Screen name="rest" on={view} title={ROWS.rest.name} back={() => (view = 'main')}>
  <div class="rows">
    <!-- Which side it comes back to, which is the hand the phone is held in. First, because
         it is the answer whether or not the button is pinned: unpinned, coming to rest is the
         shortest way to this side. -->
    <Row name={ROWS.side.name} row="side">
      {#snippet wide()}
        <Segmented
          choices={SIDE_CHOICES.map((row) => ({ value: row.value, label: row.label }))}
          chosen={settings.side}
          change={(value) => change('side', value)}
        />
      {/snippet}
    </Row>

    <!-- Pinned, it waits at one height and nowhere else. Unpinned there is no height to set,
         so the board below has nothing to do and says so by being greyed. Anywhere has no
         height either: the button is left wherever it was put down. -->
    {#if settings.side !== 'free'}
      <Row name={ROWS['rest-pin'].name} row="rest-pin">
        {#snippet control()}
          <Toggle on={settings.pin} label="pinning it" change={(on) => change('pin', on)} />
        {/snippet}
      </Row>
    {/if}
  </div>

  <!-- Where it sits, drawn. Not when it may sit anywhere: there is no one place to show,
       because the button is wherever the reader last put it down. -->
  {#if settings.side !== 'free'}
    <Resting
      y={settings.restY}
      side={settings.side}
      pinned={settings.pin}
      across={screenAcross}
      down={screenDown}
      put={(edge, y) => {
        if (settings.side !== edge) change('side', edge);
        change('restY', Number(y.toFixed(4)));
      }}
    />
  {/if}
</Screen>

<Screen name="say" on={view} title={ROWS.say.name} back={() => (view = 'main')}>
  <p class="about">{ROWS.say.about}</p>
  <div class="rows">
    <!-- Which language the answer comes back in, where this reader keeps more than one
         dictionary and there is no page in front of them to read it off. -->
    {#if !reading && learnable.length > 1}
      <Row name={SAYS['say-into']} row="say-into">
        {#snippet wide()}
          <Segmented
            choices={learnable.map((lang) => ({ value: lang, label: nameOf(lang) }))}
            chosen={learning}
            change={(value) => {
              asked = value;
              if (wanted) void ask(wanted);
            }}
          />
        {/snippet}
      </Row>
    {/if}

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
  {#if !learning || !into || learning === into}
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

<Screen name="more" on={view} title={ROWS.advanced.name} back={() => (view = 'main')}>
  <!-- Where dictionaries come from, and only then the dictionaries themselves.
       What a word means comes out of a dictionary for the language being read, which is the
       one thing the product cannot carry: the pronunciations travel with it, the meanings are
       built from a dump and are far larger. Until a reader says where theirs are there is
       nothing to list, and a screen saying "0 of 0" over an empty box is a screen that exists
       to disappoint. -->
  <div class="rows">
    <!-- The colours everything of ours is drawn in: the card, this view, and the words on the
         page. The product has carried eight palettes since before the merge and offered none
         of them. -->
    <Row name={ROWS.theme.name} row="theme" about={ROWS.theme.about}>
      {#snippet control()}
        <Picker
          options={themes}
          chosen={settings.theme}
          label={ROWS.theme.name}
          change={(value) => change('theme', value)}
        />
      {/snippet}
    </Row>

    <!-- Which side of the palette. Asking for the side a palette does not have moves the
         reader to the product's own, because the alternative is a choice that changes
         nothing: they asked to read in the dark and the screen stayed light. -->
    <Row name={ROWS.dark.name} row="dark">
      {#snippet wide()}
        <Segmented
          choices={DARK_CHOICES.map((row) => ({ value: row.value, label: row.label }))}
          chosen={settings.dark}
          change={(value) => {
            const wanted = value === 'system' ? (device ? 'dark' : 'light') : value;
            if (!(SIDES[settings.theme] ?? []).includes(wanted)) change('theme', THEME);
            change('dark', value);
          }}
        />
      {/snippet}
    </Row>
  </div>

  {#if offered > 0}
    <NavRow
      name={ROWS.dictionaries.name}
      row="dictionaries"
      about={`${held} of ${offered} here, ${packs.open.length} open`}
      open={() => (view = 'packs')}
    />
  {/if}

  <!-- What this page is being read as, and how that language is read. -->
  <NavRow
    name={reading ? nameOf(reading) : where === 'phone' ? ROWS.accent.name : ROWS.source.name}
    row="page"
    about={reading
      ? `${settings.source ? 'set by you' : 'what the page says'}${
          accentName ? ` · ${accentName}` : ''
        }${words ? ` · ${words}` : ''}`
      : `${elsewhere.length} languages offer a choice`}
    open={() => (view = 'page')}
  />

  {#if where === 'phone'}
    <!-- Which apps the side button answers in. The list itself is the system's, with its own
         icons, so it is the one screen the phone draws for itself. -->
    <NavRow
      name={ROWS.apps.name}
      row="apps"
      about={settings.allApps
        ? SAYS['every-app']
        : `${settings.apps.length} app${settings.apps.length === 1 ? '' : 's'} chosen`}
      open={() => onOpenApps?.()}
    />
  {/if}

  <div class="rows">
    <Row
      name={ROWS.narrow.name}
      row="narrow"
      hint={SAYS['detail-explained']}
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

    {#if where === 'browser'}
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
    {/if}
  </div>

  <!-- What actually answers a word, in the order it is asked. A reader deciding whether to
       trust what a card says is deciding it on this, and the card's own pill names which of
       these answered each time. The browser's: the phone's card carries no pill to explain. -->
  {#if where === 'browser'}
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
  {/if}
</Screen>
