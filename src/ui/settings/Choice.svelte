<script lang="ts">
  // One choice out of a handful, each saying what it does.
  //
  // A row of one-word buttons is only a choice for a reader who already knows what the words
  // mean: "both" says nothing to somebody who has not worked out what the other four are. So
  // each choice carries its own sentence, and the one in force is marked.

  interface Props {
    /** What it is called, what it does, and the value it stands for. */
    options: { value: string; label: string; about: string }[];
    chosen: string;
    /** What each option reports itself under, so a check can press one by name. */
    mark?: string;
    change: (value: string) => void;
  }

  let { options, chosen, mark = 'data-choice', change }: Props = $props();
</script>

<div class="choices">
  {#each options as option (option.value)}
    <button
      class="choice {chosen === option.value ? 'on' : ''}"
      {...{ [mark]: option.value }}
      aria-pressed={chosen === option.value}
      onclick={() => change(option.value)}
    >
      <span class="c-what">
        <span class="c-name">{option.label}</span>
        <span class="c-about">{option.about}</span>
      </span>
      {#if chosen === option.value}<span class="c-mark" aria-hidden="true">✓</span>{/if}
    </button>
  {/each}
</div>
