<script lang="ts">
  // What a reader changes, and nothing else.
  //
  // Every control here is one setting: the panel, its rows and the controls in them are the
  // design page's own, so the settings a reader meets in a browser and the ones on a phone are
  // the same surface.
  import { LANGUAGES } from '@/data/languages';
  import type { Layer } from '@/ext/content/inline';
  import type { Settings } from '@/settings';

  interface Props {
    settings: Settings;
    /** The densities the bar's positions mean, from the core, so the number a reader sees is
     *  the number the annotation is decided by. */
    curve: number[];
    /** Which languages have a pack on this machine, and which are open. */
    packs: { held: string[]; open: string[] };
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
  }

  let { settings, curve, packs, change }: Props = $props();

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
      <span class="r-name">Dictionaries</span>
      <!-- What is actually here, rather than what could be fetched: a reader deciding whether
           to trust an answer is deciding it against the dictionaries the machine has. -->
      <span class="r-sub">
        {#if packs.held.length === 0}
          none yet
        {:else}
          {packs.held.map((code) => LANGUAGES[code]?.english ?? code).join(', ')}
        {/if}
      </span>
      <span class="r-act"><span class="chip">{packs.open.length} open</span></span>
    </div>
  </div>
</div>
