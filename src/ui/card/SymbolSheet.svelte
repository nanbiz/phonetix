<script lang="ts">
  // What one sound is, a tap under the word.
  //
  // The card answers about a word; this answers about a sound, which is why it is one tap
  // deeper rather than on the card's face. What is absent is absent: a symbol whose table row
  // has no diagram shows no diagram, rather than an empty frame that looks like something
  // failing to load.
  import type { IpaSymbol } from '@/core/answer';

  /** Where Commons keeps a file, which is the same URL the phone builds. */
  const commons = (file: string) =>
    `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(file)}`;

  interface Props {
    /** The sound the reader tapped, as the core described it. */
    about: IpaSymbol;
    /** A picture of the mouth making it, where the host could fetch one. */
    diagram?: string | null;
    onPlay?: (url: string) => void;
    onOpen?: (url: string) => void;
  }

  let { about, diagram = null, onPlay, onOpen }: Props = $props();

  let links = $derived(
    [
      about.wiki
        ? { label: 'Wikipedia', url: `https://en.wikipedia.org/wiki/${about.wiki}` }
        : null,
      about.seeing
        ? { label: 'Seeing Speech', url: `https://seeingspeech.ac.uk/${about.seeing}` }
        : null,
    ].filter((link): link is { label: string; url: string } => link !== null)
  );
</script>

{#if about.name}
  <aside class="popover">
    {#if diagram}
      <!-- The mouth that makes it. Fetched by the host, because a page's own policy would
           refuse the load and because one picture is worth the round trip. -->
      <img class="diagram" src={diagram} alt="how the mouth makes {about.token}" />
    {/if}
    <div class="pop-sym">
      {about.token}
      <span class="chip">{about.kind}</span>
    </div>
    <div class="pop-name">{about.name}</div>
    {#if about.example}
      <!-- The table's own phrasing, which already names the symbol: repeating it here made
           every sound read as "p" in "p" in pin. -->
      <p class="pop-desc">{about.example}</p>
    {/if}
    <div class="pop-links">
      {#if about.audio}
        <!-- A person saying it, where Commons has a recording of one. -->
        <button class="btn-text" onclick={() => onPlay?.(commons(about.audio))}>
          Play recording
        </button>
      {/if}
      {#each links as link (link.label)}
        <button class="btn-text" onclick={() => onOpen?.(link.url)}>{link.label}</button>
      {/each}
    </div>
  </aside>
{/if}
