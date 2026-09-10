<script lang="ts">
  // What a reader changes, and nothing else.
  //
  // The controls are daisyUI's and the icons are icon sets compiled to components. Nothing
  // here is drawn by hand: a segmented control cut out of spans is not a control, it is a
  // paragraph that happens to react - it takes the text cursor, it drags into a selection,
  // and its states have to be kept legible by this repository rather than by a library that
  // does nothing else. What is ours is the reading surface, where a colour has to match the
  // page it is drawn over; a settings screen has no such constraint and is better off native.
  import Eye from 'virtual:icons/pixelarticons/eye';
  import Volume from 'virtual:icons/pixelarticons/volume-2';
  import Download from 'virtual:icons/pixelarticons/download';
  import Close from 'virtual:icons/pixelarticons/close';
  import Loader from 'virtual:icons/line-md/loading-twotone-loop';

  import { accentsOf } from '@/data/accents';
  import { LANGUAGES } from '@/data/languages';
  import type { Layer } from '@/ext/content/inline';
  import type { Settings } from '@/settings';
  import type { Offered } from '@/host/packs';

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
  }

  let { settings, curve, packs, change, get, forget, fetching = null, site = '', pageLang = '', onSite }:
    Props = $props();

  let here = $derived(site !== '' && !settings.off.includes(site));
  // What the page is in decides which accents there are to choose between.
  let accents = $derived(accentsOf(settings.source || pageLang || ''));

  /** A size a reader can weigh, since the whole point of a dictionary row is deciding
   *  whether to spend it. */
  function size(bytes: number): string {
    if (bytes >= 1024 * 1024) return `${Math.round(bytes / (1024 * 1024))} MB`;
    if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${bytes} B`;
  }

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

  /** Where on the bar the reader's density sits, which is what the slider is set to. */
  let position = $derived(
    curve.length === 0
      ? 0
      : curve.reduce(
          (best, density, at) =>
            Math.abs(density - settings.density) < Math.abs(curve[best] - settings.density)
              ? at
              : best,
          0
        )
  );

  function slid(event: Event) {
    const at = Number((event.currentTarget as HTMLInputElement).value);
    change('density', curve[at] ?? settings.density);
  }
</script>

<div class="flex flex-col divide-y divide-base-300">
  <div class="flex items-center justify-between gap-3 px-4 py-3" data-row="on">
    <span class="font-medium" data-name>Annotate what I read</span>
    <input
      type="checkbox"
      class="toggle toggle-primary"
      aria-label="annotate what I read"
      checked={settings.on}
      onchange={() => change('on', !settings.on)}
    />
  </div>

  {#if site}
    <!-- One site, rather than everywhere: a reader who does not want this on their bank does
         not want to switch it off on the web. -->
    <div class="flex items-center justify-between gap-3 px-4 py-3" data-row="site">
      <span class="min-w-0 truncate font-medium" data-name>On {site}</span>
      <input
        type="checkbox"
        class="toggle toggle-primary"
        aria-label="on {site}"
        checked={here}
        onchange={() => onSite?.(!here)}
      />
    </div>
  {/if}

  <div class="flex flex-col gap-2 px-4 py-3" data-row="layer">
    <span class="flex items-center gap-2 font-medium" data-name><Eye class="size-4 opacity-60" />Show over a word</span>
    <div class="join w-full">
      {#each layers as choice (choice.value)}
        <button
          type="button"
          class="btn join-item btn-xs flex-1 {settings.layer === choice.value ? 'btn-primary' : ''}"
          data-choice={choice.value}
          aria-pressed={settings.layer === choice.value}
          onclick={() => change('layer', choice.value)}
        >{choice.label}</button>
      {/each}
    </div>
  </div>

  <div class="flex flex-col gap-1 px-4 py-3" data-row="density">
    <div class="flex items-baseline justify-between">
      <span class="font-medium" data-name>How often</span>
      <span class="text-sm opacity-60" data-about>one word in {settings.density}</span>
    </div>
    <input
      class="range range-primary range-xs"
      type="range"
      min="0"
      max={Math.max(0, curve.length - 1)}
      value={position}
      aria-label="how often"
      oninput={slid}
    />
  </div>

  <div class="flex items-center justify-between gap-3 px-4 py-3" data-row="target">
    <span class="font-medium" data-name>I read into</span>
    <select
      class="select select-sm max-w-48 cursor-pointer"
      aria-label="I read into"
      value={settings.target}
      onchange={(e) => change('target', (e.currentTarget as HTMLSelectElement).value)}
    >
      <option value="">nothing yet</option>
      {#each named as language (language.code)}
        <option value={language.code}>{language.english} · {language.native}</option>
      {/each}
    </select>
  </div>

  <div class="flex items-center justify-between gap-3 px-4 py-3" data-row="source">
    <span class="font-medium" data-name>This page is in</span>
    <select
      class="select select-sm max-w-48 cursor-pointer"
      aria-label="this page is in"
      value={settings.source}
      onchange={(e) => change('source', (e.currentTarget as HTMLSelectElement).value)}
    >
      <option value="">what the page says</option>
      {#each named as language (language.code)}
        <option value={language.code}>{language.english}</option>
      {/each}
    </select>
  </div>

  {#if accents.length > 0}
    <!-- Only where there is something real to offer: a voice that exists, or a rule that holds
         for the whole vocabulary. A list of accents that all sound the same would be a list of
         promises. -->
    <div class="flex items-center justify-between gap-3 px-4 py-3" data-row="accent">
      <span class="flex items-center gap-2 font-medium" data-name><Volume class="size-4 opacity-60" />Accent</span>
      <select
        class="select select-sm max-w-48 cursor-pointer"
        aria-label="accent"
        value={settings.accent}
        onchange={(e) => change('accent', (e.currentTarget as HTMLSelectElement).value)}
      >
        <option value="">as the dictionary gives it</option>
        {#each accents as accent (accent.id)}
          <option value={accent.id}>{accent.name}</option>
        {/each}
      </select>
    </div>
  {/if}

  <div class="flex items-center justify-between gap-3 px-4 py-3" data-row="narrow">
    <span class="flex flex-col">
      <span class="font-medium" data-name>Transcriptions</span>
      <span class="text-sm opacity-60">
        {settings.narrow ? 'every detail of how it is said' : 'the sounds that tell words apart'}
      </span>
    </span>
    <div class="join">
      <button
        type="button"
        class="btn join-item btn-xs {settings.narrow ? '' : 'btn-primary'}"
        data-choice="broad"
        aria-pressed={!settings.narrow}
        onclick={() => change('narrow', false)}
      >broad</button>
      <button
        type="button"
        class="btn join-item btn-xs {settings.narrow ? 'btn-primary' : ''}"
        data-choice="narrow"
        aria-pressed={settings.narrow}
        onclick={() => change('narrow', true)}
      >narrow</button>
    </div>
  </div>

  <div class="flex items-center justify-between gap-3 px-4 py-3" data-row="stress">
    <span class="flex flex-col">
      <span class="font-medium" data-name>Stress marks</span>
      <span class="text-sm opacity-60">over a word; the card always shows them</span>
    </span>
    <input
      type="checkbox"
      class="toggle toggle-primary"
      aria-label="stress marks"
      checked={!settings.hideStress}
      onchange={() => change('hideStress', !settings.hideStress)}
    />
  </div>

  <div class="flex flex-col gap-2 px-4 py-3" data-row="dictionaries">
    <div class="flex items-baseline justify-between">
      <span class="font-medium" data-name>Dictionaries</span>
      <span class="text-sm opacity-60">
        {#if packs.offered.length === 0}
          <!-- The list comes from wherever the reader said their dictionaries live, and that
               is theirs to set: an extension that went looking on its own would be an
               extension deciding who to talk to. -->
          no source for them yet
        {:else}
          {packs.held.length} of {packs.offered.length} here, {packs.open.length} open
        {/if}
      </span>
    </div>

    <!-- One row per dictionary: what it is, what it costs, and the one thing to do with it. -->
    {#each packs.offered as pack (pack.lang)}
      <div class="flex items-center justify-between gap-3" data-row="pack" data-lang={pack.lang}>
        <span class="flex min-w-0 flex-col">
          <span class="truncate" data-name>{LANGUAGES[pack.lang]?.english ?? pack.lang}</span>
          <span class="text-xs opacity-60" data-about>
            {pack.entries.toLocaleString()} words · {size(pack.bytes)}
          </span>
        </span>
        {#if fetching === pack.lang}
          <span class="btn btn-ghost btn-xs" aria-label="fetching"><Loader class="size-4" /></span>
        {:else if packs.held.includes(pack.lang)}
          <button class="btn btn-ghost btn-xs" data-does="remove"
            onclick={() => forget?.(pack.lang)}>
            <Close class="size-4" />remove
          </button>
        {:else}
          <button class="btn btn-xs" data-does="get" onclick={() => get?.(pack.lang)}>
            <Download class="size-4" />get
          </button>
        {/if}
      </div>
    {/each}
  </div>
</div>
