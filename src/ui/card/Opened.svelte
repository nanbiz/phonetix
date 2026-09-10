<script lang="ts">
  // What a reader has open about one word: the answer, and the sound they asked about.
  //
  // The sound is described on the card's own detail line rather than in a panel under it, and
  // that line is always there and always the same height. A panel that appeared and grew with
  // the description moved the card while a reader was reading it, and a card that moves under
  // the cursor is a card that closes itself.
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
  }: Props = $props();

  let opened = $state<string | null>(null);
  // The first sound of the word until the reader picks another: the line is there either way,
  // and a described sound teaches what the line is for where a prompt only says it.
  let sound = $derived(
    answer.symbols.find((symbol) => symbol.token === opened) ??
      (opened === null ? (answer.symbols.find((symbol) => symbol.name !== '') ?? null) : null)
  );
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
/>
