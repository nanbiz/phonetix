<script lang="ts">
  // Every state the card has, side by side, in both modes.
  //
  // The answers are made here rather than looked up: what is being looked at is the card,
  // and a card that needs a dictionary open before it can be seen is a card nobody can check
  // until everything else works.
  import Opened from '@/ui/card/Opened.svelte';
  import { sendMessage } from '@/host/messages';
  import type { Answer } from '@/core/answer';
  import '@/ui/tokens.css';
  import '@/ui/card/card.css';

  function answer(over: Partial<Answer>): Answer {
    return {
      state: 'Entry',
      spelling: 'perro',
      lemma: null,
      pos: 'noun',
      ipa: ['ˈpe.ro'],
      symbols: [],
      says: ['Hund'],
      glosses: ['dog'],
      example: null,
      readings: [],
      provenance: { kind: 'dictionary', pack: 'lex-es' },
      source: 'es',
      target: 'de',
      ...over,
    };
  }

  const states: { uid: string; answer: Answer }[] = [
    { uid: 'entry', answer: answer({ example: 'El perro ladra.' }) },
    { uid: 'form', answer: answer({ state: 'Form', spelling: 'perros', lemma: 'perro' }) },
    {
      uid: 'homograph',
      answer: answer({
        state: 'Homograph',
        spelling: 'banco',
        ipa: ['ˈbaŋ.ko'],
        says: ['Bank'],
        glosses: ['bench', 'a financial institution'],
        readings: [
          { pos: 'noun', ipa: ['ˈbaŋ.ko'], says: ['Bank'], glosses: ['bench'] },
          { pos: 'verb', ipa: ['ˈbaŋ.ko'], says: ['buchen'], glosses: ['to bank'] },
        ],
      }),
    },
    {
      uid: 'guess',
      answer: answer({
        state: 'Guess',
        spelling: 'chubasco',
        ipa: ['tʃuˈβas.ko'],
        says: ['Regenschauer'],
        glosses: [],
      }),
    },
    {
      uid: 'anchored',
      answer: answer({ spelling: 'camino', ipa: ['kaˈmi.no'], says: [], glosses: ['way, route'] }),
    },
    {
      uid: 'senses',
      answer: answer({
        spelling: 'punto',
        ipa: ['ˈpun.to'],
        says: ['Punkt'],
        glosses: ['point', 'dot', 'stitch', 'spot'],
      }),
    },
    {
      uid: 'ipa-only',
      answer: answer({ state: 'IpaOnly', says: [], glosses: [], pos: null, source: '', target: '' }),
    },
    {
      uid: 'no-pack',
      answer: answer({ state: 'NoPack', ipa: [], says: [], glosses: [], pos: null }),
    },
    { uid: 'none', answer: answer({ state: 'None', ipa: [], says: [], glosses: [], pos: null }) },
  ];

  const modes = ['light', 'dark'] as const;

  // The sounds of each transcription come from the core, like everywhere else: a page that
  // split them itself would be showing a card nobody else draws.
  let drawn = $state(states);
  $effect(() => {
    void (async () => {
      drawn = await Promise.all(
        states.map(async (state) => ({
          ...state,
          answer: {
            ...state.answer,
            symbols: state.answer.ipa[0]
              ? await sendMessage('symbols', { ipa: state.answer.ipa[0] })
              : [],
          },
        }))
      );
    })();
  });
</script>

<main>
  {#each modes as mode (mode)}
    <section class="theme-paper mode-{mode}" data-mode={mode}>
      {#each drawn as state (state.uid)}
        <div class="slot" data-uid={state.uid}>
          <div class="label">{state.uid}</div>
          <Opened answer={state.answer} />
        </div>
      {/each}
    </section>
  {/each}
</main>

<style>
  main {
    display: flex;
    flex-direction: column;
    margin: 0;
  }
  section {
    background: var(--color-page-bg);
    color: var(--color-ink);
    padding: var(--space-4);
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-4);
    align-items: flex-start;
  }
  .slot {
    width: var(--card-width);
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .label {
    font-family: var(--font-ui);
    font-size: var(--font-size-label);
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--color-ink-muted);
  }
</style>
