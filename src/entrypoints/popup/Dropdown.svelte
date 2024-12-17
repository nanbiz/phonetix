<script lang="ts">

  
  let {Options, specialElement} = $props();

  var selectedOption = $state(Object.values(Options)[0]);
  var autoSelected = $state(false);
  
  var dropdownExpanded = $state(false);
  function toggleExpanded() {
    dropdownExpanded = !dropdownExpanded;
    console.log(dropdownExpanded);
  }
</script>

 <div role="button" tabindex="0" onkeydown={toggleExpanded} class="selector-container w-full relative" onclick={toggleExpanded}>
  {#if specialElement}
      <div class="p-2">{autoSelected ? (selectedOption? selectedOption + " (auto-detected)" : "detecting...") : selectedOption}</div>
    {/if}
      <div class="w-full selector-menu {dropdownExpanded ? 'show' : ''}">
        {#if dropdownExpanded}
          <button
            type="button"
            class="menu-item w-full"
            onclick={() => {
              autoSelected = true;
              selectedOption = "";
              // TODO: trigger language detection
            }}>Detect Language</button
          >
          {#each Object.values(Options) as language}
            <button
              type="button"
              class="menu-item w-full"
              onclick={() => {
                autoSelected = false;
                selectedOption = language;
              }}>{language}</button
            >
          {/each}
        {/if}
      </div>
    </div>

<style>
  .dropdown-content {
    background-color: rgb(65, 75, 87);
  }

  .selector-container {
    position: relative;
    display: inline-block;
    cursor: pointer;
    user-select: none;
    appearance: none;
    height: 3rem;
    min-height: 3rem;
    padding-inline-start: 1rem;
    padding-inline-end: 2.5rem;

    font-size: 0.875rem;
    line-height: 1.25rem;
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

    overflow: unset;
  }

  .selector-menu {
    position: absolute;
    top: 100%;
    left: calc(0.5 * var(--rounded-btn, 0.5rem));
    width: calc(100% - var(--rounded-btn, 0.5rem));
    background-color: var(--fallback-b1, oklch(var(--b1) / var(--tw-bg-opacity)));
    border: 1px solid #636363e8;
    box-shadow: 0 2px 5px rgba(0, 0, 0, 0.2);
    z-index: 1000; /* High z-index to display above other elements */
  }

  .selector-menu.show {
    display: block;
  }

  .menu-item {
    padding: 10px;
    cursor: pointer;
  }

  .menu-item:hover {
    background-color: var(--fallback-p, oklch(var(--p) / var(--tw-border-opacity)));
  }
</style>

