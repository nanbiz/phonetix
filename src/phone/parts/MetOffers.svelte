<script lang="ts">
  // The languages a reader keeps meeting and has on none of their lists, each with how often
  // and the three things they can make of it: learn it, read it as it is, or not now. The same
  // offer the card makes on a word of one, kept here so it is not lost with the card.
  import { offered, type Settings } from '@/settings/shape';
  import { named } from '@/data/languages';
  import { ROWS, say } from '@/data/wording';
  import PASS from 'virtual:icons/lucide/x';
  import Group from './Group.svelte';
  import Item from './Item.svelte';

  interface Props {
    settings: Settings;
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
    /** At most this many, the ones met most: a toolbar view has room for one. */
    most?: number;
  }

  let { settings, change, most = Infinity }: Props = $props();
  let offers = $derived(offered(settings).slice(0, most));
  const add = (list: string[] | undefined, lang: string) => [...new Set([...(list ?? []), lang])];
</script>

{#if offers.length > 0}
  <Group name={ROWS.met.name}>
    {#each offers as offer (offer.lang)}
      <Item name={named(offer.lang)} row="met-{offer.lang}" value={say('met-on-pages', String(offer.pages))}>
        {#snippet control()}
          <span class="btn-row met-answers">
            <button class="button" data-does="learn" onclick={() => change('alsoLearn', add(settings.alsoLearn, offer.lang))}
              >{say('learn-it')}</button>
            <button class="button quiet" data-does="read" onclick={() => change('known', add(settings.known, offer.lang))}
              >{say('read-it')}</button>
            <button class="icon-button" data-does="pass" aria-label={say('not-now')} title={say('not-now')}
              onclick={() => change('passed', add(settings.passed, offer.lang))}><PASS /></button>
          </span>
        {/snippet}
      </Item>
    {/each}
  </Group>
{/if}
