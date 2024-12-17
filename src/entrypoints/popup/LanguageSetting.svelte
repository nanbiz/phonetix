<script lang="ts">
  import Dropdown from "./Dropdown.svelte";

  import Toggles from "./Toggles.svelte";
  var Languages = {
    english: "English",
    german: "German",
  };

  var autoSelected = $state(false);
  var selectedLanguage = $state(Languages.english);
  var Modes = {
    wholePage: "Translate Whole Page",
    translateOnHover: "Translate on Hover",
    showOriginalOnHover: "Show Original on Hover",
  };

  var languageExpanded = $state(false);
  function toggleLanguageExpanded() {
    languageExpanded = !languageExpanded;
    console.log(languageExpanded);
  }

  var selectedMode = $state(Modes.wholePage);
  var modeExpanded = $state(false);
  function toggleModeExpanded() {
    modeExpanded = !modeExpanded;
    console.log(modeExpanded);
  }
</script>

  <div class="flex flex-row place-items-center min-w-full p-2 gap-8">
    <p class="text-lg">Language:</p>
    <div role="button" tabindex="0" onkeydown={toggleLanguageExpanded} class="dropdown-container w-full relative" onclick={toggleLanguageExpanded}>
      <div class="p-2">{autoSelected ? (selectedLanguage ? selectedLanguage + " (auto-detected)" : "detecting...") : selectedLanguage}</div>
      <div class="w-full dropdown-menu {languageExpanded ? 'show' : ''}">
        <button
          type="button"
          class="dropdown-menu-item w-full"
          onclick={() => {
            autoSelected = true;
            selectedLanguage = "";
            // TODO: trigger language detection
          }}>Detect Language</button
        >
        {#each Object.values(Languages) as language}
          <button
            type="button"
            class="dropdown-menu-item w-full"
            onclick={() => {
              selectedLanguage = language;
              autoSelected = false;
            }}>{language}</button
          >
        {/each}
      </div>
    </div>
  </div>


<style>
  .dropdown-content {
    background-color: rgb(65, 75, 87);
  }

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

