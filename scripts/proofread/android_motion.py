#!/usr/bin/env python3
"""The overlay held against many shapes of movement, not one.

A suite that scrolls the page one way proves the transcriptions follow that one way. What a
reader does is a different thing at every moment: a flick that is fastest at its start and
settles slowly, a reach that eases in and out, a drag at a speed no hand actually holds, a
page moved in short strokes with pauses to read between them. Each of those puts a different
distance between where the page is and where it was when the overlay last read it, and it is
in that distance that a transcription ends up beside its word instead of on it.

So the page drives itself here, frame by frame, along profiles taken from how human movement
is modelled - minimum jerk for a reach, a lognormal velocity for a rapid stroke - and reports
where it is throughout. Every reading the overlay makes is then compared against where the
page actually was at that instant, and against what a still page at the same position looks
like once the movement is over.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/android_motion.py
  ... --profiles minjerk,lognormal --quick
"""
import re
import sys
import time

from android_harness import Device, shell

# What a transcription may be out by while the page is moving before a reader would see it
# lagging behind its word. A line here is about 50px tall.
DRIFT_TOL = 24
# And once everything has stopped, it is simply wrong to be out at all.
SETTLED_TOL = 3
# The longest the overlay may go without redrawing while the page is moving. Beyond this the
# transcriptions are visibly standing still on a moving page.
GAP_MS = 400

# The shapes of movement, as the app knows them.
PROFILES = ["linear", "accelerate", "decelerate", "minjerk", "lognormal", "tremor"]

# How far and how fast, from a slow read-along to a flick.
SPEEDS = [
    ("slow", 420, 1400),
    ("brisk", 700, 700),
    ("fast", 1100, 380),
]

# One movement, and a reader's several with pauses between them.
STROKES = [1, 3]


class Results:
    def __init__(self):
        self.passed = 0
        self.failures = []

    def check(self, ok, name, detail=""):
        if ok:
            self.passed += 1
        else:
            self.failures.append(f"{name}: {detail}")
        return ok

    @property
    def total(self):
        return self.passed + len(self.failures)


def warm(dev, attempts=12):
    """Wait until the page is transcribed at all, so nothing is measured before the
    dictionary is loaded."""
    for _ in range(attempts):
        dev.clear_log()
        dev.surface(mode="unique", enable=1, density=3, allApps=1, scrollTo=0)
        time.sleep(2.5)
        boxes = dev.boxes()
        if boxes:
            return boxes
        dev.enable_service()
    return {}


def doc_drift(dev, frames, timeline):
    """How far a transcription wandered from the place in the page it belongs to.

    Every reading is turned into document coordinates by adding where the page said it was
    at that instant, and a word's place in the document does not change while the page
    scrolls under it. So the spread of one word's readings is exactly how far the overlay let
    it slip, in pixels, with nothing to guess: the page carries no word twice, so a word
    names one transcription and only that one.
    """
    seen = {}
    for stamp, boxes in frames:
        where = dev.scroll_at(timeline, stamp)
        if where is None:
            continue
        for info in boxes.values():
            seen.setdefault(info["word"], []).append(info["rect"][1] + where)
    worst = 0.0
    worst_word = ""
    samples = 0
    for word, positions in seen.items():
        if len(positions) < 2:
            continue
        samples += len(positions)
        spread = max(positions) - min(positions)
        if spread > worst:
            worst, worst_word = spread, word
    return worst, worst_word, samples


def longest_gap(frames, began, ended):
    """The longest the overlay went without redrawing while the page was moving."""
    stamps = [t for t, _ in frames if began <= t <= ended]
    if len(stamps) < 2:
        return ended - began
    gaps = [b - a for a, b in zip(stamps, stamps[1:])]
    return max(gaps + [stamps[0] - began, ended - stamps[-1]])


def run_motion(dev, profile, distance, duration, strokes, seed, start=500):
    """Drive one movement and bring back everything said about it."""
    dev.surface(mode="unique", enable=1, density=3, allApps=1, scrollTo=start)
    time.sleep(2.2)
    dev.clear_log()
    dev.surface(
        mode="unique", enable=1, density=3, allApps=1,
        motion=profile, distance=distance, duration=duration, strokes=strokes, seed=seed,
    )
    # The movement, its pauses, and enough afterwards for the overlay to settle on the page
    # where it stopped.
    time.sleep(duration / 1000 + 0.4 * strokes + 2.6)
    return dev.log()


