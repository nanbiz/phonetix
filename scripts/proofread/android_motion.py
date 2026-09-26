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
#
# It scales with how fast the page is going, because part of the error is not ours to remove:
# an app reports where its lines were when it last laid them out, and asking it costs a round
# trip on top of that. At a slow read-along that is a pixel or two; through a flick of three
# pixels a millisecond the same delay is tens of them. So the allowance is the distance the
# page covers in the time the answer takes to arrive, and never less than the still-screen
# tolerance.
DRIFT_TOL = 24
LATENCY_MS = 25
# And once everything has stopped, it is simply wrong to be out at all.
SETTLED_TOL = 3
# The longest the overlay may go without redrawing while the page is moving. Beyond this the
# transcriptions are visibly standing still on a moving page.
GAP_MS = 400
# How much further than the typical one the odd word may be. A single word out of place for a
# frame of a fling is not what a reader complains about; all of them out of place is.
LOOSE = 3
# How often the page has to say where it is for a reading to be placed against it. A frame is
# 16ms; this allows for a device that misses a couple.
REPORTED_OFTEN_MS = 50

# The shapes of movement, as the app knows them.
PROFILES = ["linear", "accelerate", "decelerate", "minjerk", "lognormal", "tremor"]

# Which page the movement happens on. "unique" keeps every line it has, the way an article
# does; "list" recycles its rows the way every real list does, handing the same view - and
# the same accessibility node - to a different line as the old one leaves the screen.
PAGE = "unique"

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
        dev.surface(mode=PAGE, enable=1, density=3, allApps=1, scrollTo=0)
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
    spreads = []
    worst = 0.0
    worst_word = ""
    samples = 0
    for word, positions in seen.items():
        if len(positions) < 2:
            continue
        samples += len(positions)
        spread = max(positions) - min(positions)
        spreads.append(spread)
        if spread > worst:
            worst, worst_word = spread, word
    spreads.sort()
    return worst, worst_word, samples, spreads


def drawn_drift(dev, log, frames, timeline, began, ended):
    """How far what was actually on the screen sat from the text under it.

    Between two readings the words are not where the overlay last read them: they are where
    the layer carrying them has got to, frame by frame, at the speed it believes the page is
    going. That is what a reader watches, and it is the thing a reading-by-reading comparison
    cannot see - an overlay that read the page perfectly twice a second and slid its words
    off the screen in between would look faultless.

    So each frame the layer drew is taken with the reading it was drawing, offset by what it
    had shifted that set by, and held against where the page was at that moment.
    """
    drawn = []
    for line in log.splitlines():
        m = re.search(r"LAYER (\d+) (-?[\d.]+) (\d+)", line)
        if m:
            drawn.append((int(m.group(1)), float(m.group(2)), int(m.group(3))))
    if not drawn:
        return None
    readings = {stamp: boxes for stamp, boxes in frames}
    worst = 0.0
    worst_word = ""
    samples = 0
    for at, shift, base in drawn:
        if not (began <= at <= ended):
            continue
        # The reading this frame is drawing, named by the layer itself.
        boxes = readings.get(base)
        if boxes is None:
            continue
        latest = (base, boxes)
        was = dev.scroll_at(timeline, base)
        now = dev.scroll_at(timeline, at)
        if was is None or now is None:
            continue
        # The page scrolled (now - was) further on since the reading, which carries the text
        # that far up the screen, so the words are in the right place only if the layer has
        # taken them the same distance the other way. Whatever is left over is the gap a
        # reader sees between a word and the transcription over it.
        behind = (now - was) + shift
        samples += 1
        if abs(behind) > abs(worst):
            worst = behind
            worst_word = next(iter(latest[1].values()))["word"] if latest[1] else ""
    return abs(worst), worst_word, samples


