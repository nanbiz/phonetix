<script lang="ts">
  import Dropdown from "./Dropdown.svelte";
  import MultiSelect from "svelte-multiselect";
  import Info from "virtual:icons/line-md/alert-circle";
  import Book from "virtual:icons/line-md/document";

  import { getCurrentTabId, sendMessage } from "@/lib/messaging"

  import { Languages, LanguageNames, LanguageOptions, Modes, AccentsByLanguage, DefaultAccents } from "@/lib/types"
  import type { LanguageOption, Mode } from "@/lib/types"

  let selectedLanguage = $state<LanguageOption>("auto");
  let detectedLanguage = $state<string>("en");
  let selectedAccent = $state<string>("en");
  let selectedMode = $state<Mode>("wholePage");
  let initialized = $state(false);
  let dictManifest = $state<Record<string, { entries: number; sizeKB: number }>>({});

  // The actual language being used (resolved from auto or manual)
  let effectiveLanguage = $derived<string>(
    selectedLanguage === 'auto' ? detectedLanguage : selectedLanguage as string
  );

  // Available accents for the effective language
  let currentAccents = $derived(AccentsByLanguage[effectiveLanguage] || {});
  let currentAccentLabel = $derived(currentAccents[selectedAccent] || selectedAccent);

  // Build language options for MultiSelect
  interface LangOption {
    label: string;
    value: string;
    dictEntries?: number;
  }

  let languageOptionsList = $derived<LangOption[]>([
    { label: 'Auto-detect', value: 'auto' },
    ...Object.entries(Languages).map(([code, cfg]) => {
      const dict = dictManifest[code];
      const suffix = dict ? ` (${(dict.entries / 1000).toFixed(0)}k words)` : ' (espeak only)';
      return { label: `${cfg.name}${suffix}`, value: code, dictEntries: dict?.entries };
    }).sort((a, b) => a.label.localeCompare(b.label)),
  ]);

  let selectedLangOption = $derived(
    languageOptionsList.find(o => o.value === selectedLanguage) || languageOptionsList[0]
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

    const savedAccent = await storage.getItem<string>('local:selectedAccent');
    selectedAccent = savedAccent || DefaultAccents[effectiveLanguage];

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

  function onLanguageSelect(selected: LangOption | LangOption[]) {
    const opt = Array.isArray(selected) ? selected[0] : selected;
    if (!opt) return;
    selectedLanguage = opt.value as LanguageOption;
  }

  // Persist + notify on language change
  $effect(() => {
    if (!initialized) return;
    storage.setItem<string>('local:selectedLanguage', selectedLanguage);
    selectedAccent = DefaultAccents[effectiveLanguage];
    storage.setItem<string>('local:selectedAccent', selectedAccent);
    (async () => sendMessage('languageChanged', selectedLanguage, await getCurrentTabId()))();
  });

  $effect(() => {
    if (!initialized) return;
    storage.setItem<string>('local:selectedAccent', selectedAccent);
    (async () => sendMessage('accentChanged', selectedAccent, await getCurrentTabId()))();
  });

  $effect(() => {
    if (!initialized) return;
    storage.setItem<string>('local:selectedMode', selectedMode);
    (async () => sendMessage('modeChanged', selectedMode, await getCurrentTabId()))();
  });

  let showInfo = $state(false);

  // Stats
  let totalDictLangs = $derived(Object.keys(dictManifest).length);
  let totalWords = $derived(Object.values(dictManifest).reduce((s, d) => s + d.entries, 0));
  let effectiveHasDict = $derived(effectiveLanguage in dictManifest);
</script>

<div class="w-full space-y-4">
  <!-- Language selector -->
  <div class="flex flex-col gap-2">
    <h1 class="text-lg font-medium text-white">Language</h1>
    <MultiSelect
      options={languageOptionsList}
      selected={[selectedLangOption]}
      maxSelect={1}
      placeholder="Search languages..."
      on:change={(e) => onLanguageSelect(e.detail.option)}
      --sms-border="1px solid #374151"
      --sms-bg="transparent"
      --sms-text-color="white"
      --sms-options-bg="#1f2937"
      --sms-li-selected-bg="#374151"
      --sms-li-active-bg="#374151"
      --sms-font-size="0.95rem"
      --sms-padding="0.6rem 0.8rem"
      --sms-min-height="2.8rem"
    />
    {#if selectedLanguage === 'auto'}
      <p class="text-xs text-gray-500 px-1">
        Detected: <strong class="text-gray-400">{LanguageNames[detectedLanguage] || detectedLanguage}</strong>
        {#if effectiveHasDict}
          — using Wiktionary dictionary
        {:else}
          — using espeak synthesis
        {/if}
      </p>
    {:else if effectiveHasDict}
      <p class="text-xs text-gray-500 px-1">
        Using Wiktionary dictionary ({(dictManifest[effectiveLanguage]?.entries || 0).toLocaleString()} words)
      </p>
    {:else}
      <p class="text-xs text-gray-500 px-1">
        No dictionary available — using espeak synthesis only
      </p>
    {/if}
  </div>

  {#if Object.keys(currentAccents).length > 1}
    <Dropdown
      topic="Accent"
      headEntry={currentAccentLabel}
      selectedElement={selectedAccent}
      elements={currentAccents}
      onElementChange={(accent) => {
        selectedAccent = accent;
      }}
    />
  {/if}

  <Dropdown
    topic="Mode"
    headEntry={Modes[selectedMode]}
    selectedElement={selectedMode}
    elements={Modes}
    onElementChange={(mode) => {
      if (mode in Modes) {
        selectedMode = mode as Mode;
      }
    }}
  />

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
