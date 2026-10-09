<script lang="ts">
  // The microphone, drawn as what it is doing: waiting to be allowed, refused, or hearing.
  //
  // One round mark in the accent, the way the panel draws its own microphone, so the page that
  // asks for it and the popup that records are plainly the same thing the reader pressed.
  import Mic from 'virtual:icons/lucide/mic';
  import MicOff from 'virtual:icons/lucide/mic-off';

  interface Props {
    state: 'asking' | 'refused' | 'recording';
  }

  let { state }: Props = $props();
</script>

<div class="hearing {state}" data-hearing={state}>
  {#if state === 'refused'}<MicOff />{:else}<Mic />{/if}
</div>
