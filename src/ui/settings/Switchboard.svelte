<script lang="ts">
  // Whether this is on, and whether it is on here.
  //
  // The first thing in the popup and the first thing a reader came for. Two switches, because
  // they answer two different questions: the big one is what happens on a site the reader has
  // not decided about, and the small one is the decision about this site, which wins.
  import { ROWS, SAYS } from '@/data/wording';
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
    /** Whether the surface can answer a word at all. A phone that has not been allowed to
     *  read the screen cannot, and a switch that does nothing is worse than a switch that
     *  says why it is waiting. */
    ready?: boolean;
    change: (on: boolean) => void;
    onSite?: (on: boolean) => void;
    /** The other direction, which is what the mark answers: everything else here is about a
     *  word somebody else wrote, and this is a word the reader is looking for. Behind the
     *  mark rather than in the list, because it is not a setting. */
    onSay?: () => void;
  }

  let {
    icon = '',
    on,
    site = '',
    siteIcon = '',
    here = true,
    decided = false,
    ready = true,
    change,
    onSite,
    onSay,
  }: Props = $props();

  /** A press held on the mark, which is how the other direction is asked for. A tap does
   *  nothing, so a reader who meant to press the switch beside it has lost nothing. */
  let held: ReturnType<typeof setTimeout> | null = null;

  function start() {
    held = setTimeout(() => {
      held = null;
      onSay?.();
    }, 400);
  }

  function stop() {
    if (held) clearTimeout(held);
    held = null;
  }
</script>

<div class="switchboard">
  <div class="board-row" data-row="on">
    {#if icon && onSay}
      <!-- The mark is a button: held, it answers the other direction. A tap does nothing, so
           a reader who meant the switch beside it has lost nothing. -->
      <button
        class="mark-button"
        data-does="say"
        aria-label={ROWS.say.name}
        onpointerdown={start}
        onpointerup={stop}
        onpointerleave={stop}
        oncontextmenu={(event) => {
          event.preventDefault();
          onSay();
        }}
        onkeydown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') onSay();
        }}
      >
        <img class="board-mark" src={icon} alt="" />
      </button>
    {:else if icon}
      <img class="board-mark" src={icon} alt="" />
    {/if}
    <span class="board-what">
      <span class="board-name" data-name>Phonetix</span>
      <!-- What the product does, which is what belongs under its own name. This line used to
           explain what happens on sites the reader has not decided about - an edge case, as
           the first sentence anybody reads. -->
      <span class="board-sub" data-about>
        {ready ? SAYS['tagline'] : ROWS.start.about}
      </span>
    </span>
    <Toggle on={on && ready} big label="annotate what I read" enabled={ready} {change} />
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
          {decided
            ? here
              ? 'always on here'
              : 'always off here'
            : `following the switch above, which is ${on ? 'on' : 'off'}`}
        </span>
      </span>
      <Toggle on={here} label="on {site}" change={(value) => onSite?.(value)} />
    </div>
  {/if}
</div>
