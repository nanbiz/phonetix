<script lang="ts">
  import Dropdown from "./Dropdown.svelte";
  import Info from "virtual:icons/line-md/alert-circle";

  import { sendMessage } from "@/lib/messaging"

  import { Languages, LanguageNames, AccentsByLanguage, DefaultAccents } from "@/lib/types"
  import { ACCENTS } from "@/lib/accents"
  import { DENSITY_MIN, DENSITY_MAX, densityForPos, posForDensity } from "@/lib/sprinkle"
  import type { LanguageOption, Mode } from "@/lib/types"

  let selectedLanguage = $state<LanguageOption>("auto");
  let detectedLanguage = $state<string>("en");
  /** Voice per language. A page can carry several languages at once, so an
   *  accent is only meaningful relative to one of them. */
  let accents = $state<Record<string, string>>({});
  let selectedMode = $state<Mode>("showOriginalOnHover");
  /** Sprinkle density: transcribe one in every N words (2 = half, 50 = one in fifty). */
  let sprinkleDensity = $state(12);
  let initialized = $state(false);
  let dictManifest = $state<Record<string, { entries: number; sizeKB: number }>>({});

  // The actual language being used (resolved from auto or manual)
  let effectiveLanguage = $derived<string>(
    selectedLanguage === 'auto' ? detectedLanguage : selectedLanguage as string
  );

  // Accents are a standing per-language preference, not a property of the page:
  // only the languages that actually offer a choice get a row.
  const accentChoices = Object.entries(AccentsByLanguage)
    .filter(([, opts]) => Object.keys(opts).length > 1)
    .sort(([a], [b]) => (LanguageNames[a] || a).localeCompare(LanguageNames[b] || b));

  function accentOf(lang: string): string {
    return accents[lang] || DefaultAccents[lang] || lang;
  }

  // The accent for the language actually on screen is the one worth showing; the
  // others are a standing preference you rarely revisit.
  let pageAccent = $derived<Record<string, string> | null>(
    Object.keys(AccentsByLanguage[effectiveLanguage] || {}).length > 1
      ? AccentsByLanguage[effectiveLanguage]
      : null,
  );
  let otherAccentChoices = $derived(accentChoices.filter(([lang]) => lang !== effectiveLanguage));

  // An accent with no per-word data behind it is derived from pronunciation rules.
  // Say so, rather than let it look like the same kind of thing as the others — for
  // whichever language's accent is being shown, not only the current one.
  function ruleNoteFor(lang: string): string {
    const chosen = ACCENTS[lang]?.find(a => a.id === accentOf(lang));
    return chosen?.ruleBased
      ? 'No per-word dictionary exists for this accent — it is derived from its pronunciation rules, applied to every word.'
      : '';
  }
  let ruleBasedNote = $derived(ruleNoteFor(effectiveLanguage));

  // Language options: auto first, then every language with the size of the
  // dictionary behind it, so the choice says what it will actually get you.
  let languageOptions = $derived<Record<string, string>>(
    Object.fromEntries([
      ['auto', 'Auto-detect'],
      ...Object.entries(Languages)
        .map(([code, cfg]) => {
          const dict = dictManifest[code];
          const suffix = dict ? ` (${(dict.entries / 1000).toFixed(0)}k words)` : ' (espeak only)';
          return [code, `${cfg.name}${suffix}`] as [string, string];
        })
        .sort((a, b) => a[1].localeCompare(b[1])),
    ]),
  );

  // Load manifest
  (async () => {
    try {
      const res = await fetch(chrome.runtime.getURL('dictionaries/manifest.json'));
      if (res.ok) dictManifest = await res.json();
    } catch {}
  })();

  // Load saved settings
  (async () => {
    const savedLang = await storage.getItem<string>('local:selectedLanguage');
    if (savedLang && (savedLang === 'auto' || savedLang in Languages)) {
      selectedLanguage = savedLang as LanguageOption;
    }

    const detected = await storage.getItem<string>('local:detectedLanguage');
    if (detected && detected in Languages) {
      detectedLanguage = detected as string;
    }

    const savedAccents = await storage.getItem<string>('local:accents');
    if (savedAccents) accents = JSON.parse(savedAccents);

    const savedMode = await storage.getItem<string>('local:selectedMode');
    if (savedMode === 'showOriginalOnHover' || savedMode === 'onHover' || savedMode === 'sprinkle') {
      selectedMode = savedMode as Mode;
    }

    const savedDensity = await storage.getItem<string>('local:sprinkleDensity');
    if (savedDensity) {
      const n = parseInt(savedDensity, 10);
      if (Number.isFinite(n)) sprinkleDensity = Math.min(DENSITY_MAX, Math.max(DENSITY_MIN, n));
    }

    initialized = true;
  })();

  storage.watch<string>('local:detectedLanguage', (newVal) => {
    if (newVal && newVal in Languages) {
      detectedLanguage = newVal as string;
    }
  });

  // Persist + notify on language change. The accent map is keyed by language and
  // survives this: switching language reveals that language's accent, it does not
  // overwrite anything.
  // Every page watches these keys, so writing them is all it takes: a message
  // would reach only the active tab, and only if the popup agreed with the
  // browser about which tab that is.
  // Persist only a real change. Writing the just-loaded value back on open would make
  // every tab (which watches these keys) run a full reprocess and flash a re-render for
  // nothing, so the write that the load itself triggers is skipped.
  let firstLangWrite = true;
  $effect(() => {
    const v = selectedLanguage;
    if (!initialized) return;
    if (firstLangWrite) { firstLangWrite = false; return; }
    storage.setItem<string>('local:selectedLanguage', v);
  });

  // Hidden stress marks and narrow detail are on by default; an unset key is the
  // default, not off. These must match the same defaults in content.ts.
  let hideStress = $state(true);
  (async () => {
    const saved = await storage.getItem<string>('local:hideStress');
    if (saved !== null && saved !== undefined) hideStress = saved === 'true';
  })();

  async function setHideStress(hide: boolean) {
    hideStress = hide;
    await storage.setItem<string>('local:hideStress', String(hide));
  }

  let narrow = $state(true);
  (async () => {
    const saved = await storage.getItem<string>('local:narrow');
    if (saved !== null && saved !== undefined) narrow = saved === 'true';
  })();

  async function setNarrow(on: boolean) {
    narrow = on;
    await storage.setItem<string>('local:narrow', String(on));
  }

  // Animations are off by default; the reader turns them on here.
  let animations = $state(false);
  (async () => {
    const saved = await storage.getItem<string>('local:animations');
    if (saved !== null && saved !== undefined) animations = saved === 'true';
  })();

  async function setAnimations(on: boolean) {
    animations = on;
    await storage.setItem<string>('local:animations', String(on));
  }

  // How long the cursor rests on a word before its tooltip opens, 0–1000ms.
  let hoverDelay = $state(200);
  (async () => {
    const saved = await storage.getItem<string>('local:hoverDelay');
    const n = Number(saved);
    if (saved !== null && saved !== undefined && saved !== '' && Number.isFinite(n)) {
      hoverDelay = Math.min(1000, Math.max(0, n));
    }
  })();

  function setHoverDelay(ms: number) {
    hoverDelay = ms;
    storage.setItem<string>('local:hoverDelay', String(ms));
  }

  async function setAccent(lang: string, accent: string) {
    accents = { ...accents, [lang]: accent };
    await storage.setItem<string>('local:accents', JSON.stringify(accents));
  }

  // Same skip-the-load-write guard as for the language above.
  let firstModeWrite = true;
  $effect(() => {
    const v = selectedMode;
    if (!initialized) return;
    if (firstModeWrite) { firstModeWrite = false; return; }
    storage.setItem<string>('local:selectedMode', v);
  });

  let firstDensityWrite = true;
  $effect(() => {
    const v = sprinkleDensity;
    if (!initialized) return;
    if (firstDensityWrite) { firstDensityWrite = false; return; }
    storage.setItem<string>('local:sprinkleDensity', String(v));
  });

  // One control replaces the mode + density pair: an "IPA frequency" slider running
  // from Full IPA (every word transcribed, hover brings the word back) at the left,
  // through the sparse sprinkle in the middle, to IPA on Hover (nothing inline, hover
  // brings the IPA up) at the right. It reads and writes the same selectedMode +
  // sprinkleDensity the page already watches, so nothing downstream changes.
  // Left = least IPA (IPA on hover), right = most (Full IPA), so dragging right raises
  // the frequency, which is the way a slider is read. The sprinkle density in between
  // gets denser (a smaller 1-in-N) toward the right.
  //
  // The slider owns its own position (its 100 steps are finer than the ~49 sprinkle
  // densities). Deriving the position back from the rounded density instead would snap
  // the thumb to the nearest representable spot mid-drag — sometimes to the left of where
  // it was dragged. So it is seeded from the stored setting once, then drives the setting.
  const FREQ_MAX = 100;

  // The curve and its inverse live in @/lib/sprinkle, which the page and the Android port
  // both read, so the bar means the same thing everywhere.
  const posToDensity = (v: number) => densityForPos((v - 1) / (FREQ_MAX - 2));
  const densityToPos = (d: number) => 1 + Math.round(posForDensity(d) * (FREQ_MAX - 2));

  let freq = $state(FREQ_MAX);
  let freqSeeded = false;
  $effect(() => {
    const mode = selectedMode, dens = sprinkleDensity;
    if (!initialized || freqSeeded) return;
    freqSeeded = true;
    freq = mode === 'onHover' ? 0
      : mode === 'showOriginalOnHover' ? FREQ_MAX
      : densityToPos(dens);
  });
  function setFreq(v: number) {
    freq = v;   // the thumb sits exactly where it is dragged
    if (v <= 0) {
      selectedMode = 'onHover';
    } else if (v >= FREQ_MAX) {
      selectedMode = 'showOriginalOnHover';
    } else {
      selectedMode = 'sprinkle';
      sprinkleDensity = posToDensity(v);
    }
  }
  let freqLabel = $derived(
    selectedMode === 'showOriginalOnHover' ? 'Full IPA'
    : selectedMode === 'onHover' ? 'IPA on hover'
    : sprinkleDensity <= DENSITY_MIN ? 'Every word'
    : `1 in ${sprinkleDensity} (${Math.round(100 / sprinkleDensity)}%)`,
  );

  let showInfo = $state(false);
  /** The popup is one screen at a time: the accent has too many choices to sit in a
   *  menu, and the detailed settings live behind their own button so the first screen
   *  stays short. */
  let view = $state<'main' | 'accent' | 'settings'>('main');

  let accentLabel = $derived(
    pageAccent ? pageAccent[accentOf(effectiveLanguage)] || '' : '',
  );

  // Stats
  let totalDictLangs = $derived(Object.keys(dictManifest).length);
  let totalWords = $derived(Object.values(dictManifest).reduce((s, d) => s + d.entries, 0));
  let effectiveHasDict = $derived(effectiveLanguage in dictManifest);

  // Subsystem health — surfaces a silently-failed engine (as eld once was) here
  // in the popup, on whichever browser the user is running.
  let health = $state<{ eld: boolean; dict: boolean; espeak: boolean; errors: string[] } | null>(null);
  (async () => {
    try { health = await sendMessage('getHealth', {}); }
    catch (e) { health = { eld: false, dict: false, espeak: false, errors: [String(e)] }; }
  })();
  let unhealthy = $derived(!!health && !(health.eld && health.dict && health.espeak));

  // Optional remote dictionary pack host. Stored at runtime only; never in source.
  let packUrl = $state<string>('');
  (async () => { packUrl = (await storage.getItem<string>('local:packBaseUrl')) || ''; })();
  function savePackUrl() { storage.setItem('local:packBaseUrl', packUrl.trim() || (null as any)); }
  let downList = $derived(
    !health ? '' : [
      health.eld ? '' : 'language detection',
      health.dict ? '' : 'dictionaries',
      health.espeak ? '' : 'espeak',
    ].filter(Boolean).join(', '),
  );
