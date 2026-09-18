<script lang="ts">
  // Where the button sits, chosen by putting it there.
  //
  // It sits on one side or the other, at whatever height suits the hand holding the phone -
  // and nowhere else. A button in the middle of the page is a button over the words it is for
  // and under the thumb that is trying to read them, so the board takes it to the nearer side
  // however it is dragged: what the reader chooses is the side and the height, in one gesture.
  //
  // What is stored is a share of the screen down, so the same choice means the same place on
  // a phone of any size, and a number of pixels never has to be thought about.
  import { SAYS } from '@/data/wording';

  interface Props {
    /** How far down it sits, as a share of the screen, and which side it sits on. */
    y: number;
    side: string;
    /** The screen this stands in for, so the board is the shape of the reader's own. */
    across?: number;
    down?: number;
    /** Put on a side, that far down. */
    put: (side: string, y: number) => void;
  }

  let { y, side, across = 9, down = 19.5, put }: Props = $props();

  let board: HTMLButtonElement | undefined = $state();
  let holding = $state(false);
  /** Where it is while a finger is on it, before it is let go. */
  let held: { side: string; y: number } | null = $state(null);

  /** How far in from its side the button sits, and how far down it may be put. */
  const IN = 0.12;
  const TOP = 0.08;
  const FOOT = 0.92;
  /** How far the arrow keys move it, and how far with a shift held. */
  const STEP = 0.02;
  const STRIDE = 0.1;

  /**
   * What the reader last chose here, until the app has said it back.
   *
   * The app is told and answers in its own time, and the board is drawn from what it says: a
   * board that draws only that snaps back to where the button was for as long as the answer
   * takes, which reads as the choice being thrown away.
   */
  let mine: { side: string; y: number } | null = $state(null);
  $effect(() => {
    const said = mine;
    if (said && said.side === side && Math.abs(said.y - y) < 0.001) mine = null;
  });

  /**
   * How much room is left under the title, and how wide the board may be to fit in it.
   *
   * The board is the shape of the reader's own screen, so its height follows from its width:
   * sized by width alone it either leaves a band of nothing beneath it on a phone or runs off
   * the bottom of the extension's popup. Measured rather than written in the stylesheet as a
   * `vh`, because in the app's web view `vh` resolves against a height of about four pixels -
   * a board asked for a third of the screen's height came out a pixel and a half wide.
   */
  let holder: HTMLDivElement | undefined = $state();
  let room = $state(0);
  $effect(() => {
    const measure = () => {
      const top = holder?.getBoundingClientRect().top ?? 0;
      const seen = globalThis.innerHeight ?? 0;
      room = seen > 0 ? Math.max(MIN_ROOM, seen - top - EDGE) : 0;
    };
    measure();
    globalThis.addEventListener?.('resize', measure);
    return () => globalThis.removeEventListener?.('resize', measure);
  });
  /** How close to the bottom of what is left the board may come. */
  const EDGE = 24;
  const MIN_ROOM = 180;
  let wide = $derived(room > 0 ? Math.round((room * across) / down) : 0);

  let at = $derived(held ?? mine ?? { side, y });
  let where = $derived({ x: at.side === 'left' ? IN : 1 - IN, y: at.y });

  function whereOn(touch: { clientX: number; clientY: number }) {
    const box = board?.getBoundingClientRect();
    if (!box || box.width === 0) return null;
    const across = (touch.clientX - box.left) / box.width;
    const down = (touch.clientY - box.top) / box.height;
    return {
      side: across < 0.5 ? 'left' : 'right',
      y: Math.min(FOOT, Math.max(TOP, down)),
    };
  }

  function grab(event: PointerEvent) {
    // A tap puts it there as well as a drag: a board that answers only a drag is a board a
    // reader taps twice and gives up on.
    holding = true;
    held = whereOn(event);
    (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
  }

  function move(event: PointerEvent) {
    if (!holding) return;
    event.preventDefault();
    held = whereOn(event);
  }

  function letGo(said: { side: string; y: number } | null) {
    holding = false;
    held = null;
    if (!said) return;
    mine = said;
    put(said.side, said.y);
  }

  function drop(event: PointerEvent) {
    (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
    letGo(held);
  }

  // Touches as well as pointer events. A WebView that raises one and not the other leaves the
  // board answering a mouse and nothing else, which is a board that does not work on the one
  // device it is for.
  function touched(event: TouchEvent) {
    const touch = event.touches[0] ?? event.changedTouches[0];
    if (!touch) return;
    event.preventDefault();
    if (event.type === 'touchend' || event.type === 'touchcancel') {
      letGo(held ?? whereOn(touch));
      return;
    }
    holding = true;
    held = whereOn(touch);
  }

  function typed(event: KeyboardEvent) {
    const by = event.shiftKey ? STRIDE : STEP;
    if (event.key === 'ArrowLeft') letGo({ side: 'left', y: at.y });
    else if (event.key === 'ArrowRight') letGo({ side: 'right', y: at.y });
    else if (event.key === 'ArrowUp') letGo({ side: at.side, y: Math.max(TOP, at.y - by) });
    else if (event.key === 'ArrowDown') letGo({ side: at.side, y: Math.min(FOOT, at.y + by) });
    else return;
    event.preventDefault();
  }
</script>

<div class="rest" bind:this={holder}>
  <button
    type="button"
    class="board"
    style="aspect-ratio: {across} / {down}{wide ? `; width: min(84%, ${wide}px)` : ''}"
    bind:this={board}
    aria-label={SAYS['rest-put']}
    onpointerdown={grab}
    onpointermove={move}
    onpointerup={drop}
    onpointercancel={drop}
    ontouchstart={touched}
    ontouchmove={touched}
    ontouchend={touched}
    ontouchcancel={touched}
    onkeydown={typed}
  >
    <!-- A notch and a home bar, so the board is a phone at a glance rather than a rectangle a
         reader has to be told the meaning of. -->
    <span class="notch" aria-hidden="true"></span>
    <span class="home" aria-hidden="true"></span>

    <!-- A page under it, so the board reads as a screen rather than as a box: what is being
         placed is a thing that sits over somebody's reading. -->
    <span class="page" aria-hidden="true">
      {#each Array(12) as _, line}
        <span class="line" style="width: {line % 3 === 2 ? 54 : 88}%"></span>
      {/each}
    </span>

    <!-- The two sides it can sit on, the one it is headed for lit while it is moving. -->
    <span class="rail left{at.side === 'left' ? ' taking' : ''}" aria-hidden="true"></span>
    <span class="rail right{at.side === 'right' ? ' taking' : ''}" aria-hidden="true"></span>

    <span
      class="mark{holding ? ' held' : ''}"
      style="left: {where.x * 100}%; top: {where.y * 100}%"
      aria-hidden="true"
    ></span>
  </button>
</div>

<style>
  .rest {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: var(--space-4);
  }

  .board {
    position: relative;
    appearance: none;
    padding: 0;
    font: inherit;
    color: inherit;
    display: block;
    width: min(84%, 17rem);
    border-radius: var(--radius-card);
    border: var(--border-width) solid var(--color-border);
    background: var(--color-page-bg);
    overflow: hidden;
    touch-action: none;
    cursor: pointer;
  }

  .board:focus-visible {
    outline: var(--border-width) solid var(--color-accent);
    outline-offset: 2px;
  }

  .notch {
    position: absolute;
    top: 0;
    left: 50%;
    transform: translateX(-50%);
    width: 26%;
    height: 2.6%;
    border-radius: 0 0 var(--radius-symbol) var(--radius-symbol);
    background: var(--color-border);
  }

  .home {
    position: absolute;
    bottom: 2.5%;
    left: 50%;
    transform: translateX(-50%);
    width: 34%;
    height: 1.4%;
    border-radius: 999px;
    background: var(--color-ink-faint);
    opacity: 0.45;
  }

  .page {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    justify-content: space-evenly;
    /* Inside the rails, so a line of the page never runs under the track the button sits
       on. */
    padding: 11% 22% 13%;
  }

  .line {
    height: 2px;
    border-radius: 2px;
    background: var(--color-ink-faint);
    opacity: 0.3;
  }

  /* The two places it can sit, drawn as the tracks they are. */
  .rail {
    position: absolute;
    top: 8%;
    bottom: 8%;
    width: 2px;
    border-radius: 2px;
    background: var(--color-ink-faint);
    opacity: 0.26;
    transition: opacity 120ms ease, background 120ms ease;
  }

  .rail.left {
    left: 12%;
  }

  .rail.right {
    right: 12%;
  }

  .rail.taking {
    background: var(--color-accent);
    opacity: 0.35;
  }

  .mark {
    position: absolute;
    width: 16%;
    aspect-ratio: 1;
    margin: -8% 0 0 -8%;
    border-radius: 50%;
    background: var(--color-accent);
    /* A ring around it, so it reads as a thing to take hold of rather than as a dot drawn on
       a picture. */
    box-shadow:
      0 1px 5px rgb(0 0 0 / 0.3),
      0 0 0 4px color-mix(in srgb, var(--color-accent) 22%, transparent);
    transition: transform 120ms ease, box-shadow 120ms ease;
  }

  .mark.held {
    transform: scale(1.12);
    box-shadow:
      0 2px 8px rgb(0 0 0 / 0.35),
      0 0 0 8px color-mix(in srgb, var(--color-accent) 26%, transparent);
  }
</style>
