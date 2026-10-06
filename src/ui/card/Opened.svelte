<script lang="ts">
  // What a reader has open about one word: the answer, and the sound they asked about.
  //
  // The sound is described on a line under the transcription that is there only once a sound
  // has been asked about: the card grows downward from the word it is under, so the line it
  // adds moves nothing the reader is looking at.
  import type { Answer } from '@/core/answer';
  import AnswerCard from './AnswerCard.svelte';

  interface Props {
    answer: Answer;
    /** A recording of a person saying the word, where the host found one. */
    recorded?: boolean;
    /** The accent this language is being read in, where the reader chose one. */
    accent?: string;
    /** A picture of the mouth making a sound, fetched by the host that can reach it. */
    diagram?: (file: string) => Promise<string>;
    /** Whether the card eases in, which is the reader's choice. */
    eased?: boolean;
    /** Which way the arrow points, where the card is anchored to a word. */
    points?: 'above' | 'below' | null;
    onPlay?: () => void;
    onPlayUrl?: (url: string) => void;
    onOpen?: (url: string) => void;
    /** Told when a sound is opened or closed, so a host can measure what is on screen. */
    onSymbol?: (symbol: string | null) => void;
    /** How far the dictionary for the word's language has got, where it is on its way. */
    arriving?: number | null;
  }

  let {
    answer,
    recorded = false,
    accent = '',
    diagram,
    eased = false,
    points = null,
    onPlay,
    onPlayUrl,
    onOpen,
    onSymbol,
    arriving = null,
  }: Props = $props();

  let opened = $state<string | null>(null);
  // Only the sound the reader asked about. A card that opened with the first sound described
  // was a card about phonetics laid over a word the reader wanted translated.
  let sound = $derived(answer.symbols.find((symbol) => symbol.token === opened) ?? null);
  let picture = $state<string | null>(null);

  // The mouth that makes whichever sound the line is showing, fetched by the host that can
  // reach it. Keyed to the sound rather than to the tap, so the sound the line starts on has
  // its picture too.
  $effect(() => {
    const wanted = sound;
    picture = null;
    if (!wanted?.diagram || !diagram) return;
    void diagram(wanted.diagram).then(
      (fetched) => {
        // Only while the reader is still on it: a picture that arrives after they moved on
        // belongs to a sound the line is no longer showing.
        if (sound === wanted) picture = fetched || null;
      },
      () => {}
    );
  });

  function ask(symbol: string) {
    const next = opened === symbol ? null : symbol;
    opened = next;
    onSymbol?.(next);
  }
</script>

<AnswerCard
  {answer}
  {recorded}
  {accent}
  {eased}
  {points}
  opened={sound}
  diagram={picture}
  onSymbol={ask}
  onPlaySymbol={onPlayUrl}
  {onPlay}
  {onOpen}
  {arriving}
/>
