<script lang="ts">
  // Where the button waits, chosen by putting it there on a drawing of the phone.
  //
  // It waits on one side at a height that suits the hand holding the phone, so the board takes
  // it to the nearer side however it is dragged: the side and the height in one gesture. What
  // is stored is a share of the screen down, so the same choice is the same place on any phone.
  import { SAYS } from '@/data/wording';

  interface Props {
    /** How far down it waits, as a share of the screen, and on which side. */
    y: number;
    side: string;
    /** The reader's screen, so the drawing is its shape. */
    across: number;
    down: number;
    put: (side: string, y: number) => void;
  }

  let { y, side, across, down, put }: Props = $props();

  /** How far down it may be put, as the service allows. */
  const TOP = 0.08;
  const FOOT = 0.92;
  /** How far in from its edge the button is drawn. */
  const IN = 0.13;

  let board: HTMLDivElement | undefined = $state();
  /** Where it is while a finger is on it. */
  let held: { side: string; y: number } | null = $state(null);
  /** What was last put, until the app says it back: drawn from the app's answer alone, the
   *  button jumped back for as long as the answer took. */
  let mine: { side: string; y: number } | null = $state(null);
  $effect(() => {
    if (mine && mine.side === side && Math.abs(mine.y - y) < 0.001) mine = null;
  });

  let at = $derived(held ?? mine ?? { side, y });

  function under(event: PointerEvent) {
    const box = board?.getBoundingClientRect();
    if (!box || box.width === 0) return null;
    return {
      side: event.clientX - box.left < box.width / 2 ? 'left' : 'right',
      y: Math.min(FOOT, Math.max(TOP, (event.clientY - box.top) / box.height)),
    };
  }

  function grab(event: PointerEvent) {
    board?.setPointerCapture(event.pointerId);
    held = under(event);
  }

  function move(event: PointerEvent) {
    if (held) held = under(event) ?? held;
  }

  function drop() {
    const said = held;
    held = null;
    if (!said) return;
    mine = said;
    put(said.side, Number(said.y.toFixed(4)));
  }
</script>

<div
  class="board"
  role="slider"
  tabindex="0"
  aria-label={SAYS['rest-put']}
  aria-valuemin={TOP * 100}
  aria-valuemax={FOOT * 100}
  aria-valuenow={Math.round(at.y * 100)}
  style="aspect-ratio: {across} / {down}"
  bind:this={board}
  onpointerdown={grab}
  onpointermove={move}
  onpointerup={drop}
  onpointercancel={drop}
>
  {#each Array.from({ length: 9 }, (_, n) => n) as line (line)}
    <span class="board-line" style="width: {line % 3 === 2 ? 46 : 70}%"></span>
  {/each}
  <span
    class="board-button"
    class:held={held !== null}
    style="left: {(at.side === 'left' ? IN : 1 - IN) * 100}%; top: {at.y * 100}%"
  ></span>
</div>
