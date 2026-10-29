<script lang="ts">
  // Every language the reader has, in one place: the ones they read, their own marked, and the
  // ones they are learning, the default marked, each with the accent it is read in.
  //
  // Their own is what a word in any language they do not read is translated into; the others
  // they read are left as they are and defined in their own words; of those they learn, the
  // default is what the pages in theirs are replaced into. One list each, added to and taken
  // from here, so a language is never in two of them.
  import { ACCENTS } from '@/data/accents';
  import { LANGUAGES, named } from '@/data/languages';
  import { ROWS, SAYS } from '@/data/wording';
  import { accentFor, learned, setAccent, type Settings } from '@/settings/shape';
  import Star from 'virtual:icons/lucide/star';
  import Remove from 'virtual:icons/lucide/x';
  import Plus from 'virtual:icons/lucide/plus';
  import Chevron from 'virtual:icons/lucide/chevron-right';
  import Group from '../parts/Group.svelte';
  import ListSheet from '../parts/ListSheet.svelte';

  interface Props {
    settings: Settings;
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
  }

  let { settings, change }: Props = $props();

  /** The ones they learn, the default first. */
  let learning = $derived(learned(settings));
  /** Their own first, then the others they read in the order they were added. */
  let mine = $derived(
    [settings.target, ...settings.known].filter(
      (lang, at, all) => lang && !learning.includes(lang) && all.indexOf(lang) === at
    )
  );

  const everyLanguage = Object.entries(LANGUAGES)
    .map(([code, it]) => ({
      value: code,
      label: it.english,
      about: it.native !== it.english ? it.native : undefined,
    }))
    .sort((a, b) => a.label.localeCompare(b.label));
  /** What can still be added: a language on neither list. */
  let addable = $derived(
    everyLanguage.filter((it) => !mine.includes(it.value) && !learning.includes(it.value))
  );

  function accentsOf(lang: string) {
    return (ACCENTS[lang] ?? []).map((it) => ({ value: it.id, label: it.name }));
  }
  function accentName(lang: string): string {
    return accentsOf(lang).find((it) => it.value === accentFor(settings, lang))?.label ?? '';
  }

  /** Their own becomes one they read, and this one theirs. */
  function makeMain(lang: string) {
    const known = [settings.target, ...settings.known].filter(
      (it, at, all) => it && it !== lang && all.indexOf(it) === at
    );
    change('target', lang);
    change('known', known);
  }

  function add(lang: string) {
    if (!settings.target) change('target', lang);
    else change('known', [...settings.known.filter((it) => it !== lang), lang]);
  }

  function remove(lang: string) {
    change('known', settings.known.filter((it) => it !== lang));
  }

  /** Learned as well, the default where there is none yet. */
  function learn(lang: string) {
    if (!settings.learn) change('learn', lang);
    else change('alsoLearn', [...learning.slice(1).filter((it) => it !== lang), lang]);
  }

  /** The one the pages are replaced into, the one it was staying among the others. */
  function makeDefault(lang: string) {
    change('alsoLearn', learning.filter((it) => it !== lang));
    change('learn', lang);
  }

  /** No longer learned; the default gone, the next one is. */
  function unlearn(lang: string) {
    const rest = learning.filter((it) => it !== lang);
    change('alsoLearn', rest.slice(1));
    change('learn', rest[0] ?? '');
  }

  /** Which list is open: '' none, 'add' or 'learn' a language to add, or a language's accents. */
  let choosing = $state('');
</script>

{#snippet language(lang: string, controls: 'main' | 'mine' | 'learn-main' | 'learn')}
  {@const accent = accentName(lang)}
  <div
    class="item language"
    data-row="language"
    data-lang={lang}
    data-learning={controls === 'learn' || controls === 'learn-main' ? '' : undefined}
  >
    {#if accent}
      <button class="language-open" onclick={() => (choosing = lang)}>
        <span class="item-text">
          <span class="item-name" data-name>{named(lang)}</span>
          <span class="item-value" data-about>{accent}</span>
        </span>
        <Chevron class="item-chevron" />
      </button>
    {:else}
      <span class="language-open">
        <span class="item-text"><span class="item-name" data-name>{named(lang)}</span></span>
      </span>
    {/if}
    {#if controls === 'main' || controls === 'learn-main'}
      <span
        class="icon-button main"
        role="img"
        aria-label={SAYS[controls === 'main' ? 'main-language' : 'default-learning']}><Star /></span
      >
    {:else}
      <button
        class="icon-button"
        data-does={controls === 'mine' ? 'make-main' : 'make-default'}
        aria-label={SAYS[controls === 'mine' ? 'make-main' : 'make-default-learning']}
        onclick={() => (controls === 'mine' ? makeMain(lang) : makeDefault(lang))}><Star /></button
      >
    {/if}
    {#if controls === 'main'}
      <span class="icon-room"></span>
    {:else}
      <button
        class="icon-button"
        data-does="remove"
        aria-label={SAYS['remove']}
        onclick={() => (controls === 'mine' ? remove(lang) : unlearn(lang))}><Remove /></button
      >
    {/if}
  </div>
{/snippet}

<Group name={ROWS['mine-group'].name}>
  {#each mine as lang, at (lang)}
    {@render language(lang, at === 0 ? 'main' : 'mine')}
  {/each}
  <button class="item adding" data-does="add-language" onclick={() => (choosing = 'add')}>
    <Plus /><span class="item-name">{SAYS['add-language']}</span>
  </button>
</Group>

<Group name={ROWS.learn.name}>
  {#each learning as lang, at (lang)}
    {@render language(lang, at === 0 ? 'learn-main' : 'learn')}
  {/each}
  <button class="item adding" data-does="learn-language" onclick={() => (choosing = 'learn')}>
    <Plus /><span class="item-name">{SAYS[learning.length > 0 ? 'learn-another' : 'learn-language']}</span>
  </button>
</Group>

{#if choosing === 'add'}
  <ListSheet
    title={SAYS['add-language']}
    options={addable}
    chosen=""
    change={add}
    close={() => (choosing = '')}
  />
{:else if choosing === 'learn'}
  <ListSheet
    title={SAYS[learning.length > 0 ? 'learn-another' : 'learn-language']}
    options={addable}
    chosen=""
    change={learn}
    close={() => (choosing = '')}
  />
{:else if choosing}
  <ListSheet
    title={named(choosing)}
    options={accentsOf(choosing)}
    chosen={accentFor(settings, choosing)}
    change={(value) => change('accents', setAccent(settings, choosing, value))}
    close={() => (choosing = '')}
  />
{/if}
