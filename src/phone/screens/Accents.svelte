<script lang="ts">
  // Which accent each language is read in. The two languages the button works between are
  // laid out in full; every other language that offers a choice is a row of its own.
  import { ACCENTS } from '@/data/accents';
  import { named } from '@/data/languages';
  import { SAYS } from '@/data/wording';
  import { accentFor, setAccent, type Settings } from '@/settings/shape';
  import Group from '../parts/Group.svelte';
  import Item from '../parts/Item.svelte';
  import Options from '../parts/Options.svelte';
  import ListSheet from '../parts/ListSheet.svelte';

  interface Props {
    settings: Settings;
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
  }

  let { settings, change }: Props = $props();

  function capital(text: string): string {
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  function optionsOf(lang: string) {
    return [
      { value: '', label: capital(SAYS['dictionary-accent']) },
      ...(ACCENTS[lang] ?? []).map((it) => ({ value: it.id, label: it.name })),
    ];
  }

  let offering = Object.keys(ACCENTS).filter((lang) => (ACCENTS[lang] ?? []).length > 1);
  let mine = $derived(
    [settings.learning, settings.target].filter(
      (lang, at, all) => lang && offering.includes(lang) && all.indexOf(lang) === at
    )
  );
  let others = $derived(
    offering.filter((lang) => !mine.includes(lang)).sort((a, b) => named(a).localeCompare(named(b)))
  );

  let choosing = $state('');
  function pick(lang: string, accent: string) {
    change('accents', setAccent(settings, lang, accent));
  }
</script>

{#each mine as lang (lang)}
  <Group name={named(lang)}>
    <Options options={optionsOf(lang)} chosen={accentFor(settings, lang)} change={(value) => pick(lang, value)} />
  </Group>
{/each}

{#if others.length > 0}
  <Group name={mine.length > 0 ? SAYS['other-languages'] : ''}>
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