</script>

{#if view === 'accent'}
  <!-- Accent view: every accent for this language, and what is behind it. -->
  <div class="w-full space-y-3">
    <button
      class="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-200"
      onclick={() => (view = 'main')}
    >
      ‹ Back
    </button>

    <h2 class="text-base font-medium text-white">
      {LanguageNames[effectiveLanguage] || effectiveLanguage} accent
    </h2>

    {#if pageAccent}
      <div class="flex flex-col gap-1">
        {#each Object.entries(pageAccent) as [id, label] (id)}
          <button
            class="flex w-full items-center justify-between rounded-lg border px-3 py-2 text-left text-sm
                   {accentOf(effectiveLanguage) === id
                     ? 'border-blue-500 bg-blue-600/15 text-white'
                     : 'border-gray-700 text-gray-300 hover:border-gray-500'}"
            onclick={() => setAccent(effectiveLanguage, id)}
          >
            <span>{label}</span>
            {#if accentOf(effectiveLanguage) === id}<span class="text-blue-400">✓</span>{/if}
          </button>
        {/each}
      </div>
      {#if ruleBasedNote}
        <p class="text-xs text-gray-400">{ruleBasedNote}</p>
      {/if}
    {:else}
      <p class="text-sm text-gray-500">
        This language has one pronunciation standard, so there is no accent to choose.
      </p>
    {/if}

    {#if otherAccentChoices.length}
      <details class="pt-1">
        <summary class="cursor-pointer select-none text-xs text-gray-400">Other languages</summary>
        <div class="mt-2 flex flex-col gap-2">
          {#each otherAccentChoices as [lang, options] (lang)}
            <div>
              <Dropdown
                topic={LanguageNames[lang] || lang}
                selectedElement={accentOf(lang)}
                elements={options}
                onElementChange={(accent) => setAccent(lang, accent)}
              />
              {#if ruleNoteFor(lang)}
                <p class="mt-1 text-xs text-gray-400">{ruleNoteFor(lang)}</p>
              {/if}
            </div>
          {/each}
        </div>
      </details>
    {/if}
  </div>
{:else if view === 'main'}
<div class="w-full space-y-4">
  {#if unhealthy}
    <div class="text-xs text-red-300 bg-red-950/50 border border-red-900/60 rounded px-2 py-1.5">
      ⚠ {downList} unavailable — reload the page or reinstall.
    </div>
  {/if}
  <!-- The language and its accent are one thing: what this page is being read as.
       Choosing the accent opens a view of its own rather than a menu. -->
  <button
    class="flex w-full items-center justify-between gap-3 rounded-lg border border-gray-700 bg-gray-800 px-3 py-2.5 text-left hover:border-blue-400"
    onclick={() => (view = 'accent')}
  >
    <span class="min-w-0">
      <span class="block truncate text-sm font-medium text-white">
        {LanguageNames[effectiveLanguage] || effectiveLanguage}
        {#if accentLabel}<span class="text-gray-400"> · {accentLabel}</span>{/if}
      </span>
      <span class="block truncate text-xs text-gray-400">
        {selectedLanguage === 'auto' ? 'detected' : 'set by you'}
        {#if effectiveHasDict}
          · {(dictManifest[effectiveLanguage]?.entries || 0).toLocaleString()} words
        {:else}
          · espeak synthesis only
        {/if}
      </span>
    </span>
    <span class="flex-none text-gray-500">›</span>
  </button>

  <!-- One control instead of a mode picker plus a density slider: how much of the page
       is transcribed, from none until hovered (left) up to all of it (right). -->
  <div class="px-1">
    <div class="mb-1 flex items-center justify-between gap-3">
      <span class="text-sm font-medium text-gray-200">IPA frequency</span>
      <span class="rounded-md bg-blue-500/15 px-2 py-0.5 text-xs font-medium tabular-nums text-blue-300">{freqLabel}</span>
    </div>
    <input
      type="range"
      min="0"
      max={FREQ_MAX}
      step="1"
      value={freq}
      oninput={(e) => setFreq(Number((e.currentTarget as HTMLInputElement).value))}
      class="range range-primary range-sm w-full"
    />
    <div class="mt-1 flex justify-between text-xs text-gray-500">
      <span>IPA on hover</span>
      <span>Full IPA</span>
    </div>
  </div>

  <button
    class="flex w-full items-center justify-between gap-3 rounded-lg border border-gray-700 px-3 py-2.5 text-left text-sm text-gray-300 hover:border-gray-500"
    onclick={() => (view = 'settings')}
  >
    <span class="flex items-center gap-2">
      <svg class="size-4 text-gray-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
      Settings
    </span>
    <span class="flex-none text-gray-500">›</span>
  </button>
</div>

{:else}
<!-- Settings sub-screen: everything that is not the day-to-day frequency choice. -->
<div class="w-full space-y-4">
  <button
    class="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-200"
    onclick={() => (view = 'main')}
  >
    ‹ Back
  </button>

  <label class="flex cursor-pointer items-center justify-between gap-3 px-1">
    <span class="text-sm text-gray-300">
      Narrow transcription
      <span class="block text-xs text-gray-400">Keeps fine detail like aspiration and devoicing (kʰ, z̥); off shows the broad form</span>
    </span>
    <input
      type="checkbox"
      class="toggle toggle-primary toggle-sm flex-none"
      checked={narrow}
      onchange={(e) => setNarrow((e.currentTarget as HTMLInputElement).checked)}
    />
  </label>

  <label class="flex cursor-pointer items-center justify-between gap-3 px-1">
    <span class="text-sm text-gray-300">
      Hide stress marks
      <span class="block text-xs text-gray-400">Leaves out ˈ and ˌ; the tooltip still shows them</span>
    </span>
    <input
      type="checkbox"
      class="toggle toggle-primary toggle-sm flex-none"
      checked={hideStress}
      onchange={(e) => setHideStress((e.currentTarget as HTMLInputElement).checked)}
    />
  </label>

  <label class="flex cursor-pointer items-center justify-between gap-3 px-1">
    <span class="text-sm text-gray-300">
      Animations
      <span class="block text-xs text-gray-400">The tooltip, the reveal and the diagram ease in; off is instant</span>
    </span>
    <input
      type="checkbox"
      class="toggle toggle-primary toggle-sm flex-none"
      checked={animations}
      onchange={(e) => setAnimations((e.currentTarget as HTMLInputElement).checked)}
    />
  </label>

  <div class="px-1">
    <div class="mb-1 flex items-center justify-between gap-3">
      <span class="text-sm text-gray-300">Tooltip delay</span>
      <span class="rounded-md bg-blue-500/15 px-2 py-0.5 text-xs font-medium tabular-nums text-blue-300">
        {hoverDelay} ms
      </span>
    </div>
    <span class="mb-2.5 block text-xs text-gray-400">How long to rest on a word before its tooltip opens</span>
    <input
      type="range"
      min="0"
      max="1000"
      step="50"
      value={hoverDelay}
      oninput={(e) => setHoverDelay(Number((e.currentTarget as HTMLInputElement).value))}
      class="range range-primary range-sm"
    />
    <div class="mt-1 flex justify-between text-xs text-gray-500 tabular-nums">
      <span>Instant</span>
      <span>1000 ms</span>
    </div>
  </div>

  <details class="mt-2 px-1">
    <summary class="text-xs text-gray-400 cursor-pointer select-none">Advanced</summary>

    <div class="mt-2">
      <Dropdown
        topic="Force a language"
        selectedElement={selectedLanguage}
        elements={languageOptions}
        onElementChange={(lang) => (selectedLanguage = lang as LanguageOption)}
      />
      <p class="mt-1 text-xs text-gray-400">
        Each block of the page is detected on its own, so a page in several languages
        already reads correctly. Set this only for a page detection gets wrong.
      </p>
    </div>

    <label class="block mt-2 text-xs text-gray-400">
      Dictionary pack host (optional)
      <input
        type="url"
        bind:value={packUrl}
        onchange={savePackUrl}
        placeholder="https://…"
        class="mt-1 w-full text-xs bg-transparent border border-gray-700 rounded px-2 py-1 text-white"
      />
    </label>
    <p class="mt-1 text-xs text-gray-400">
      Fetch dictionaries from a remote host (cached offline after first use). Empty
      uses the bundled dictionaries.
    </p>
  </details>

  <div class="px-1">
    <button
      class="flex items-center gap-2 text-sm text-gray-400 hover:text-gray-300 transition-colors"
      onclick={() => showInfo = !showInfo}
    >
      <Info class="w-4 h-4 flex-shrink-0" />
      <span>How does Phonetix work?</span>
    </button>

    {#if showInfo}
      <div class="mt-2 text-xs text-gray-400 bg-gray-800/60 rounded-lg p-3 space-y-2.5">
        <div class="flex items-start gap-2">
          <span class="text-blue-400 mt-0.5 flex-shrink-0">1.</span>
          <p>
            <strong class="text-gray-400">Wiktionary dictionaries</strong> are bundled for
            <strong class="text-gray-400">{totalDictLangs} languages</strong>
            ({(totalWords / 1000000).toFixed(1)}M words total).
            These provide human-curated, accurate IPA pronunciations.
          </p>
        </div>
        <div class="flex items-start gap-2">
          <span class="text-blue-400 mt-0.5 flex-shrink-0">2.</span>
          <p>
            Words not found in the dictionary fall back to
            <strong class="text-gray-400">espeak-ng</strong>, an open-source speech
            synthesizer that can generate IPA for any word.
          </p>
        </div>
        <div class="flex items-start gap-2">
          <span class="text-blue-400 mt-0.5 flex-shrink-0">3.</span>
          <p>
            When you <strong class="text-gray-400">hover a word</strong>, the tooltip
            fetches additional details from Wiktionary (audio, exact pronunciation).
          </p>
        </div>
      </div>
    {/if}
  </div>
</div>
{/if}