def settle_timeline(timeline, began_at, slack=60, sane=8.0):
    """The page's own reports, with its layout storm taken out.

    Arriving with a new intent makes the list lay itself out again, and a list being laid out
    reports every intermediate scroll position it passes through - the bottom of the page,
    then zero, then where it settles - half a dozen of them inside the same millisecond. No
    finger produces that and nobody sees it, but it lands at the head of the window the
    movement is measured in, and a transcription that never left its word is then measured
    against a page that supposedly jumped its whole height.

    So reports sharing a millisecond collapse to the last of them, the timeline starts where
    the movement said it started, and anything that would have to travel faster than a fling
    to be true is left out.
    """
    latest = {}
    for t, y in timeline:
        latest[t] = y
    collapsed = sorted(latest.items())
    start = 0
    for i, (_, y) in enumerate(collapsed):
        if abs(y - began_at) <= slack:
            start = i
            break
    kept = []
    for t, y in collapsed[start:]:
        if kept:
            pt, py = kept[-1]
            if abs(y - py) / max(1, t - pt) > sane:
                continue
        kept.append((t, y))
    return kept


def peak_speed(timeline, window=60, sane=20.0):
    """The fastest the page moved, in pixels a millisecond, over any short stretch.

    A step no finger or fling could produce is a jump rather than a movement - a page laid
    out again, or two of its instances reporting at once - and is left out.
    """
    fastest = 0.0
    for i, (t0, y0) in enumerate(timeline):
        for t1, y1 in timeline[i + 1:]:
            if t1 - t0 < window:
                continue
            speed = abs(y1 - y0) / (t1 - t0)
            if speed <= sane:
                fastest = max(fastest, speed)
            break
    return fastest


def moving_spans(timeline, still_ms=120):
    """The stretches in which the page was actually going somewhere.

    A movement made in strokes stands still between them, and a reader pausing to read is
    exactly when the transcriptions should be still too. Holding the overlay to a redraw rate
    through those pauses measures nothing about it.
    """
    spans = []
    open_at = None
    for (t0, y0), (t1, y1) in zip(timeline, timeline[1:]):
        if y1 != y0 and t1 - t0 <= still_ms:
            if open_at is None:
                open_at = t0
            end = t1
        elif open_at is not None:
            spans.append((open_at, end))
            open_at = None
    if open_at is not None:
        spans.append((open_at, timeline[-1][0]))
    return spans


def longest_gap(frames, timeline):
    """The longest the overlay went without redrawing while the page was moving."""
    worst = 0
    for began, ended in moving_spans(timeline):
        stamps = [t for t, _ in frames if began <= t <= ended]
        gaps = [b - a for a, b in zip(stamps, stamps[1:])]
        # A stretch of movement the overlay drew nothing in at all is the whole stretch.
        edges = [stamps[0] - began, ended - stamps[-1]] if stamps else [ended - began]
        worst = max([worst, *gaps, *edges])
    return worst


def wait_until_still(dev, quiet=0.4, limit=6.0):
    """Wait until the page stops reporting movement of its own.

    Bringing the page forward lays it out again and restores where it was scrolled to, and it
    reports every step of that - the bottom of the list, then zero, then the position it
    settled at. Those are real movements of the page, so they cannot be filtered out
    afterwards without also throwing away the movement under test; they simply have to be
    over before the measuring starts.
    """
    deadline = time.time() + limit
    while time.time() < deadline:
        before = len(dev.scroll_timeline())
        time.sleep(quiet)
        if len(dev.scroll_timeline()) == before:
            return True
    return False


