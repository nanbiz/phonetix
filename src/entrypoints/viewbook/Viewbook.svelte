<script lang="ts">
  // Every state the card has, side by side, in both modes.
  //
  // The answers are made here rather than looked up: what is being looked at is the card,
  // and a card that needs a dictionary open before it can be seen is a card nobody can check
  // until everything else works.
  import Opened from '@/ui/card/Opened.svelte';
  import { themeOf } from '@/ui/theme';
  import { sendMessage } from '@/host/messages';
  import type { Answer } from '@/core/answer';
  import { EVERY_PART, type CardParts } from '@/ui/card/parts';
  import '@/ui/tokens.css';
  import '@/ui/card/card.css';

  function answer(over: Partial<Answer>): Answer {
    return {
      state: 'Entry',
      spelling: 'perro',
      lemma: null,
      form: null,
      pos: 'noun',
      ipa: ['ˈpe.ro'],
      symbols: [],
      says: ['Hund'],
      glosses: ['dog'],
      marks: [['masculine']],
      example: null,
      readings: [],
      provenance: { kind: 'dictionary', pack: 'lex-es' },
      source: 'es',
      target: 'de',
      ...over,
    };
  }

  // What a word is, said in the reader's language, as the host has the translator say the
  // dictionary's English definition: the lookups are real (en and es packs), the Spanish is
  // written here in place of the translator.
  const english = (over: Partial<Answer>): Answer =>
    answer({ source: 'en', target: 'es', marks: [], provenance: { kind: 'dictionary', pack: 'lex-en' }, ...over });
  const sophomore = english({
    state: 'Homograph',
    spelling: 'sophomore',
    ipa: ['ˈsɑf.mɔɹ'],
    says: [],
    glosses: ['A second-year undergraduate student in a college or university.'],
    described: 'Estudiante de segundo año en una universidad.',
    readings: [
      { pos: 'noun', ipa: ['ˈsɑf.mɔɹ'], says: [], glosses: ['A second-year undergraduate student.'] },
      {
        pos: 'adj', ipa: ['ˈsɑf.mɔɹ'], says: [], glosses: ['The second in a series.'],
        described: 'Segundo de una serie.',
      },
    ],
  });
  const dogs = english({
    state: 'Homograph',
    spelling: 'dogs',
    ipa: ['dɑɡz'],
    says: ['perros'],
    glosses: ['The species Canis familiaris.'],
    described: 'La especie Canis familiaris.',
    readings: [
      { pos: 'noun', ipa: ['dɑɡz'], says: ['perros'], glosses: ['The species Canis familiaris.'] },
      {
        pos: 'verb', ipa: ['dɑɡz'], says: ['perseguir'], glosses: ['To pursue with the intent to catch.'],
        described: 'Seguir con la intención de atrapar.',
      },
    ],
  });

  const states: { uid: string; answer: Answer; parts?: CardParts }[] = [
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
    {
      uid: 'phrase',
      answer: answer({
        state: 'Phrase',
        spelling: 'el perro corre por el camino',
        ipa: [],
        says: ['der Hund läuft den Weg entlang'],
        glosses: [],
        pos: null,
        marks: [],
        provenance: { kind: 'guess', engine: 'Bergamot' },
      }),
    },
    { uid: 'described', answer: sophomore },
    {
      uid: 'same-word',
      answer: english({
        spelling: 'brunch',
        ipa: ['bɹʌnt͡ʃ'],
        says: ['brunch'],
        glosses: ['A meal eaten later in the day than breakfast and earlier than lunch.'],
        described: 'Comida que se toma más tarde que el desayuno y antes del almuerzo.',
      }),
    },
    { uid: 'describe-every', answer: dogs, parts: { ...EVERY_PART, describe: true } },
    { uid: 'no-grammar', answer: dogs, parts: { ...EVERY_PART, grammar: false } },
    { uid: 'no-others', answer: dogs, parts: { ...EVERY_PART, others: false } },
    { uid: 'no-sound', answer: dogs, parts: { ...EVERY_PART, sound: false } },
    {
      uid: 'sound-only',
      answer: dogs,
      parts: { ...EVERY_PART, meaning: false, grammar: false, others: false },
    },
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
    <section class={themeOf(mode === 'dark')} data-mode={mode}>
      {#each drawn as state (state.uid)}
        <div class="slot" data-uid={state.uid}>
          <div class="label">{state.uid}</div>
          <Opened answer={state.answer} parts={state.parts} />
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
