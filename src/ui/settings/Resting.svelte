<script lang="ts">
  // Where the mark waits, chosen by putting it there.
  //
  // The thing being chosen is a place on a screen, so the way to choose it is a screen with
  // the mark on it. A number of pixels would mean nothing to a reader and nothing on a phone
  // of another size: what is stored is a share of the screen across and down, so the same
  // choice means the same place on any phone.
  //
  // One gesture decides everything the place is made of. Dropped against either edge the mark
  // rests on that side, the way it always has, and settles to where a hand holds it; dropped
  // anywhere else it stays exactly there. So the side, and whether it is kept where it was
  // put, are the same act of putting it somewhere - there is nothing to read and no switch to
  // find.
  //
  // The board says what the place is for as well as where it is: the ring is how far from it
  // the carry grows to its full length, and the line running out of it is where a word in the
  // middle of the page gets pointed at from, since the circle is always carried away from
  // where the mark waits.
  import { SAYS } from '@/data/wording';

  interface Props {
    /** Where it waits now, as a share of the screen, and whether that is the reader's doing. */
    x: number;
    y: number;
    pinned: boolean;
    /** Which edge it rests on when it is not. */
    side: string;
    /** The screen this stands in for, so the board is the shape of the reader's own. */
    across?: number;
    down?: number;
    /** Put somewhere of their own. */
    pin: (x: number, y: number) => void;
    /** Or left to rest on an edge. */
    rest: (side: string) => void;
  }

  let { x, y, pinned, side, across = 9, down = 19.5, pin, rest }: Props = $props();

  let board: HTMLDivElement | undefined = $state();
  let holding = $state(false);
  /** Where the mark is while a finger is on it, before it is let go and settles. */
  let held: { x: number; y: number } | null = $state(null);

  /** How close to an edge counts as being dropped on it. */
  const EDGE = 0.14;
  /** Where a mark resting on an edge sits: in from it, and down where a hand holds the phone. */
  const IN = 0.06;
  const DOWN = 0.8;
  /** How far the arrow keys move it, and how far with a shift held. */
  const STEP = 0.02;
  const STRIDE = 0.1;

  /** Where the mark is drawn: under the finger while it is being moved, at rest otherwise. */
  let at = $derived(
    held ?? (pinned ? { x, y } : { x: side === 'left' ? IN : 1 - IN, y: DOWN })
  );

  /** Which edge it would land on, if any, shown while it is being dragged. */
  let landing = $derived.by(() => {
    const put = held;
    if (!put) return '';
    return put.x <= EDGE ? 'left' : put.x >= 1 - EDGE ? 'right' : '';
  });

  /**
   * Where the circle ends up when a finger is in the middle of the page, which is what the
   * line and the second circle stand for: the carry is away from where the mark waits, so one
   * line towards the middle says the whole rule.
   */
  let away = $derived.by(() => {
    const dx = (0.5 - at.x) * across;
    const dy = (0.5 - at.y) * down;
    const far = Math.hypot(dx, dy) || 1;
    const reach = down * 0.16;
    return { x: at.x + ((dx / far) * reach) / across, y: at.y + ((dy / far) * reach) / down };
  });

  function whereOn(event: PointerEvent) {
    const box = board?.getBoundingClientRect();
    if (!box) return null;
    return {
      x: Math.min(1, Math.max(0, (event.clientX - box.left) / box.width)),
      y: Math.min(1, Math.max(0, (event.clientY - box.top) / box.height)),
    };
  }

  /** Put where it was let go: against an edge it rests there, anywhere else it stays. */
  function settle(put: { x: number; y: number }) {
    if (put.x <= EDGE) rest('left');
    else if (put.x >= 1 - EDGE) rest('right');
    else pin(put.x, put.y);
  }

  function grab(event: PointerEvent) {
    holding = true;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    held = whereOn(event);
  }

  function move(event: PointerEvent) {
    if (holding) held = whereOn(event);
  }

  function drop(event: PointerEvent) {
    (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
    holding = false;
    const put = held;
    held = null;
    if (put) settle(put);
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
    onkeydown={typed}
  >
    <!-- A page under it, so the board reads as a screen rather than as a box: what is being
         placed is a thing that sits over somebody's reading. -->
    <div class="page" aria-hidden="true">
      {#each Array(13) as _, line}
        <span class="line" style="width: {line % 3 === 2 ? 52 : 86}%"></span>
      {/each}
    </div>

    <!-- The edges it can be left to rest on, lit while it is over one. -->
    <span class="edge left{landing === 'left' ? ' taking' : ''}" aria-hidden="true"></span>
    <span class="edge right{landing === 'right' ? ' taking' : ''}" aria-hidden="true"></span>

    <!-- How far from it the carry grows to its full length: a fifth of the shorter side of the
         screen. An element rather than a shape in the drawing below, because a circle in a
         drawing stretched to the shape of a phone is an ellipse. -->
    <span class="ring" style="left: {at.x * 100}%; top: {at.y * 100}%" aria-hidden="true"></span>
    <svg class="over" viewBox="0 0 {across} {down}" preserveAspectRatio="none" aria-hidden="true">
      <line
        class="ray"
        x1={at.x * across}
        y1={at.y * down}
        x2={away.x * across}
        y2={away.y * down}
      />
    </svg>
    <span class="circle" style="left: {away.x * 100}%; top: {away.y * 100}%" aria-hidden="true"
    ></span>
    <span
      class="mark{holding ? ' held' : ''}"
      style="left: {at.x * 100}%; top: {at.y * 100}%"
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
    padding: var(--space-4) 0;
  }

  .board {
    position: relative;
    width: min(64%, 17rem);
    border-radius: var(--radius-card);
    border: var(--border-width) solid var(--color-border);
    background: var(--color-page-bg);
    overflow: hidden;
    touch-action: none;
    cursor: grab;
  }

  .board:active {
    cursor: grabbing;
  }

  .board:focus-visible {
    outline: var(--border-width) solid var(--color-accent);
    outline-offset: 2px;
  }

  .page {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    justify-content: space-evenly;
    padding: 6% 8%;
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
    width: 14%;
    background: linear-gradient(
      to right,
      color-mix(in srgb, var(--color-accent) 18%, transparent),
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

  .ring {
    position: absolute;
    width: 40%;
    aspect-ratio: 1;
    margin: -20% 0 0 -20%;
    border-radius: 50%;
    border: var(--border-width) dashed color-mix(in srgb, var(--color-accent) 40%, transparent);
    background: color-mix(in srgb, var(--color-accent) 6%, transparent);
  }

  .over {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
  }

  .ray {
    stroke: color-mix(in srgb, var(--color-accent) 50%, transparent);
    stroke-width: 1;
    stroke-linecap: round;
    vector-effect: non-scaling-stroke;
  }

  .mark {
    position: absolute;
    width: 15%;
    aspect-ratio: 1;
    margin: -7.5% 0 0 -7.5%;
    border-radius: 50%;
    background: var(--color-accent);
    box-shadow: 0 1px 5px rgb(0 0 0 / 0.28);
    transition: transform 120ms ease;
  }

  .mark.held {
    transform: scale(1.12);
  }

  .circle {
    position: absolute;
    width: 11%;
    aspect-ratio: 1;
    margin: -5.5% 0 0 -5.5%;
    border-radius: 50%;
    border: 2px solid color-mix(in srgb, var(--color-accent) 70%, transparent);
    background: color-mix(in srgb, var(--color-accent) 10%, transparent);
  }

  .said {
    margin: 0;
    max-width: 22rem;
    color: var(--color-ink-muted);
    font-size: var(--font-size-small);
    text-align: center;
  }
</style>
