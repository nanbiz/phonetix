<script lang="ts">
  // What a reader has open about one word: the answer, the sound they asked about, and what
  // they moved on to from the word: another form of it, or its lemma's whole entry. Either way
  // the page's word stays the way back, and the page itself is left as it is.
  //
  // The sound is described on a line under the transcription that is there only once a sound
  // has been asked about: the card grows downward from the word it is under, so the line it
  // adds moves nothing the reader is looking at.
  import type { Answer, ParadigmForm } from '@/core/answer';
  import AnswerCard from './AnswerCard.svelte';
  import { samePlace, sharedStart } from './grammar';

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
    /** Look a word up the way the page's word was, for its lemma's entry and its other forms. */
    lookUp?: (word: string) => Promise<Answer | null>;
    /** Told when the card changes what it reads as, so a host can keep it in the window. */
    onGrow?: () => void;
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
    lookUp,
    onGrow,
  }: Props = $props();

  /** The form the card reads as instead of the page's word, where the reader picked one. */
  let viewing = $state<Answer | null>(null);
  /** The lemma's entry, where the reader opened it. */
  let lemma = $state<Answer | null>(null);
  let shown = $derived(lemma ?? viewing ?? answer);
  // A new word on the page is a new card: whatever was open about the last one is not about it.
  // The same word filled in with more, a recording or a transcription, keeps what is open.
  let about = '';
  $effect(() => {
    const now = answer.spelling;
    if (now === about) return;
    about = now;
    viewing = null;
    lemma = null;
  });

  function grew(): void {
    opened = null;
    onGrow?.();
  }

  /** How many forms the reader has picked, so a lookup that comes back late for an earlier
   *  one is not taken for the one on the card. */
  let picks = 0;

  /** Back to the word on the page. */
  function back(): void {
    picks++;
    viewing = null;
    lemma = null;
    grew();
  }

  /**
   * Read the card as another form of the word, at once from the row picked and then with what
   * a lookup adds: how it is said, and the word in every other value of its own categories
   * where the lookup reads it as the same place the row is.
   */
  function pick(form: ParadigmForm): void {
    if (form.here) return;
    const page = answer;
    if (form.spelling.toLowerCase() === page.spelling.toLowerCase() && page.paradigm &&
      samePlace(form.place, page.paradigm.place)) {
      back();
      return;
    }
    const lemmaOf = page.lemma ?? page.spelling;
    const picked: Answer = {
      ...page,
      state: 'Form',
      lead: null,
      spelling: form.spelling,
      lemma: lemmaOf,
      ipa: [],
      symbols: [],
      paradigm: {
        place: form.place,
        endingAt: sharedStart([form.spelling, lemmaOf]),
        said: form.said,
        along: [],
      },
    };
    viewing = picked;
    lemma = null;
    grew();
    // Compared by which pick it was rather than by the object, which the state has wrapped.
    const turn = ++picks;
    void lookUp?.(form.spelling).then((found) => {
      if (!found || turn !== picks || !viewing) return;
      const same = found.paradigm && samePlace(found.paradigm.place, form.place);
      viewing = {
        ...picked,
        ipa: found.ipa,
        symbols: found.symbols,
        paradigm: same && found.paradigm
          ? { ...found.paradigm, said: found.paradigm.said ?? form.said }
          : picked.paradigm,
      };
      onGrow?.();
    }, () => {});
  }

  /** Open the lemma's entry. */
  function openLemma(): void {
    const word = shown.lemma;
    if (!word || !lookUp) return;
    void lookUp(word).then((found) => {
      if (!found) return;
      lemma = { ...found, spelling: word };
      grew();
    }, () => {});
  }

  let opened = $state<string | null>(null);
  // Only the sound the reader asked about. A card that opened with the first sound described
  // was a card about phonetics laid over a word the reader wanted translated.
  let sound = $derived(shown.symbols.find((symbol) => symbol.token === opened) ?? null);
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
  answer={shown}
  recorded={shown === answer && recorded}
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
  back={shown === answer ? null : answer.spelling}
  onBack={back}
  entry={lemma !== null}
  onLemma={lookUp ? openLemma : undefined}
  onForm={pick}
/>
