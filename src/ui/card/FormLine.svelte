<script lang="ts">
  // Which form of which word this is, as one line: "preterite · indicative · 3rd singular of
  // andar".
  //
  // Each term is a grammatical value a reader is learning, so each explains itself: pointing at
  // one opens a sheet under the card that names the category it is a value of, says what the
  // category and the value are in Wiktionary's words, and lists the word in every other value
  // of that category with everything else kept, the word's own row lit. A row is a form the
  // card can be read as. The lemma is the way to the whole entry, and is there only for a word
  // that is not its own lemma.
  import type { Paradigm, ParadigmAlong, ParadigmForm } from '@/core/answer';
  import {
    categoryName,
    categoryText,
    cut,
    stemOf,
    termsOf,
    valueIn,
    valueName,
    valueText,
    whoOf,
    type Term,
  } from './grammar';

  interface Props {
    paradigm: Paradigm;
    /** The word the line is about, as the card shows it. */
    spelling: string;
    lemma: string | null;
    /** Open the lemma's entry, where the host can look it up. */
    onLemma?: () => void;
    /** Read the card as another form of the word. */
    onForm?: (form: ParadigmForm) => void;
  }

  let { paradigm, spelling, lemma, onLemma, onForm }: Props = $props();

  let terms = $derived(termsOf(paradigm.place));
  let ofLemma = $derived(
    lemma && lemma.toLowerCase() !== spelling.toLowerCase() ? lemma : null
  );

  /** The term whose sheet is open, by its category. */
  let open = $state<string | null>(null);
  let term = $derived(terms.find((it) => it.category === open) ?? null);
  let along = $derived(
    term ? (paradigm.along.find((it) => it.category === term.category) ?? null) : null
  );

  // A sheet stays while the pointer crosses from its term to it, and goes a moment after the
  // pointer has left both: the gap between the card and the sheet is open page.
  let leaving: ReturnType<typeof setTimeout> | null = null;
  function stay(): void {
    if (leaving) clearTimeout(leaving);
    leaving = null;
  }
  function point(category: string): void {
    stay();
    open = category;
  }
  function leave(): void {
    stay();
    leaving = setTimeout(() => {
      open = null;
      leaving = null;
    }, 260);
  }
  /** From the keyboard, where there is no pointer to leave with. A click opens like pointing
   *  does: under a mouse the sheet is already open by then, and closing it there took away
   *  what the reader had just reached for. */
  function toggle(category: string): void {
    stay();
    open = open === category ? null : category;
  }
  function pick(form: ParadigmForm): void {
    stay();
    open = null;
    onForm?.(form);
  }

  // Under the card, or over it where the card is over its word and the sheet would cover the
  // word; and the other way where the window has no room for it on that side.
  let sheet = $state<HTMLElement | null>(null);
  let over = $state(false);
  $effect(() => {
    if (!sheet) return;
    const card = sheet.closest('.card');
    if (!card) return;
    const box = card.getBoundingClientRect();
    const tall = sheet.getBoundingClientRect().height + 8;
    const room = { under: window.innerHeight - box.bottom, over: box.top };
    const wanted = card.classList.contains('above') ? 'over' : 'under';
    const other = wanted === 'over' ? 'under' : 'over';
    over = (room[wanted] >= tall || room[wanted] >= room[other] ? wanted : other) === 'over';
  });

  /** The rows of a sheet, each cut where its ending starts. */
  function rows(of: ParadigmAlong) {
    const stem = stemOf(of.forms, lemma);
    return of.forms.map((form) => ({ form, parts: cut(form.spelling, stem) }));
  }

  /** A person grid: who down the side, how many across the top. */
  function grid(of: ParadigmAlong) {
    const stem = stemOf(of.forms, lemma);
    const people = [...new Set(of.forms.map((form) => valueIn(form.place, 'person') ?? ''))];
    const numbers = [...new Set(of.forms.map((form) => valueIn(form.place, 'number') ?? ''))];
    const saids = of.forms.map((form) => form.said ?? '');
    const who = saids.every(Boolean) ? whoOf(saids) : saids.map(() => '');
    const cell = (person: string, number: string) => {
      const at = of.forms.findIndex(
        (form) =>
          (valueIn(form.place, 'person') ?? '') === person &&
          (valueIn(form.place, 'number') ?? '') === number
      );
      return at < 0
        ? null
        : { form: of.forms[at], parts: cut(of.forms[at].spelling, stem), who: who[at] };
    };
    return { people, numbers, cell };
  }

  /** The value of a row that this sheet moves along, as the row is named. */
  function rowName(form: ParadigmForm, of: Term): string {
    return valueName(valueIn(form.place, of.category) ?? '');
  }

  const capital = (name: string) => name.charAt(0).toUpperCase() + name.slice(1);

  /** What the sheet's term and its category are, each by itself, where Wiktionary says. */
  function explained(of: Term): { name: string; text: string }[] {
    const said: { name: string; text: string }[] = [];
    for (const value of of.values) {
      const text = categoryText(value.category);
      if (text) said.push({ name: value.category, text });
    }
    // A person with its number is two categories and two values, and four definitions made a
    // sheet to read rather than a glance: the grid's own headings say what each value is.
    if (of.values.length > 1) return said;
    for (const value of of.values) {
      const text = valueText(value.value);
      if (text) said.push({ name: value.value.replaceAll('-', ' '), text });
    }
    return said;
  }
