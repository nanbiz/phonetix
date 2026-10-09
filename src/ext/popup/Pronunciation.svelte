<script lang="ts">
  // How the IPA is written: how much detail, whether stress is marked, and which accent each
  // language is read in.
  //
  // One screen that fits the popup without scrolling. The detail is shown by example rather than
  // described: the same word written both ways says what the difference is. Each language a
  // reader uses has its accent on one row, and every other language is one row behind those.
  import { ACCENTS } from '@/data/accents';
  import { named } from '@/data/languages';
  import { ROWS, SAYS } from '@/data/wording';
  import { accentFor, setAccent, type Settings } from '@/settings/shape';
  import Group from '@/phone/parts/Group.svelte';
  import Item from '@/phone/parts/Item.svelte';
  import Switch from '@/phone/parts/Switch.svelte';
  import Segments from '@/phone/parts/Segments.svelte';
  import ListSheet from '@/phone/parts/ListSheet.svelte';

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

  function accentName(lang: string): string {
    return optionsOf(lang).find((it) => it.value === accentFor(settings, lang))?.label ?? '';
  }

  /** The languages with more than one accent to choose between. */
  const offering = Object.keys(ACCENTS).filter((lang) => (ACCENTS[lang] ?? []).length > 0);
  /** The reader's own two, each on a row of its own. */
  let mine = $derived(
    [settings.target, ...(settings.known ?? [])].filter(
      (lang, at, all) => lang && offering.includes(lang) && all.indexOf(lang) === at
    )
  );
  let others = $derived(
    offering
      .filter((lang) => !mine.includes(lang))
      .sort((a, b) => named(a).localeCompare(named(b)))
      .map((lang) => ({ value: lang, label: named(lang), about: accentName(lang) }))
  );

  /** Which list is open: none (''), the other languages ('others'), or the accents of the
   *  language with this code. */
  let choosing = $state<string>('');
</script>

<Group name={ROWS.narrow.name}>
  <div class="item wide" data-row="narrow">
    <Segments
      choices={[
        { value: 'broad', label: SAYS['simple'], example: SAYS['simple-example'] },
        { value: 'narrow', label: SAYS['detailed'], example: SAYS['detailed-example'] },
      ]}
      chosen={settings.narrow ? 'narrow' : 'broad'}
      label={ROWS.narrow.name}
      change={(value) => change('narrow', value === 'narrow')}
    />
  </div>
</Group>

<Group>
  <Item name={ROWS.stress.name} row="stress">
    {#snippet control()}
      <Switch
        on={!settings.hideStress}
        label={ROWS.stress.name}
        change={(on) => change('hideStress', !on)}
      />
    {/snippet}
  </Item>
</Group>

<Group name={ROWS.accents.name}>
  {#each mine as lang (lang)}
    <Item
      name={named(lang)}
      row="accent"
      value={accentName(lang)}
      open={() => (choosing = lang)}
    />
  {/each}
  {#if others.length > 0}
    <Item
      name={SAYS['other-languages']}
      row="accent-elsewhere"
      open={() => (choosing = 'others')}
    />
  {/if}
</Group>

{#if choosing === 'others'}
  <ListSheet
    title={SAYS['other-languages']}
    options={others}
    chosen=""
    change={(lang) => setTimeout(() => (choosing = lang))}
    close={() => {
      if (choosing === 'others') choosing = '';
    }}
  />
{:else if choosing}
  <ListSheet
    title={named(choosing)}
    options={optionsOf(choosing)}
    chosen={accentFor(settings, choosing)}
    change={(value) => change('accents', setAccent(settings, choosing, value))}
    close={() => (choosing = '')}
  />
{/if}
