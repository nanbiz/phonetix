<script lang="ts">
  // What made the sound the button beside it plays.
  //
  // A spark for a voice a machine generated from the transcription, a microphone for a
  // recording of a person. A reader deciding how much to trust what they hear is owed the
  // difference, and it is a property of the sound rather than a sentence to read on every
  // card, so it is drawn and carries the word only for a reader who cannot see it.
  interface Props {
    kind: 'synthesised' | 'recording';
  }

  let { kind }: Props = $props();
</script>

{#if kind === 'synthesised'}
  <svg class="ipa-mark" viewBox="0 0 20 20" role="img" aria-label="synthesised voice">
    <path d="M10 1c1 4 5 8 9 9-4 1-8 5-9 9-1-4-5-8-9-9 4-1 8-5 9-9z" />
  </svg>
{:else}
  <svg
    class="ipa-mark"
    viewBox="0 0 20 20"
    role="img"
    aria-label="recording of a speaker"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
  >
    <rect x="7" y="2" width="6" height="10" rx="3" />
    <path d="M4.5 9.5a5.5 5.5 0 0 0 11 0" />
    <path d="M10 16v3" />
  </svg>
{/if}
