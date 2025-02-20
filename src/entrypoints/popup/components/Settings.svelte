<script lang="ts">
  import Dropdown from "./Dropdown.svelte";

  import { getCurrentTabId, sendMessage } from "@/lib/messaging"

  import { Languages, Modes } from "@/lib/types"

  let detectedLanguage = $state<keyof typeof Languages>("english");
  let selectedLanguage = $state<keyof typeof Languages>("english");
  let detectLanguage = $derived(selectedLanguage === Languages.detect);

  $effect(() => {
    storage.setItem<string>(`local:selectedLanguage`, selectedLanguage);
  });
  storage.getItem<string>(`local:selectedLanguage`).then((value) => {
    if (value && value in Languages) {
      selectedLanguage = value as keyof typeof Languages;
    }
  });

  let selectedMode = $state<keyof typeof Modes>("wholePage");
  
  $effect(() => {
    storage.setItem<string>(`local:selectedMode`, selectedMode);
  });
  storage.getItem<string>(`local:selectedMode`).then((value) => {
    if (value && value in Modes) {
      selectedMode = value as keyof typeof Modes;
    }
  });

  $effect(() => {
    (async () => sendMessage('languageChanged', detectLanguage ? detectedLanguage : selectedLanguage, await getCurrentTabId()))();
  })
  $effect(() => {
    (async () => sendMessage('modeChanged', selectedMode, await getCurrentTabId()))();
  })
</script>

<div class="w-full space-y-4">
  <Dropdown
    topic="Language"
    headEntry={detectLanguage ? (detectedLanguage ? `${Languages[detectedLanguage]} (auto)` : "Detecting...") : Languages[selectedLanguage]}
    selectedElement={selectedLanguage}
    elements={Languages}
    onElementChange={(language) => {
      if (language in Languages) {
        selectedLanguage = language as keyof typeof Languages;
      } else {
        console.error("Tried to set invalid language");
      }
    }}
  />
  <Dropdown
    topic="Mode"
    headEntry={Modes[selectedMode]}
    selectedElement={selectedMode}
    elements={Modes}
    onElementChange={(mode) => {
       if (mode in Modes) {
        selectedMode = mode as keyof typeof Modes;
      } else {
        console.error("Tried to set invalid mode");
      }
    }}
  />
</div>
