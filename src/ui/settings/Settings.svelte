<script lang="ts">
  // What a reader changes, and nothing else.
  //
  // Every control here is one setting: the panel, its rows and the controls in them are the
  // design page's own, so the settings a reader meets in a browser and the ones on a phone are
  // the same surface.
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
    onSite?: (on: boolean) => void;
    /** Fetch a language's dictionary, or give one up. */
    get?: (lang: string) => void;
    forget?: (lang: string) => void;
    /** Which language is being fetched right now, so the row can say so. */
    fetching?: string | null;
  }

  let { settings, curve, packs, change, get, forget, fetching = null, site = '', onSite }:
    Props = $props();

  let here = $derived(site !== '' && !settings.off.includes(site));

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

<div class="panel">
  <h4>Phonetix<span class="h4-note">{settings.on ? 'reading' : 'off'}</span></h4>
  <div class="rows">
    <div class="row">
      <span class="r-name">Annotate what I read</span>
      <span class="r-act">
        <button
          class="toggle {settings.on ? 'on' : ''}"
          aria-label="annotate what I read"
          aria-pressed={settings.on}
          onclick={() => change('on', !settings.on)}
        ></button>
      </span>
    </div>

    {#if site}
      <div class="row">
        <!-- One site, rather than everywhere: a reader who does not want this on their bank
             does not want to switch it off on the web. -->
        <span class="r-name">On {site}</span>
        <span class="r-act">
          <button
            class="toggle {here ? 'on' : ''}"
            aria-label="on {site}"
            aria-pressed={here}
            onclick={() => onSite?.(!here)}
          ></button>
        </span>
      </div>
    {/if}

    <div class="row">
      <span class="r-name">Show over a word</span>
      <span class="r-wide">
        <span class="seg">
          {#each layers as choice (choice.value)}
            <span
              class={settings.layer === choice.value ? 'on' : ''}
              role="button"
              tabindex="0"
              onclick={() => change('layer', choice.value)}
              onkeydown={(e) => e.key === 'Enter' && change('layer', choice.value)}
            >{choice.label}</span>
          {/each}
        </span>
      </span>
    </div>

    <div class="row">
      <span class="r-name">How often</span>
      <span class="r-sub">one word in {settings.density}</span>
      <span class="r-wide">
        <input
          class="slider"
          type="range"
          min="0"
          max={Math.max(0, curve.length - 1)}
          value={position}
          aria-label="how often"
          oninput={slid}
        />
      </span>
    </div>

    <div class="row">
      <span class="r-name">I read into</span>
      <span class="r-act">
        <select
          class="select"
          aria-label="I read into"
          value={settings.target}
          onchange={(e) => change('target', (e.currentTarget as HTMLSelectElement).value)}
        >
          <option value="">nothing yet</option>
          {#each named as language (language.code)}
            <option value={language.code}>{language.english} · {language.native}</option>
          {/each}
        </select>
      </span>
    </div>

    <div class="row">
      <span class="r-name">This page is in</span>
      <span class="r-act">
        <select
          class="select"
          aria-label="this page is in"
          value={settings.source}
          onchange={(e) => change('source', (e.currentTarget as HTMLSelectElement).value)}
        >
          <option value="">what the page says</option>
          {#each named as language (language.code)}
            <option value={language.code}>{language.english}</option>
          {/each}
        </select>
      </span>
    </div>

    <div class="row">
      <span class="r-name">Transcriptions</span>
      <span class="r-sub">
        {settings.narrow ? 'every detail of how it is said' : 'the sounds that tell words apart'}
      </span>
      <span class="r-act">
        <span class="seg">
          <span
            class={settings.narrow ? '' : 'on'}
            role="button"
            tabindex="0"
            onclick={() => change('narrow', false)}
            onkeydown={(e) => e.key === 'Enter' && change('narrow', false)}
          >broad</span>
          <span
            class={settings.narrow ? 'on' : ''}
            role="button"
            tabindex="0"
            onclick={() => change('narrow', true)}
            onkeydown={(e) => e.key === 'Enter' && change('narrow', true)}
          >narrow</span>
        </span>
      </span>
    </div>

    <div class="row">
      <span class="r-name">Stress marks</span>
      <span class="r-sub">over a word; the card always shows them</span>
      <span class="r-act">
        <button
          class="toggle {settings.hideStress ? '' : 'on'}"
          aria-label="stress marks"
          aria-pressed={!settings.hideStress}
          onclick={() => change('hideStress', !settings.hideStress)}
        ></button>
      </span>
    </div>

    <div class="row">
      <span class="r-name">Dictionaries</span>
      <span class="r-sub">
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
      <div class="row">
        <span class="r-name">{LANGUAGES[pack.lang]?.english ?? pack.lang}</span>
        <span class="r-sub">
          {pack.entries.toLocaleString()} words · {size(pack.bytes)}
        </span>
        <span class="r-act">
          {#if fetching === pack.lang}
            <span class="chip">fetching</span>
          {:else if packs.held.includes(pack.lang)}
            <button class="btn-text" onclick={() => forget?.(pack.lang)}>remove</button>
          {:else}
            <button class="btn-text" onclick={() => get?.(pack.lang)}>get</button>
          {/if}
        </span>
      </div>
    {/each}
  </div>
</div>
