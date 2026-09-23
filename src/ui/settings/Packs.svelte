<script lang="ts">
  // The dictionaries: what is here, what else is on offer, and what each costs.
  //
  // A page's dictionary arrives by itself the first time a page in its language is read; this
  // is where a reader sees which ones they hold, fetches one ahead of reading it, or gives one
  // up to have the space back.
  import Download from 'virtual:icons/pixelarticons/download';
  import Close from 'virtual:icons/pixelarticons/close';
  import Loader from 'virtual:icons/line-md/loading-twotone-loop';

  import { LANGUAGES } from '@/data/languages';
  import type { Offered } from '@/host/packs';
  import Row from './Row.svelte';

  interface Props {
    packs: { held: string[]; offered: Offered[] };
    /** Which language is being fetched right now, so its row says so rather than looking dead. */
    fetching?: string | null;
    get?: (lang: string) => void;
    forget?: (lang: string) => void;
  }

  let { packs, fetching = null, get, forget }: Props = $props();

  /** A size a reader can weigh, since the whole point of the row is deciding whether to
   *  spend it. */
  function size(bytes: number): string {
    if (bytes >= 1024 * 1024) return `${Math.round(bytes / (1024 * 1024))} MB`;
    if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${bytes} B`;
  }
</script>

<div class="rows">
  {#each packs.offered as pack (pack.lang)}
    <Row
      name={LANGUAGES[pack.lang]?.english ?? pack.lang}
      row="pack"
      marks={{ 'data-lang': pack.lang }}
      about="{pack.entries.toLocaleString()} words · {size(pack.bytes)}"
    >
      {#snippet control()}
        {#if fetching === pack.lang}
          <span class="btn-text" aria-label="fetching"><Loader class="r-icon" /></span>
        {:else if packs.held.includes(pack.lang)}
          <button class="btn-text" data-does="remove" onclick={() => forget?.(pack.lang)}>
            <Close class="r-icon" />remove
          </button>
        {:else}
          <button class="btn-text" data-does="get" onclick={() => get?.(pack.lang)}>
            <Download class="r-icon" />get
          </button>
        {/if}
      {/snippet}
    </Row>
  {/each}
</div>
