<script lang="ts">
  // Which apps the button answers in: every one, or the ones ticked here.
  import { ROWS, SAYS } from '@/data/wording';
  import type { Settings } from '@/settings/shape';
  import Check from 'virtual:icons/pixelarticons/check';
  import Group from '../parts/Group.svelte';
  import Item from '../parts/Item.svelte';
  import Switch from '../parts/Switch.svelte';

  interface Props {
    settings: Settings;
    /** The apps on the launcher, by package and by the name the launcher shows. */
    apps: { pkg: string; label: string }[];
    allApps: (on: boolean) => void;
    toggle: (pkg: string) => void;
  }

  let { settings, apps, allApps, toggle }: Props = $props();

  let typed = $state('');
  let shown = $derived.by(() => {
    const wanted = typed.trim().toLowerCase();
    return wanted ? apps.filter((it) => it.label.toLowerCase().includes(wanted)) : apps;
  });
  /** The ticked ones first, so what is chosen is in view without scrolling for it. */
  let ordered = $derived([
    ...shown.filter((it) => settings.apps.includes(it.pkg)),
    ...shown.filter((it) => !settings.apps.includes(it.pkg)),
  ]);
</script>

<Group>
  <Item name={SAYS['every-app']} row="all-apps">
    {#snippet control()}
      <Switch on={settings.allApps} label={SAYS['every-app']} change={allApps} />
    {/snippet}
  </Item>
</Group>

<input class="search" disabled={settings.allApps} type="search" placeholder={SAYS['search']} bind:value={typed} />
  <Group>
    {#each ordered as app (app.pkg)}
      {@const on = settings.allApps || settings.apps.includes(app.pkg)}
      <button
        class="item option"
        class:on
        role="checkbox"
        aria-checked={on}
        data-row="app"
        data-pkg={app.pkg}
        disabled={settings.allApps}
        onclick={() => toggle(app.pkg)}
      >
        <img class="app-icon" src="./icon/{app.pkg}.png" alt="" loading="lazy" />
        <span class="item-text"><span class="item-name">{app.label}</span></span>
        {#if on}<Check class="option-check" />{/if}
      </button>
    {/each}
    {#if ordered.length === 0}<p class="none">{SAYS['nothing-found']}</p>{/if}
  </Group>
