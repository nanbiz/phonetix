<script lang="ts">
  import Chevron from "virtual:icons/line-md/chevron-up";
  import Heart from "virtual:icons/pixelarticons/heart";

  import Toggles from "./Toggles.svelte";
  import Setting from "./Setting.svelte";

  let Languages = {
    detect: "Detect Language",
    english: "English",
    german: "German",
    spanish: "Spanish",
  };

  let detectedLanguage = $state("");
  let selectedLanguage = $state(Languages.english);
  let detectLanguage = $derived(selectedLanguage === Languages.detect);

  $effect(() => {
    storage.setItem<string>(`local:selectedLanguage`, selectedLanguage);
  });
  storage.getItem<string>(`local:selectedLanguage`).then((value) => {
    if (value) {
      selectedLanguage = value;
    }
  });

  let languageExpanded = $state(false);
  function toggleLanguageExpanded() {
    languageExpanded = !languageExpanded;
    console.log(languageExpanded);
  }


  let Modes = {
    wholePage: "Translate Whole Page",
    translateOnHover: "Translate on Hover",
    showOriginalOnHover: "Show Original on Hover",
  };

  let selectedMode = $state(Modes.wholePage);
  
  $effect(() => {
    storage.setItem<string>(`local:selectedMode`, selectedMode);
  });
  storage.getItem<string>(`local:selectedMode`).then((value) => {
    if (value) {
      selectedMode = value;
    }
  });

  let modeExpanded = $state(false);
</script>

<main class="flex flex-col items-center gap-6">
  <Toggles />
  <div class="w-full space-y-4">
    <Setting
      topic="Language"
      headEntry={detectLanguage ? (detectedLanguage ? `${detectedLanguage} (auto)` : "Detecting...") : selectedLanguage}
      selectedElement={selectedLanguage}
      elements={Languages}
      onElementChange={(language) => {
        selectedLanguage = language;
      }}
    />
    <Setting
      topic="Mode"
      headEntry={selectedMode}
      selectedElement={selectedMode}
      elements={Modes}
      onElementChange={(mode) => {
        selectedMode = mode;
      }}
    />
  </div>

  <div style="min-height: 13em;"></div>
  <div class="inline-flex gap-1.5 mb-4 items-center">
    Made with
    <Heart class="size-6 text-red-500" />
    .
  </div>
</main>