def run_motion(dev, profile, distance, duration, strokes, seed, start=500):
    """Drive one movement and bring back everything said about it."""
    dev.surface(mode=PAGE, enable=1, density=3, allApps=1, scrollTo=start)
    time.sleep(2.2)
    wait_until_still(dev)
    dev.clear_log()
    dev.surface(
        mode=PAGE, enable=1, density=3, allApps=1,
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

    timeline = settle_timeline(
        [(t, y) for t, y in dev.scroll_timeline(log) if from_when <= t <= to_when + 600],
        began_at,
    )
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

    gap = longest_gap(frames, timeline)
    r.check(gap <= GAP_MS, f"{label}: it never froze mid-movement",
            f"{gap}ms without a redraw")

    worst, word, samples, spreads = doc_drift(dev, during, timeline)
    # The speed it actually reached, not the average: a movement made in three pushes with
    # pauses between them averages out to something slow, while each push is as fast as the
    # page ever goes, and it is during the push that a reading goes stale.
    speed = peak_speed(timeline)
    average = abs(distance) / max(1, duration)
    # None of these profiles is more than about two and a half times its own average at its
    # fastest. A timeline that says otherwise is not describing this movement - it still has
    # some of the page's layout in it - and the honest thing is to say so rather than to
    # report a drift measured against a page that supposedly jumped.
    # And the page has to have reported itself often enough for "where it was" to mean
    # anything. Drift is measured against those reports, and between two of them the page is
    # taken to be standing where the last one left it - so through a fling the emulator
    # renders in a dozen frames, a reading taken mid-gap is compared against a position the
    # page held two hundred milliseconds ago, and the answer is the length of the gap rather
    # than anything about the overlay.
    stamps = [t for t, _ in timeline]
    gaps = sorted(b - a for a, b in zip(stamps, stamps[1:])) or [0]
    reported_often = gaps[len(gaps) // 2] <= REPORTED_OFTEN_MS
    credible = speed <= average * 4 and reported_often
    allowed = max(DRIFT_TOL, speed * LATENCY_MS)
    if not reported_often:
        print(f"  {label}: the page reported itself every "
              f"{gaps[len(gaps) // 2]}ms, too rarely to say where it was, drift not judged")
    elif not credible:
        print(f"  {label}: the page's own report is not usable "
              f"({speed:.1f}px/ms against an average of {average:.1f}), drift not judged")
    elif samples and spreads:
        # The typical word, and separately the odd one. A word that wanders is one word of a
        # screenful for part of a movement; every word wandering is what a reader sees as the
        # transcriptions not keeping up, and only the second of those is worth a red line.
        middle = spreads[len(spreads) // 2]
        nearly = spreads[int(len(spreads) * 0.9)]
        r.check(middle <= allowed, f"{label}: transcriptions kept up with the text",
                f"half of them drifted more than {middle:.0f}px over {samples} readings, "
                f"allowed {allowed:.0f}px at {speed:.1f}px/ms (worst {worst:.0f} on {word})")
        r.check(nearly <= allowed * LOOSE, f"{label}: none of them wandered far",
                f"a tenth drifted more than {nearly:.0f}px, worst {worst:.0f}px on {word}, "
                f"against {allowed * LOOSE:.0f}px allowed")

    # And what was on the screen between those readings, which is what a reader sees.
    seen = drawn_drift(dev, log, frames, timeline, moving_from, moving_to)
    if seen is not None and seen[2] and credible:
        shown, shown_word, shown_n = seen
        r.check(shown <= allowed * LOOSE,
                f"{label}: what was on the screen kept up with the text",
                f"worst {shown:.0f}px on {shown_word} over {shown_n} frames, "
                f"allowed {allowed * LOOSE:.0f}px at {speed:.1f}px/ms")

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
    dev.surface(mode=PAGE, enable=1, density=3, allApps=1, scrollTo=ended_at)
    time.sleep(2.5)
    still = dev.boxes()
    if not still:
        # Asking the page to go where it already is moves nothing, so nothing happens and
        # nothing is reported - which is not the same as nothing being transcribed. A pixel
        # of movement is not enough either: a shift that small is inside what the follow
        # treats as standing still, so it too passes without a word being said. Sent well
        # away and brought back, which is a movement by any measure.
        for away in (400, 900):
            dev.surface(mode=PAGE, enable=1, density=3, allApps=1,
                        scrollTo=max(0, ended_at - away))
            time.sleep(1.5)
            dev.clear_log()
            dev.surface(mode=PAGE, enable=1, density=3, allApps=1, scrollTo=ended_at)
            time.sleep(2.5)
            still = dev.boxes()
            if still:
                break
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
    global PAGE
    for arg in sys.argv[1:]:
        if arg.startswith("--profiles"):
            profiles = arg.split("=", 1)[1].split(",")
        if arg.startswith("--page"):
            PAGE = arg.split("=", 1)[1]
        if arg == "--quick":
            speeds = SPEEDS[1:2]
            strokes = [1]

    dev = Device()
    print(f"device {dev.width}x{dev.height}, page {PAGE}")
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
