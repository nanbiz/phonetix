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
          : [{ pos: entry.pos, ipa: entry.ipa, says: entry.says, glosses: entry.glosses }];
      return readings
        .map((reading) => {
          const senses = reading.says.length > 0 ? reading.says : reading.glosses;
          // The answer's marks are the senses of the reading it leads with, in their order.
          const marked =
            reading.glosses.length === entry.glosses.length &&
            reading.glosses.every((gloss, i) => gloss === entry.glosses[i]);
          return {
            pos: reading.pos ?? '',
            senses: senses.map((sense, i) => ({
              sense,
              marks: marked ? (entry.marks[i] ?? []).map((mark) => mark.replaceAll('-', ' ')) : [],
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
        </li>
      {/each}
    </ol>
  {/each}
</div>
