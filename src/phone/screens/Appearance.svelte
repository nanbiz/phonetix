<script lang="ts">
  // Which palette, and which side of it. Each palette is drawn in its own colours, so it is
  // chosen by how it looks.
  import { DARK_CHOICES, ROWS } from '@/data/wording';
  import { SIDES } from '@/ui/palettes';
  import { THEME, THEMES, themeOf } from '@/ui/theme';
  import type { Settings } from '@/settings/shape';
  import Check from 'virtual:icons/lucide/check';
  import Group from '../parts/Group.svelte';
  import Segments from '../parts/Segments.svelte';

  interface Props {
    settings: Settings;
    /** Whether the device is set to dark. */
    device: boolean;
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
  }

  let { settings, device, change }: Props = $props();

  let side = $derived(settings.dark === 'system' ? (device ? 'dark' : 'light') : settings.dark);
  /** Only the palettes that have the side in force: one without it cannot be drawn. */
  let themes = $derived(THEMES.filter((name) => (SIDES[name] ?? []).includes(side)));
  let chosen = $derived(settings.theme || THEME);
</script>

<Group name={ROWS.dark.name}>
  <div class="item wide" data-row="dark">
    <Segments
      choices={DARK_CHOICES}
      chosen={settings.dark}
      label={ROWS.dark.name}
      change={(value) => {
        const wanted = value === 'system' ? (device ? 'dark' : 'light') : value;
        if (!(SIDES[settings.theme] ?? []).includes(wanted)) change('theme', THEME);
        change('dark', value);
      }}
    />
  </div>
</Group>

<Group name={ROWS.theme.name}>
  <div class="palettes" role="radiogroup" data-row="palettes">
    {#each themes as name (name)}
      <button
        class="palette {themeOf(side === 'dark', name)}"
        class:on={name === chosen}
        role="radio"
        aria-checked={name === chosen}
        data-choice={name}
        onclick={() => change('theme', name)}
      >
        <span class="palette-sample">
          <span class="palette-ink"></span>
          <span class="palette-ink short"></span>
          <span class="palette-accent"></span>
        </span>
        <span class="palette-name">{name.charAt(0).toUpperCase() + name.slice(1)}</span>
        {#if name === chosen}<Check class="palette-check" />{/if}
      </button>
    {/each}
  </div>
</Group>
