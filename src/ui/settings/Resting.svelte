<script lang="ts">
  // Where the mark waits, chosen by putting it there.
  //
  // The thing being chosen is a place on the screen, so the way to choose it is a screen with
  // the mark on it: a number of pixels would mean nothing to a reader and nothing on a phone
  // of a different size. What is stored is a share of the screen across and down, so the same
  // choice means the same place on any phone and in either orientation.
  //
  // The board also says what the place is for. The mark waits there, and the circle it carries
  // is pushed away from there while a finger drags it, so the hand is never over the word: the
  // ray shows that direction and the ring shows how far out the carry takes its full length.
  import { SAYS } from '@/data/wording';

  interface Props {
    /** Where it waits now, as a share of the screen. */
    x: number;
    y: number;
    /** The screen this is standing in for, so the board is the shape of the reader's phone. */
    across?: number;
    down?: number;
    /** Whether the reader is choosing it, as opposed to being shown where it goes by itself. */
    on?: boolean;
    change: (x: number, y: number) => void;
  }

  let { x, y, across = 9, down = 19.5, on = true, change }: Props = $props();

  let board: HTMLDivElement | undefined = $state();
  let holding = $state(false);

  /** How far the arrow keys move it, and how far with a shift held. */
  const STEP = 0.02;
  const STRIDE = 0.1;

  /**
   * Where the circle ends up when a finger is in the middle of the page, which is what the
   * ray and the second circle stand for: the carry is always away from where the mark waits,
   * so one line from the mark towards the middle says the whole rule.
   */
  let away = $derived.by(() => {
    const dx = 0.5 - x;
    const dy = 0.5 - y;
    const far = Math.hypot(dx * across, dy * down) || 1;
    const reach = 0.42;
    return { x: x + (dx * across / far) * reach * (down / across) * 0.5, y: y + (dy * down / far) * reach };
  });

  function put(event: PointerEvent) {
    const box = board?.getBoundingClientRect();
    if (!box || !on) return;
    change(
      Math.min(1, Math.max(0, (event.clientX - box.left) / box.width)),
      Math.min(1, Math.max(0, (event.clientY - box.top) / box.height))
    );
  }

  function grab(event: PointerEvent) {
    if (!on) return;
    holding = true;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    put(event);
  }

  function move(event: PointerEvent) {
    if (holding) put(event);
  }

  function drop(event: PointerEvent) {
    holding = false;
    (event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId);
  }

  function typed(event: KeyboardEvent) {
    if (!on) return;
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
    change(
      Math.min(1, Math.max(0, x + step[0])),
      Math.min(1, Math.max(0, y + step[1]))
    );
  }
</script>

<div class="rest">
  <div
    class="board{on ? '' : ' idle'}"
    style="aspect-ratio: {across} / {down}"
    bind:this={board}
    role="application"
    tabindex={on ? 0 : -1}
    aria-label={SAYS['rest-put']}
    onpointerdown={grab}
    onpointermove={move}
    onpointerup={drop}
    onpointercancel={drop}
    onkeydown={typed}
  >
    <!-- The page under it, said in two lines so the board reads as a screen rather than a
         box: what is being placed is a thing that sits over somebody's reading. -->
    <div class="page" aria-hidden="true">
      {#each Array(9) as _, line}
        <span class="line" style="width: {line % 3 === 2 ? 52 : 86}%"></span>
      {/each}
    </div>
    <!-- How far out the carry grows to its full length: a fifth of the shorter side of the
         screen, which on a phone is its width. Drawn as an element rather than in the drawing
         below, so that it is a circle on a screen of any shape rather than an ellipse. -->
    <span class="ring" style="left: {x * 100}%; top: {y * 100}%" aria-hidden="true"></span>
    <svg class="over" viewBox="0 0 {across} {down}" preserveAspectRatio="none" aria-hidden="true">
      <line class="ray" x1={x * across} y1={y * down} x2={away.x * across} y2={away.y * down} />
    </svg>
    <span class="mark" style="left: {x * 100}%; top: {y * 100}%" aria-hidden="true"></span>
    <span class="circle" style="left: {away.x * 100}%; top: {away.y * 100}%" aria-hidden="true"
    ></span>
  </div>
  <p class="said">{on ? SAYS['rest-put'] : SAYS['rest-side']}</p>
</div>

<style>
  .rest {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-3) 0 var(--space-4);
  }

  .board {
    position: relative;
    width: min(58%, 15rem);
    border-radius: var(--radius-card);
    border: 1px solid var(--color-border);
    background: var(--color-page-bg);
    overflow: hidden;
    touch-action: none;
    cursor: crosshair;
    transition: opacity 120ms ease;
  }

  .board:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }

  .board.idle {
    opacity: 0.55;
    cursor: default;
  }

  .page {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 7%;
    padding: 0 8%;
  }

  .line {
    height: 2px;
    border-radius: 2px;
    background: var(--color-ink-faint);
    opacity: 0.35;
  }

  .over {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
  }

  .ring {
    position: absolute;
    width: 40%;
    aspect-ratio: 1;
    margin: -20% 0 0 -20%;
    border-radius: 50%;
    border: 1px dashed color-mix(in srgb, var(--color-accent) 45%, transparent);
    background: color-mix(in srgb, var(--color-accent) 7%, transparent);
    pointer-events: none;
  }

  .ray {
    stroke: color-mix(in srgb, var(--color-accent) 55%, transparent);
    stroke-width: 1;
    stroke-linecap: round;
    vector-effect: non-scaling-stroke;
  }

  .mark {
    position: absolute;
    width: 14%;
    aspect-ratio: 1;
    margin: -7% 0 0 -7%;
    border-radius: 50%;
    background: var(--color-accent);
    box-shadow: 0 1px 4px rgb(0 0 0 / 0.25);
  }

  .circle {
    position: absolute;
    width: 11%;
    aspect-ratio: 1;
    margin: -5.5% 0 0 -5.5%;
    border-radius: 50%;
    border: 2px solid var(--color-accent);
    background: color-mix(in srgb, var(--color-accent) 12%, transparent);
  }

  .said {
    margin: 0;
    color: var(--color-ink-muted);
    font-size: var(--font-size-small);
    text-align: center;
  }
</style>