def check_run(r, dev, profile, speed_name, distance, duration, strokes, seed):
    log = run_motion(dev, profile, distance, duration, strokes, seed)
    label = f"{profile}/{speed_name}/{strokes}-stroke"

    started = re.search(
        r"MOTION start at=(\d+) profile=(\w+) from=(\d+) distance=(-?\d+)", log)
    done = re.search(r"MOTION done at=(\d+) y=(\d+)", log)
    if not r.check(started is not None and done is not None,
                   f"{label}: the page ran the movement", "no MOTION report"):
        return None
    # The movement's own window. Arriving with a new intent makes the page lay itself out
    # again, and a re-laid-out list reports a burst of clamped scroll positions that belong
    # to no movement at all; measured against those, a transcription that never moved looks
    # like it jumped the height of the page.
    from_when = int(started.group(1))
    to_when = int(done.group(1))
    began_at = int(started.group(3))
    ended_at = int(done.group(2))
    r.check(
        abs((ended_at - began_at) - distance) <= 24,
        f"{label}: the page travelled what it was asked to",
        f"asked {distance}px, moved {ended_at - began_at}px",
    )

    timeline = [(t, y) for t, y in dev.scroll_timeline(log) if from_when <= t <= to_when + 600]
    frames = [(t, b) for t, b in dev.box_frames(log) if from_when <= t <= to_when + 600]
    # A movement the page only managed to report a handful of times is one the device
    # dropped frames through, and there is nothing in it to hold the overlay to.
    if len(timeline) < 5:
        print(f"  {label}: only {len(timeline)} position reports, too few to judge")
        return ended_at
    if len(frames) < 2:
        r.check(False, f"{label}: the overlay redrew while the page moved",
                f"{len(frames)} redraws over {duration}ms")
        return None

    moving_from, moving_to = timeline[0][0], timeline[-1][0]
    during = [f for f in frames if moving_from <= f[0] <= moving_to]
    # A movement of a second should be followed several times over, not once at each end.
    wanted = max(2, int((moving_to - moving_from) / 300))
    r.check(
        len(during) >= wanted,
        f"{label}: the overlay redrew while the page moved",
        f"{len(during)} redraws over {moving_to - moving_from}ms, wanted {wanted}",
    )

    gap = longest_gap(frames, moving_from, moving_to)
    r.check(gap <= GAP_MS, f"{label}: it never froze mid-movement",
            f"{gap}ms without a redraw")

    worst, word, samples = doc_drift(dev, during, timeline)
    if samples:
        r.check(worst <= DRIFT_TOL, f"{label}: transcriptions kept up with the text",
                f"worst drift {worst:.0f}px on {word} over {samples} readings")

    # Nothing may lose its colours while it moves: a set that falls back to a palette of ours
    # mid-movement flickers into the wrong colour and back.
    uncoloured = [
        (t, [b["word"] for b in boxes.values() if not b["sampled"]])
        for t, boxes in during
    ]
    worst_frame = max((len(w) for _, w in uncoloured), default=0)
    r.check(worst_frame == 0, f"{label}: they keep their colours while moving",
            f"{worst_frame} transcriptions without sampled colours mid-movement")

    print(f"  {label}: {ended_at - began_at}px in {moving_to - moving_from}ms, "
          f"{len(during)} redraws, worst drift {worst:.0f}px, longest gap {gap}ms")
    return ended_at


def check_settled(r, dev, profile, ended_at):
    """Where a movement leaves the transcriptions must be where a still page puts them.

    The page is jumped straight to the position the movement ended at, which is the same
    screen arrived at without any movement at all, and the two sets of transcriptions are
    compared. A follow that ends a few pixels out, or that leaves a word behind, shows here
    and nowhere else.
    """
    after_motion = dev.boxes()
    dev.clear_log()
    dev.surface(mode="unique", enable=1, density=3, allApps=1, scrollTo=ended_at)
    time.sleep(2.5)
    still = dev.boxes()
    if not r.check(bool(after_motion) and bool(still),
                   f"{profile}: there are transcriptions to compare after it stops",
                   f"{len(after_motion)} after the movement, {len(still)} on the still page"):
        return
    matched = 0
    for info in after_motion.values():
        same = [
            b for b in still.values()
            if b["word"] == info["word"]
            and abs(b["rect"][0] - info["rect"][0]) <= SETTLED_TOL
            and abs(b["rect"][1] - info["rect"][1]) <= SETTLED_TOL
        ]
        if same:
            matched += 1
    r.check(
        matched >= max(1, int(len(after_motion) * 0.8)),
        f"{profile}: where it stops is where a still page has it",
        f"{matched} of {len(after_motion)} match the same screen reached without moving",
    )


def main():
    profiles = PROFILES
    speeds = SPEEDS
    strokes = STROKES
    for arg in sys.argv[1:]:
        if arg.startswith("--profiles"):
            profiles = arg.split("=", 1)[1].split(",")
        if arg == "--quick":
            speeds = SPEEDS[1:2]
            strokes = [1]

    dev = Device()
    print(f"device {dev.width}x{dev.height}")
    if not warm(dev):
        print("FAIL - nothing is transcribed at all; is the service enabled?")
        sys.exit(1)

    r = Results()
    seed = 1
    for profile in profiles:
        print(f"\n{profile}")
        last_end = None
        for speed_name, distance, duration in speeds:
            for count in strokes:
                seed += 1
                end = check_run(r, dev, profile, speed_name, distance, duration, count, seed)
                if end is not None:
                    last_end = end
        if last_end is not None:
            check_settled(r, dev, profile, last_end)

    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the transcriptions follow every shape of movement they were given")


if __name__ == "__main__":
    main()
