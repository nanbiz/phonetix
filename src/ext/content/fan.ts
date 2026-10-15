// Where the two actions offered round the finger go, as geometry alone: the same placement as
// the phone's (android ActionsFan.kt), whose unit test checks it at every place a finger can be.
//
// They sit across the leash, one either side of the finger and square to the line the circle is
// carried along, so neither covers the line the eye follows to the word nor the way the hand
// came. Where a wall or the card is in the way they first bend back from the circle together,
// then the pair turns away from what is in the way, and only where nothing fits at all are they
// pulled in from the walls: an action off the screen cannot be reached.

export interface Spot {
  x: number;
  y: number;
}

export interface Box {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** How far the pair's axis is turned from the leash, nearest first. */
const TURNS = [0, 20, -20, 40, -40, 60, -60, 90, -90, 120, -120, 150, -150, 180];

/** How far each action sits off that axis: square to it, bent back from the circle, and closer
 *  together where a corner leaves less room. */
const SPREADS = [90, 115, 140, 60, 45, 35];

/** The way the circle leans from the finger, in degrees, up where it has not swung away. */
export function leash(finger: Spot, ball: Spot): number {
  return ball.x === finger.x && ball.y === finger.y
    ? -90
    : (Math.atan2(ball.y - finger.y, ball.x - finger.x) * 180) / Math.PI;
}

/**
 * The two spots, play first, round a finger whose circle is at [ball], each [reach] from the
 * finger and [half] wide either side, inside [screen] by [margin] and clear of [card] where it
 * can be.
 */
export function around(
  finger: Spot,
  ball: Spot,
  reach: number,
  half: number,
  margin: number,
  screen: Box,
  card: Box | null,
): Spot[] {
  const lean = leash(finger, ball);
  const at = (degrees: number): Spot => ({
    x: finger.x + Math.cos(((lean + degrees) * Math.PI) / 180) * reach,
    y: finger.y + Math.sin(((lean + degrees) * Math.PI) / 180) * reach,
  });
  const fits = (p: Spot) => p.x - half >= screen.left + margin &&
    p.x + half <= screen.right - margin && p.y - half >= screen.top + margin &&
    p.y + half <= screen.bottom - margin;
  const under = (p: Spot) => !card ? 0
    : Math.max(0, Math.min(p.x + half, card.right) - Math.max(p.x - half, card.left)) *
      Math.max(0, Math.min(p.y + half, card.bottom) - Math.max(p.y - half, card.top));
  const pairs = TURNS.flatMap((turn) => SPREADS.map((spread) => [at(turn - spread), at(turn + spread)]));
  // On the screen first and clear of the card next: one the card covers can still be seen round
  // its edge, one past a wall cannot.
  const clear = pairs.find((pair) => pair.every((p) => fits(p) && under(p) === 0));
  if (clear) return clear;
  const onScreen = pairs.filter((pair) => pair.every(fits));
  const covered = (pair: Spot[]) => pair.reduce((sum, p) => sum + under(p), 0);
  if (onScreen.length > 0) {
    return onScreen.reduce((best, pair) => (covered(pair) < covered(best) ? pair : best));
  }
  // Nowhere a pair fits whole: the one turned furthest from the walls, pulled in from them.
  const past = (p: Spot) => Math.max(0, screen.left + margin + half - p.x) +
    Math.max(0, p.x + half - (screen.right - margin)) +
    Math.max(0, screen.top + margin + half - p.y) +
    Math.max(0, p.y + half - (screen.bottom - margin));
  const outside = (pair: Spot[]) => pair.reduce((sum, p) => sum + past(p), 0);
  const nearest = pairs.reduce((best, pair) => (outside(pair) < outside(best) ? pair : best));
  const clamp = (v: number, low: number, high: number) => Math.min(Math.max(v, low), high);
  return nearest.map((p) => ({
    x: clamp(p.x, screen.left + margin + half, screen.right - margin - half),
    y: clamp(p.y, screen.top + margin + half, screen.bottom - margin - half),
  }));
}
