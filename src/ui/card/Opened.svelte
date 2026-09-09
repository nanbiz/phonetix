<script lang="ts">
  // What a reader has open about one word: the answer, and the sound they asked about.
  //
  // The sheet is below the card rather than over it, so the word stays in sight while the
  // sound is being read about, and a second tap on the same symbol closes it: it is a detail
  // about the word on screen, not a place to end up in.
  import type { Answer } from '@/core/answer';
  import AnswerCard from './AnswerCard.svelte';
  import SymbolSheet from './SymbolSheet.svelte';
  import { describeSymbol } from '@/data/ipa-symbols';

  interface Props {
    answer: Answer;
    /** A picture of the mouth making a sound, fetched by the host that can reach it. */
    diagram?: (file: string) => Promise<string>;
    onPlay?: () => void;
    onPlayUrl?: (url: string) => void;
    onOpen?: (url: string) => void;
    /** Told when a sound is opened or closed, so a host can measure what is on screen. */
    onSymbol?: (symbol: string | null) => void;
  }

  let { answer, diagram, onPlay, onPlayUrl, onOpen, onSymbol }: Props = $props();

  let opened = $state<string | null>(null);
  let picture = $state<string | null>(null);

  async function ask(symbol: string) {
    const next = opened === symbol ? null : symbol;
    opened = next;
    picture = null;
    onSymbol?.(next);
    const file = next ? describeSymbol(next)?.diagram : undefined;
    if (next && file && diagram) {
      const fetched = await diagram(file).catch(() => '');
      // Only if the reader is still on the same sound: a picture that arrives after they
      // moved on belongs to a sheet that is no longer open.
      if (opened === next) picture = fetched || null;
    }
  }
</script>

<AnswerCard {answer} onSymbol={ask} {onPlay} {onOpen} />
{#if opened}
  <SymbolSheet symbol={opened} diagram={picture} onPlay={onPlayUrl} {onOpen} />
{/if}
