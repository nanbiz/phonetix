<script lang="ts">
  // A lemma's whole entry, in the card: every sense, under the part of speech it belongs to,
  // with what the dictionary marks it as.
  //
  // Opened from the lemma on a form line, so a reader who met "anduvo" can read everything
  // "andar" means without leaving the page.
  import type { Answer } from '@/core/answer';
  import { valueText } from './grammar';

  interface Props {
    entry: Answer;
  }

  let { entry }: Props = $props();

  /** The senses of each word the spelling is, in the reader's language where the dictionary
   *  reached it and in English otherwise, each with its marks where the core sent them. */
  let groups = $derived(
    (() => {
      const readings =
        entry.readings.length > 0
          ? entry.readings
          : [{
              pos: entry.pos, ipa: entry.ipa, says: entry.says, glosses: entry.glosses,
              marks: entry.marks, examples: entry.examples,
            }];
      return readings
        .map((reading) => {
          const senses = reading.says.length > 0 ? reading.says : reading.glosses;
          // Marks and examples are the glosses', sense by sense, so they go with the senses
          // where those are the glosses or one word for each of them.
          const bySense = senses.length === reading.glosses.length;
          return {
            pos: reading.pos ?? '',
            senses: senses.map((sense, i) => ({
              sense,
              marks: bySense
                ? (reading.marks?.[i] ?? []).map((mark) => mark.replaceAll('-', ' '))
                : [],
              example: bySense ? (reading.examples?.[i] ?? null) : null,
            })),
          };
        })
        .filter((group) => group.senses.length > 0);
    })()
  );
</script>

<div class="entry" data-entry>
  {#each groups as group, g (g)}
    {#if group.pos}<div class="entry-pos">{group.pos}</div>{/if}
    <ol>
      {#each group.senses as it, i (i)}
        <li>
          {it.sense}{#each it.marks as mark (mark)}<span
              class="entry-mark{valueText(mark.replaceAll(' ', '-')) ? ' explained' : ''}"
              title={valueText(mark.replaceAll(' ', '-')) ?? undefined}>{mark}</span
            >{/each}
          {#if it.example}<span class="entry-example">{it.example}</span>{/if}
        </li>
      {/each}
    </ol>
  {/each}
</div>
