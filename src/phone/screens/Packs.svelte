<script lang="ts">
  // The dictionaries: which are here, which can be fetched, and what each one costs.
  import { LANGUAGES } from '@/data/languages';
  import { SAYS } from '@/data/wording';
  import type { Offered } from '@/host/packs';
  import Loader from 'virtual:icons/line-md/loading-twotone-loop';
  import Group from '../parts/Group.svelte';

  interface Props {
    held: string[];
    offered: Offered[];
    /** Which language is being fetched right now. */
    fetching: string | null;
    get: (lang: string) => void;
    forget: (lang: string) => void;
  }

  let { held, offered, fetching, get, forget }: Props = $props();

  /** A language by its name: the product's table first, the device's own names for the
   *  languages the table does not hold. */
  const device = new Intl.DisplayNames(['en'], { type: 'language' });
  function named(code: string): string {
    return LANGUAGES[code]?.english ?? device.of(code) ?? code;
  }

  function size(bytes: number): string {
    if (bytes >= 1024 * 1024) return `${Math.round(bytes / (1024 * 1024))} MB`;
    if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${bytes} B`;
  }

  function count(entries: number): string {
    const words = entries >= 1000 ? `${Math.round(entries / 1000)}k` : String(entries);
    return SAYS['words-count'].replace('%s', words);
  }

  let sorted = $derived(
    [...offered].sort((a, b) => named(a.lang).localeCompare(named(b.lang)))
  );
  let here = $derived(sorted.filter((it) => held.includes(it.lang)));
  let elsewhere = $derived(sorted.filter((it) => !held.includes(it.lang)));
</script>

{#each [{ packs: here, name: SAYS['packs-here'] }, { packs: elsewhere, name: SAYS['packs-more'] }] as { packs, name } (name)}
  {#if packs.length > 0}
    <Group {name}>
      {#each packs as pack (pack.lang)}
        <div class="item" data-row="pack" data-lang={pack.lang}>
          <span class="item-text">
            <span class="item-name" data-name>{named(pack.lang)}</span>
            <span class="item-value">{count(pack.entries)} · {size(pack.bytes)}</span>
          </span>
          {#if fetching === pack.lang}
            <span aria-label="fetching"><Loader class="option-check" /></span>
          {:else if held.includes(pack.lang)}
            <button class="button quiet" data-does="remove" onclick={() => forget(pack.lang)}>
              {SAYS['remove']}
            </button>
          {:else}
            <button class="button" data-does="get" onclick={() => get(pack.lang)}>
              {SAYS['get']}
            </button>
          {/if}
        </div>
      {/each}
    </Group>
  {/if}
{/each}