</script>

<div class="forms" data-form>
  <!-- The spaces are inside the dot and the "of", so the line copies as it reads and the gaps
       around each are even. -->
  <span class="form-terms"
    >{#each terms as it, i (it.category)}{#if i > 0}<span class="form-dot" aria-hidden="true"
          >{' · '}</span
        >{/if}<span
        class="form-term{open === it.category ? ' on' : ''}"
        role="button"
        tabindex="0"
        data-term={it.category}
        onpointerenter={() => point(it.category)}
        onpointerleave={leave}
        onclick={() => point(it.category)}
        onkeydown={(event) => event.key === 'Enter' && toggle(it.category)}>{it.label}</span
      >{/each}</span
  >{#if ofLemma}<span class="form-of"
      ><span class="form-of-word">{' of '}</span
      >{#if onLemma}<button class="form-lemma" data-lemma onclick={() => onLemma?.()}
          >{ofLemma}</button
        >{:else}<span class="form-lemma still">{ofLemma}</span>{/if}</span
    >{/if}

  {#if term}
    <div
      class="sheet{over ? ' over' : ''}"
      data-sheet={term.category}
      role="dialog"
      tabindex="-1"
      bind:this={sheet}
      onpointerenter={stay}
      onpointerleave={leave}
    >
      <div class="sheet-cat">{categoryName(term.category)} · {term.long}</div>
      {#if explained(term).length > 0}
        <div class="sheet-def">
          {#each explained(term) as line (line.name)}
            <p><b>{capital(line.name)}:</b> {line.text}</p>
          {/each}
        </div>
      {/if}
      {#if along && term.category === 'person'}
        {@const table = grid(along)}
        <div
          class="sheet-grid"
          style="grid-template-columns: var(--sheet-name-width) repeat({table.numbers.length}, minmax(0, 1fr))"
        >
          <span class="g-head first"></span>
          {#each table.numbers as number, n (number)}
            <span
              class="g-head{n === table.numbers.length - 1 ? ' last' : ''}"
              title={valueText(number) ?? undefined}>{valueName(number)}</span
            >
          {/each}
          {#each table.people as person (person)}
            <span class="g-name" title={valueText(person) ?? undefined}>{valueName(person)}</span>
            {#each table.numbers as number, n (number)}
              {@const cell = table.cell(person, number)}
              {@const last = n === table.numbers.length - 1 ? ' last' : ''}
              {#if cell}
                <button
                  class="g-cell{cell.form.here ? ' here' : ''}{last}"
                  data-spelling={cell.form.spelling}
                  onclick={() => pick(cell.form)}
                  ><span class="g-form">{cell.parts[0]}<span class="ending">{cell.parts[1]}</span
                    ></span
                  >{#if cell.who}<span class="g-who">{cell.who}</span>{/if}</button
                >
              {:else}
                <span class="g-cell empty{last}"></span>
              {/if}
            {/each}
          {/each}
        </div>
      {:else if along}
        <table class="sheet-rows">
          <tbody>
            {#each rows(along) as row, i (i)}
              <tr
                class={row.form.here ? 'here' : ''}
                data-spelling={row.form.spelling}
                tabindex="0"
                onclick={() => pick(row.form)}
                onkeydown={(event) => event.key === 'Enter' && pick(row.form)}
              >
                <td
                  class="r-name"
                  title={valueText(valueIn(row.form.place, term.category) ?? '') ?? undefined}
                  >{rowName(row.form, term)}</td
                >
                <td class="r-form">{row.parts[0]}<span class="ending">{row.parts[1]}</span></td>
                {#if row.form.said}<td class="r-said">{row.form.said}</td>{/if}
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </div>
  {/if}
</div>
