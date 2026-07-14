<script lang="ts">
  import Dropdown from "./Dropdown.svelte";
  import Segmented from "./Segmented.svelte";
  import Info from "virtual:icons/line-md/alert-circle";
  import Book from "virtual:icons/line-md/document";

  import { sendMessage } from "@/lib/messaging"

  import { Languages, LanguageNames, Modes, ModeLabels, AccentsByLanguage, DefaultAccents } from "@/lib/types"
  import { ACCENTS } from "@/lib/accents"
  import type { LanguageOption, Mode } from "@/lib/types"

  let selectedLanguage = $state<LanguageOption>("auto");
  let detectedLanguage = $state<string>("en");
  /** Voice per language. A page can carry several languages at once, so an
   *  accent is only meaningful relative to one of them. */
  let accents = $state<Record<string, string>>({});
  let selectedMode = $state<Mode>("wholePage");
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
  // Say so, rather than let it look like the same kind of thing as the others.
  let ruleBasedNote = $derived.by(() => {
    const chosen = ACCENTS[effectiveLanguage]?.find(a => a.id === accentOf(effectiveLanguage));
    if (!chosen?.ruleBased) return '';
    return 'No per-word dictionary exists for this accent — it is derived from its pronunciation rules, applied to every word.';
  });

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
    if (savedMode && savedMode in Modes) {
      selectedMode = savedMode as Mode;
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
  $effect(() => {
    if (!initialized) return;
    storage.setItem<string>('local:selectedLanguage', selectedLanguage);
  });

  let hideStress = $state(false);
  (async () => { hideStress = (await storage.getItem<string>('local:hideStress')) === 'true'; })();

  async function setHideStress(hide: boolean) {
    hideStress = hide;
    await storage.setItem<string>('local:hideStress', String(hide));
  }

  async function setAccent(lang: string, accent: string) {
    accents = { ...accents, [lang]: accent };
    await storage.setItem<string>('local:accents', JSON.stringify(accents));
  }

  $effect(() => {
    if (!initialized) return;
    storage.setItem<string>('local:selectedMode', selectedMode);
  });

  let showInfo = $state(false);

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

<div class="w-full space-y-4">
  {#if unhealthy}
    <div class="text-xs text-red-300 bg-red-950/50 border border-red-900/60 rounded px-2 py-1.5">
      ⚠ {downList} unavailable — reload the page or reinstall.
    </div>
  {/if}
  <!-- What the page is being read as. Detection decides this on its own; the
       override lives in Advanced, for the page it gets wrong. -->
  <div class="flex flex-col gap-1 px-1">
    <h2 class="text-sm font-medium text-gray-300">Language</h2>
    {#if selectedLanguage === 'auto'}
      <p class="text-sm text-white">
        {LanguageNames[detectedLanguage] || detectedLanguage}
        <span class="text-xs text-gray-500">(detected)</span>
      </p>
    {:else}
      <p class="text-sm text-white">
        {LanguageNames[effectiveLanguage] || effectiveLanguage}
        <span class="text-xs text-gray-500">(set by you)</span>
      </p>
    {/if}
    <p class="text-xs text-gray-500">
      {#if effectiveHasDict}
        Wiktionary dictionary, {(dictManifest[effectiveLanguage]?.entries || 0).toLocaleString()} words
      {:else}
        No dictionary — espeak synthesis only
      {/if}
    </p>
  </div>

  <!-- The page's own language first, the rest only if asked for. -->
  {#if pageAccent}
    <div class="flex flex-col gap-1">
      <Dropdown
        topic={`${LanguageNames[effectiveLanguage] || effectiveLanguage} accent`}
        selectedElement={accentOf(effectiveLanguage)}
        elements={pageAccent}
        onElementChange={(accent) => setAccent(effectiveLanguage, accent)}
      />
      {#if ruleBasedNote}
        <p class="px-1 text-xs text-gray-500">{ruleBasedNote}</p>
      {/if}
    </div>
  {/if}

  {#if otherAccentChoices.length}
    <details class="px-1">
      <summary class="cursor-pointer select-none text-xs text-gray-500">
        Accents for other languages
      </summary>
      <div class="mt-2 flex flex-col gap-2">
        {#each otherAccentChoices as [lang, options] (lang)}
          <Dropdown
            topic={LanguageNames[lang] || lang}
            selectedElement={accentOf(lang)}
            elements={options}
            onElementChange={(accent) => setAccent(lang, accent)}
          />
        {/each}
      </div>
    </details>
  {/if}

  <Segmented
    topic="Mode"
    selectedElement={selectedMode}
    elements={ModeLabels}
    onElementChange={(mode) => {
      if (mode in Modes) selectedMode = mode as Mode;
    }}
  />

  <label class="flex cursor-pointer items-center justify-between gap-3 px-1">
    <span class="text-sm text-gray-300">
      Hide stress marks
      <span class="block text-xs text-gray-500">Leaves out ˈ and ˌ; the tooltip still shows them</span>
    </span>
    <input
      type="checkbox"
      class="toggle toggle-primary toggle-sm flex-none"
      checked={hideStress}
      onchange={(e) => setHideStress((e.currentTarget as HTMLInputElement).checked)}
    />
  </label>

  <details class="mt-2 px-1">
    <summary class="text-xs text-gray-500 cursor-pointer select-none">Advanced</summary>

    <div class="mt-2">
      <Dropdown
        topic="Force a language"
        selectedElement={selectedLanguage}
        elements={languageOptions}
        onElementChange={(lang) => (selectedLanguage = lang as LanguageOption)}
      />
      <p class="mt-1 text-[10px] text-gray-500">
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
    <p class="mt-1 text-[10px] text-gray-500">
      Fetch dictionaries from a remote host (cached offline after first use). Empty
      uses the bundled dictionaries.
    </p>
  </details>

  <!-- IPA source info -->
  <div class="mt-3 px-1">
    <button
      class="flex items-center gap-2 text-sm text-gray-400 hover:text-gray-300 transition-colors"
      onclick={() => showInfo = !showInfo}
    >
      <Info class="w-4 h-4 flex-shrink-0" />
      <span>How does Phonetix work?</span>
    </button>

    {#if showInfo}
      <div class="mt-2 text-xs text-gray-500 bg-gray-800/60 rounded-lg p-3 space-y-2.5">
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
