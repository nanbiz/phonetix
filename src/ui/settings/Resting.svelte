<script lang="ts">
  // Where the button sits, chosen by putting it there.
  //
  // The thing being chosen is a place on a screen, so the way to choose it is a screen with
  // the button on it. A number of pixels would mean nothing to a reader and nothing on a
  // phone of another size: what is stored is a share of the screen across and down, so the
  // same choice means the same place on any phone.
  //
  // One gesture decides everything the place is made of. Put against either side the button
  // stays on that side, where it has always sat, and rides where a hand holds the phone; put
  // anywhere else it stays exactly there. Which side, and whether it is kept where it was
  // put, are the same act.
  //
  // Nothing else is drawn on the board. What the place is also for - the circle is carried
  // away from it, so a hand is never over the word - is true whether or not a reader knows
  // it, and a ring and a ray saying so were two symbols with nothing to say what they meant.
  import { SAYS } from '@/data/wording';

  interface Props {
    /** Where it sits now, as a share of the screen, and whether that is the reader's doing. */
    x: number;
    y: number;
    pinned: boolean;
    /** Which side it sits on when it is not. */
    side: string;
    /** The screen this stands in for, so the board is the shape of the reader's own. */
    across?: number;
    down?: number;
    /** Put somewhere of their own. */
    pin: (x: number, y: number) => void;
    /** Or left to sit on a side. */
    rest: (side: string) => void;
  }

  let { x, y, pinned, side, across = 9, down = 19.5, pin, rest }: Props = $props();

  let board: HTMLDivElement | undefined = $state();
  let holding = $state(false);
  /** Where the button is while a finger is on it, before it is let go and settles. */
  let held: { x: number; y: number } | null = $state(null);

  /** How close to a side counts as being put against it. */
  const EDGE = 0.16;
  /** Where a button sitting on a side goes: in from it, and down where a hand holds a phone. */
  const IN = 0.1;
  const DOWN = 0.8;
  /** How far the arrow keys move it, and how far with a shift held. */
  const STEP = 0.02;
  const STRIDE = 0.1;
  /** How far in it is kept, which is its own half-width: drawn any closer to the board's
   *  edge, the button is cut in half by it. */
  const INSIDE = 0.09;

  /** Where the button is drawn: under the finger while it is moving, where it sits otherwise. */
  let at = $derived(
    held ?? (pinned ? { x, y } : { x: side === 'left' ? IN : 1 - IN, y: DOWN })
  );

  /** Which side it would land on, if any, lit while it is being moved. */
  let landing = $derived.by(() => {
    const put = held;
    if (!put) return '';
    return put.x <= EDGE ? 'left' : put.x >= 1 - EDGE ? 'right' : '';
  });

  /** Kept off the board's own edge, so the button is always drawn whole. */
  function inside(value: number): number {
    return Math.min(1 - INSIDE, Math.max(INSIDE, value));
  }

  function whereOn(touch: { clientX: number; clientY: number }) {
    const box = board?.getBoundingClientRect();
    if (!box || box.width === 0) return null;
    return {
      x: Math.min(1, Math.max(0, (touch.clientX - box.left) / box.width)),
      y: Math.min(1, Math.max(0, (touch.clientY - box.top) / box.height)),
    };
  }

  /** Put where it was let go: against a side it sits there, anywhere else it stays put. */
  function settle(put: { x: number; y: number }) {
    if (put.x <= EDGE) rest('left');
    else if (put.x >= 1 - EDGE) rest('right');
    else pin(inside(put.x), inside(put.y));
  }

  function grab(event: PointerEvent) {
    // A tap moves it as well as a drag: a board that answers only a drag is a board a reader
    // taps twice and gives up on.
    holding = true;
    held = whereOn(event);
    (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
  }

  function move(event: PointerEvent) {
    if (!holding) return;
    event.preventDefault();
    held = whereOn(event);
  }

  function drop(event: PointerEvent) {
    (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
    holding = false;
    const put = held;
    held = null;
    if (put) settle(put);
  }

  // Touches as well as pointers. A WebView that does not raise pointer events for a finger
  // leaves the board answering a mouse and nothing else, which is a board that does not work
  // on the one device it is for.
  function touched(event: TouchEvent) {
    const touch = event.touches[0] ?? event.changedTouches[0];
    if (!touch) return;
    event.preventDefault();
    if (event.type === 'touchend' || event.type === 'touchcancel') {
      holding = false;
      const put = held ?? whereOn(touch);
      held = null;
      if (put) settle(put);
      return;
    }
    holding = true;
    held = whereOn(touch);
  }

  function typed(event: KeyboardEvent) {
    const by = event.shiftKey ? STRIDE : STEP;
    const moved: Record<string, [number, number]> = {
      ArrowLeft: [-by, 0],
      ArrowRight: [by, 0],
      ArrowUp: [0, -by],
      ArrowDown: [0, by],
    };
    const step = moved[event.key];
    if (!step) return;
    event.preventDefault();
    settle({
      x: Math.min(1, Math.max(0, at.x + step[0])),
      y: Math.min(1, Math.max(0, at.y + step[1])),
    });
  }
</script>

<div class="rest">
  <div
    class="board"
    style="aspect-ratio: {across} / {down}"
    bind:this={board}
    role="application"
    tabindex="0"
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
    <!-- A notch and a home bar, so the board is a phone at a glance rather than a rectangle
         a reader has to be told the meaning of. -->
    <span class="notch" aria-hidden="true"></span>
    <span class="home" aria-hidden="true"></span>

    <!-- A page under it, so the board reads as a screen rather than as a box: what is being
         placed is a thing that sits over somebody's reading. -->
    <div class="page" aria-hidden="true">
      {#each Array(12) as _, line}
        <span class="line" style="width: {line % 3 === 2 ? 54 : 88}%"></span>
      {/each}
    </div>

    <!-- The sides it can be left on, lit while it is over one. -->
    <span class="edge left{landing === 'left' ? ' taking' : ''}" aria-hidden="true"></span>
    <span class="edge right{landing === 'right' ? ' taking' : ''}" aria-hidden="true"></span>

    <span
      class="mark{holding ? ' held' : ''}"
      style="left: {inside(at.x) * 100}%; top: {inside(at.y) * 100}%"
      aria-hidden="true"
    ></span>
  </div>
  <p class="said">{SAYS['rest-put']}</p>
</div>

<style>
  .rest {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-4) var(--space-4);
  }

  .board {
    position: relative;
    width: min(58%, 14rem);
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
    width: 30%;
    height: 3.2%;
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
    padding: 9% 8% 8%;
  }

  .line {
    height: 2px;
    border-radius: 2px;
    background: var(--color-ink-faint);
    opacity: 0.3;
  }

  .edge {
    position: absolute;
    top: 0;
    bottom: 0;
    width: 16%;
    background: linear-gradient(
      to right,
      color-mix(in srgb, var(--color-accent) 20%, transparent),
      transparent
    );
    opacity: 0;
    transition: opacity 120ms ease;
  }

  .edge.left {
    left: 0;
  }

  .edge.right {
    right: 0;
    transform: scaleX(-1);
  }

  .edge.taking {
    opacity: 1;
  }

  .mark {
    position: absolute;
    width: 16%;
    aspect-ratio: 1;
    margin: -8% 0 0 -8%;
    border-radius: 50%;
    background: var(--color-accent);
    box-shadow: 0 1px 5px rgb(0 0 0 / 0.3);
    transition: transform 120ms ease;
  }

  .mark.held {
    transform: scale(1.15);
  }

  .said {
    margin: 0;
    max-width: 18rem;
    color: var(--color-ink-muted);
    font-size: var(--font-size-small);
    text-align: center;
  }
</style>
