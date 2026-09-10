<script lang="ts">
  // What one sound is, on the card's own line for it.
  //
  // Always there and always the same height, whether it holds a description or the invitation
  // to ask for one: a panel that appeared and grew with the description moved the card while a
  // reader was reading it, and a card that moves under the cursor is a card that closes itself.
  //
  // Only what the table actually has. A sound with no recording shows no play button, because
  // a control that leads nowhere is worse than no control.
  import type { IpaSymbol } from '@/core/answer';
  import IconButton from '@/ui/controls/IconButton.svelte';
  import IconLink from '@/ui/controls/IconLink.svelte';
  import { ARTICLE, FILM, SPEAKER } from './icons';

  /** Where Commons keeps a file, which is the same URL the phone builds. */
  const commons = (file: string) =>
    `https://commons.wikimedia.org/wiki/Special:FilePath/${encodeURIComponent(file)}`;

  interface Props {
    /** The sound the line is showing, or nothing while none has been asked about. */
    about: IpaSymbol | null;
    /** A picture of the mouth making it, where the host could fetch one. */
    diagram?: string | null;
    onPlay?: (url: string) => void;
    onOpen?: (url: string) => void;
  }

  let { about, diagram = null, onPlay, onOpen }: Props = $props();
</script>

<div class="detail">
  {#if about}
    <span class="d-sym">{about.token}</span>
    <span class="d-text">
      <span class="d-name">{about.name || about.token}</span>
      {#if about.example}<span class="d-eg">{about.example}</span>{/if}
    </span>
    {#if about.audio}
      <IconButton
        icon={SPEAKER}
        label="play a recording of {about.token}"
        name="play-recording"
        press={() => onPlay?.(commons(about.audio))}
      />
    {/if}
    {#if about.wiki}
      <IconLink
        icon={ARTICLE}
        label="read about {about.token}"
        url="https://en.wikipedia.org/wiki/{about.wiki}"
        name="Wikipedia"
        open={onOpen}
      />
    {/if}
    {#if about.seeing}
      <!-- Films of a real mouth saying it, which is what a diagram cannot show. -->
      <IconLink
        icon={FILM}
        label="see a mouth saying {about.token}"
        url="https://seeingspeech.ac.uk/{about.seeing}"
        name="Seeing Speech"
        open={onOpen}
      />
    {/if}
    {#if diagram}
      <!-- The mouth that makes it, fetched by the host because a page's own policy would
           refuse the load. -->
      <span class="d-thumb"><img src={diagram} alt="how the mouth makes {about.token}" /></span>
    {/if}
  {:else}
    <span class="d-hint">Tap a sound to hear what it is</span>
  {/if}
</div>
