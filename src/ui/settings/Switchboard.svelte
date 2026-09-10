<script lang="ts">
  // Whether this is on, and whether it is on here.
  //
  // The first thing in the popup and the first thing a reader came for. Two switches, because
  // they answer two different questions: the big one is what happens on a site the reader has
  // not decided about, and the small one is the decision about this site, which wins.
  import Toggle from '@/ui/controls/Toggle.svelte';

  interface Props {
    /** The extension's own mark, from wherever the browser keeps it. */
    icon?: string;
    on: boolean;
    /** The site the reader is looking at, and its own mark. */
    site?: string;
    siteIcon?: string;
    /** Whether it is on here, which is the default unless the reader has said otherwise. */
    here?: boolean;
    /** Whether this site has a decision of its own, rather than following the default. */
    decided?: boolean;
    change: (on: boolean) => void;
    onSite?: (on: boolean) => void;
  }

  let { icon = '', on, site = '', siteIcon = '', here = true, decided = false, change, onSite }:
    Props = $props();
</script>

<div class="switchboard">
  <div class="board-row" data-row="on">
    {#if icon}<img class="board-mark" src={icon} alt="" />{/if}
    <span class="board-what">
      <span class="board-name" data-name>Phonetix</span>
      <span class="board-sub">Default for sites you have not set</span>
    </span>
    <Toggle {on} big label="annotate what I read" {change} />
  </div>

  {#if site}
    <div class="board-row" data-row="site">
      <!-- The site's own mark, and hidden rather than replaced when it will not load: a
           broken-image glyph beside a hostname is worse than the space it saves. -->
      <img
        class="board-mark small"
        src={siteIcon}
        alt=""
        onerror={function (this: HTMLImageElement) {
          this.style.visibility = 'hidden';
        }}
      />
      <span class="board-what">
        <span class="board-site" data-name>{site}</span>
        <span class="board-sub" data-about>
          {decided ? (here ? 'always on here' : 'always off here') : 'following the default'}
        </span>
      </span>
      <Toggle on={here} label="on {site}" change={(value) => onSite?.(value)} />
    </div>
  {/if}
</div>
