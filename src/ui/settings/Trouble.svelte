<script lang="ts">
  // What is not working, where anything is not.
  //
  // Nothing at all when everything answers, which is almost always. An engine that has quietly
  // stopped looks exactly like a word no dictionary holds - the annotation is simply missing -
  // so the one place a reader can find out is here, before they conclude the product is bad at
  // the language they are reading.

  interface Props {
    /** What went wrong, in the host's own words. Empty is the ordinary state. */
    trouble?: string[];
  }

  let { trouble = [] }: Props = $props();
</script>

{#if trouble.length > 0}
  <div class="alert across" data-row="trouble" role="status">
    <!-- What is wrong and what it costs, and nothing about what to do: what a reader can do
         about an engine depends on which one it was, and guessing at it in one sentence for
         all of them is advice that is wrong more often than it is right. -->
    <span data-name>
      {trouble.join('; ')}. Words it would have answered are missing until it does.
    </span>
  </div>
{/if}
