<script lang="ts">
  // What the reader kept to learn, newest first: each as it is written, what it means, and the
  // line they met it in and where, with its sound and a way to let it go.
  import type { Kept, Settings } from '@/settings/shape';
  import { say } from '@/data/wording';
  import PLAY from 'virtual:icons/lucide/play';
  import FORGET from 'virtual:icons/lucide/x';

  interface Props {
    settings: Settings;
    change: <K extends keyof Settings>(name: K, value: Settings[K]) => void;
    /** Say it, where the surface can. */
    speak?: (text: string, lang: string) => void;
  }

  let { settings, change, speak }: Props = $props();
  let kept = $derived(settings.kept ?? []);
  const forget = (one: Kept) => change('kept', kept.filter((it) => it !== one));
</script>

<section class="group">
  <div class="card kept-list">
    {#each kept as one (one.lang + one.text + one.at)}
      <div class="kept" data-kept={one.text}>
        {#if speak}
          <button class="icon-button" data-does="play" aria-label={say('say-word', one.text)}
            onclick={() => speak?.(one.text, one.lang)}><PLAY /></button>
        {/if}
        <span class="kept-text">
          <span class="kept-head"><b>{one.text}</b>{#if one.ipa}<span class="kept-ipa">/{one.ipa}/</span>{/if}</span>
          {#if one.says}<span class="kept-says">{one.says}</span>{/if}
          {#if one.line}<span class="kept-line">“{one.line}” · {one.where}</span>{/if}
        </span>
        <button class="icon-button" data-does="forget" aria-label={say('forget-it')} title={say('forget-it')}
          onclick={() => forget(one)}><FORGET /></button>
      </div>
    {/each}
  </div>
</section>
