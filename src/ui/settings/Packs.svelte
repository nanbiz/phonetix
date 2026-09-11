<script lang="ts">
  // The dictionaries: what is on offer, what each costs, and the one thing to do with it.
  //
  // Nothing is fetched because a page happened to be in a language. A dictionary is tens of
  // megabytes on someone's connection, so it arrives when a reader asks for it by name.
  import Download from 'virtual:icons/pixelarticons/download';
  import Close from 'virtual:icons/pixelarticons/close';
  import Loader from 'virtual:icons/line-md/loading-twotone-loop';

  import { LANGUAGES } from '@/data/languages';
  import type { Offered } from '@/host/packs';
  import { ROWS, SAYS } from '@/data/wording';
  import Field from '@/ui/controls/Field.svelte';
  import Row from './Row.svelte';

  interface Props {
    packs: { held: string[]; open: string[]; offered: Offered[] };
    /** Where the dictionaries come from, which is the reader's to decide. */
    host?: string;
    onHost?: (host: string) => void;
    /** Which language is being fetched right now, so its row says so rather than looking dead. */
    fetching?: string | null;
    get?: (lang: string) => void;
    forget?: (lang: string) => void;
  }

  let { packs, host = '', onHost, fetching = null, get, forget }: Props = $props();

  /** A size a reader can weigh, since the whole point of the row is deciding whether to
   *  spend it. */
  function size(bytes: number): string {
    if (bytes >= 1024 * 1024) return `${Math.round(bytes / (1024 * 1024))} MB`;
    if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${bytes} B`;
  }
</script>

<div class="rows">
  <!-- Where they come from, which is the reader's to decide and appears nowhere in the source:
       an extension that went looking on its own would be an extension deciding who to talk to. -->
  <Row
    name={ROWS.host.name}
    row="host"
    about={host ? ROWS.host.about : SAYS['no-host']}
  >
    {#snippet wide()}
      <Field
        value={host}
        kind="url"
        label="where the dictionaries come from"
        placeholder="https://…"
        change={(said) => onHost?.(said)}
      />
    {/snippet}
  </Row>
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
