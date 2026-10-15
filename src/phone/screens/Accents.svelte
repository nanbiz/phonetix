<script lang="ts">
  // Which accent each language is read in, for the languages that are not the reader's own:
  // theirs are set beside them on the Languages screen.
  import { ACCENTS } from '@/data/accents';
  import { named } from '@/data/languages';
  import { SAYS } from '@/data/wording';
  import { accentFor, setAccent, type Settings } from '@/settings/shape';
  import Group from '../parts/Group.svelte';
  import Item from '../parts/Item.svelte';
  import ListSheet from '../parts/ListSheet.svelte';

  interface Props {
    settings: Settings;
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
  }

  let { settings, change }: Props = $props();


  function optionsOf(lang: string) {
    return [
      ...(ACCENTS[lang] ?? []).map((it) => ({ value: it.id, label: it.name })),
    ];
  }

  let offering = Object.keys(ACCENTS).filter((lang) => (ACCENTS[lang] ?? []).length > 1);
  let mine = $derived([settings.target, ...settings.known, settings.learn]);
  let others = $derived(
    offering.filter((lang) => !mine.includes(lang)).sort((a, b) => named(a).localeCompare(named(b)))
  );

  let choosing = $state('');
  function pick(lang: string, accent: string) {
    change('accents', setAccent(settings, lang, accent));
  }
</script>

{#if others.length > 0}
  <!-- Headed as what they are: without it the list read as three languages picked at random,
       the reader's own being on the Languages screen. -->
  <Group name={SAYS['other-languages']}>
    {#each others as lang (lang)}
      <Item
        name={named(lang)}
        row="accent-elsewhere"
        value={optionsOf(lang).find((it) => it.value === accentFor(settings, lang))?.label ?? ''}
        open={() => (choosing = lang)}
      />
    {/each}
  </Group>
{/if}

{#if choosing}
  <ListSheet
    title={named(choosing)}
    options={optionsOf(choosing)}
    chosen={accentFor(settings, choosing)}
    change={(value) => pick(choosing, value)}
    close={() => (choosing = '')}
  />
{/if}
