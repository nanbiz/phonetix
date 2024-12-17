<script lang="ts">
  import Toggles from "./Toggles.svelte";


  var Languages = {
    detect: "Detect Language",
    english: "English",
    german: "German",
  };

  var detectLanguage = $state(false)
  var detectedLanguage = $state("");
  var selectedLanguage = $state(Languages.english);
  $effect(() => {
    if (selectedLanguage == Languages.detect) {
      detectLanguage = true;
    } else {
      detectLanguage = false;
    }
  });

  $effect(() => {
    storage.setItem<string>(`local:selectedLanguage`, selectedLanguage);
  });
  storage.getItem<string>(`local:selectedLanguage`).then((value) => {
    if (value) {
      selectedLanguage = value;
    }
  });

  var languageExpanded = $state(false);
  function toggleLanguageExpanded() {
    languageExpanded = !languageExpanded;
    console.log(languageExpanded);
  }

  var Modes = {
    wholePage: "Translate Whole Page",
    translateOnHover: "Translate on Hover",
    showOriginalOnHover: "Show Original on Hover",
  };

  var selectedMode = $state(Modes.wholePage);
  $effect(() => {
    storage.setItem<string>(`local:selectedMode`, selectedMode);
  });
  storage.getItem<string>(`local:selectedMode`).then((value) => {
    if (value) {
      selectedMode = value;
    }
  });

  var modeExpanded = $state(false);
  function toggleModeExpanded() {
    modeExpanded = !modeExpanded;
    console.log(modeExpanded);
  }



</script>

<main class="flex flex-col items-center gap-2">
  <Toggles />
  <h2 class="text-4xl m-4 mt-8">Settings</h2>
  <div class="flex flex-row place-items-center min-w-full p-2 gap-8">
    <p class="text-lg">Language:</p>
    <div role="button" tabindex="0" onkeydown={toggleLanguageExpanded} class="dropdown-container w-full relative" onclick={toggleLanguageExpanded}>
      <div class="p-2">{detectLanguage ? (detectedLanguage ? detectedLanguage + " (auto-detected)" : "detecting...") : selectedLanguage}</div>
      <div class="w-full dropdown-menu {languageExpanded ? 'show' : ''}">
        {#each Object.values(Languages) as language}
          <button
            type="button"
            class="dropdown-menu-item w-full"
            onclick={() => {
              selectedLanguage = language;
            }}>{language}</button
          >
        {/each}
      </div>
    </div>
  </div>

  <div class="flex flex-row place-items-center min-w-full p-2 gap-8">
    <p class="text-lg">Mode:</p>
    <div role="button" tabindex="0" onkeydown={toggleModeExpanded} class="dropdown-container w-full relative" onclick={toggleModeExpanded}>
      <div class="p-2">{selectedMode}</div>
      <div class="w-full dropdown-menu {modeExpanded ? 'show' : ''}">
        {#each Object.values(Modes) as mode}
          <button
            type="button"
            class="dropdown-menu-item w-full"
            onclick={() => {
              selectedMode = mode;
            }}>{mode}</button
          >
        {/each}
      </div>
    </div>
  </div>

  <div style="min-height: 13em;"></div>
</main>

<style>
  .dropdown-container {
    position: relative;
    display: inline-block;
    cursor: pointer;
    user-select: none;
    appearance: none;
    height: 3.25rem;
    min-height: 3.25rem;
    padding-inline-start: 1rem;
    padding-inline-end: 2.5rem;

    font-size: 1rem;
    line-height: 2;
    border-radius: var(--rounded-btn, 0.5rem);
    border-width: 1px;
    --tw-border-opacity: 1;
    border-color: var(--fallback-p, oklch(var(--p) / var(--tw-border-opacity)));
    --tw-bg-opacity: 1;
    background-color: var(--fallback-b1, oklch(var(--b1) / var(--tw-bg-opacity)));

    background-image: linear-gradient(45deg, transparent 50%, currentColor 50%), linear-gradient(135deg, currentColor 50%, transparent 50%);
    background-position:
      calc(100% - 20px) calc(1px + 50%),
      calc(100% - 16.1px) calc(1px + 50%);
    background-size:
      4px 4px,
      4px 4px;
    background-repeat: no-repeat;
  }

  .dropdown-menu {
    position: absolute;
    display: none;
    top: 100%;
    left: calc(0.5 * var(--rounded-btn, 0.5rem));
    width: calc(100% - var(--rounded-btn, 0.5rem));
    background-color: var(--fallback-b1, oklch(var(--b1) / var(--tw-bg-opacity)));
    border: 1px solid #636363e8;
    box-shadow: 0 2px 5px rgba(0, 0, 0, 0.2);
    z-index: 1000; /* High z-index to display above other elements */
  }

  .dropdown-menu.show {
    display: block;
  }

  .dropdown-menu-item {
    padding: 10px;
    cursor: pointer;
  }

  .dropdown-menu-item:hover {
    background-color: var(--fallback-p, oklch(var(--p) / var(--tw-border-opacity)));
  }
</style>
