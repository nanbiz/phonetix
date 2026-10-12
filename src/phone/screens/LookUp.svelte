<script lang="ts">
  // What a card says, part by part: how the word is said, and what it means with what goes
  // with the meaning. Grammar and the other words a spelling can be are about the meaning,
  // so they sit under it and go quiet with it off: a card that says "noun" and nothing else
  // says nothing. Everything off is the card off, so the last one on stays on.
  import { ROWS } from '@/data/wording';
  import type { Settings } from '@/settings/shape';
  import Group from '../parts/Group.svelte';
  import Switch from '../parts/Switch.svelte';

  interface Props {
    settings: Settings;
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
  }

  let { settings, change }: Props = $props();

  type Part = 'showSound' | 'showMeaning' | 'showGrammar' | 'showOthers' | 'describeAll';
  const ROW: Record<Part, string> = {
    showSound: 'show-sound',
    showMeaning: 'show-meaning',
    showGrammar: 'show-grammar',
    showOthers: 'show-others',
    describeAll: 'describe-all',
  };
  const TOP: Part[] = ['showSound', 'showMeaning'];
  const UNDER: Part[] = ['showGrammar', 'showOthers', 'describeAll'];
</script>

<Group>
  {#each TOP as part (part)}
    {@const alone = settings[part] && !settings[part === 'showSound' ? 'showMeaning' : 'showSound']}
    <label class="item" data-row={ROW[part]}>
      <span class="item-text"><span class="item-name" data-name>{ROWS[ROW[part]].name}</span></span>
      <Switch
        on={settings[part]}
        label={ROWS[ROW[part]].name}
        locked={alone}
        change={(on) => change(part, on)}
      />
    </label>
  {/each}
  {#each UNDER as part (part)}
    <label class="item sub" class:quiet={!settings.showMeaning} data-row={ROW[part]}>
      <span class="item-text"><span class="item-name" data-name>{ROWS[ROW[part]].name}</span></span>
      <Switch
        on={settings[part]}
        label={ROWS[ROW[part]].name}
        disabled={!settings.showMeaning}
        change={(on) => change(part, on)}
      />
    </label>
  {/each}
</Group>
