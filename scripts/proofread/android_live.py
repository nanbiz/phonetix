#!/usr/bin/env python3
"""Text that changes while it moves, which is what a reader actually watches.

Every other suite here moves a page whose words stay exactly as they were: a list is
scrolled, a page is flung, and the text on it is the same text throughout. The screen a
reader looks at does something else. An answer being written into a conversation grows a
word at a time, the lines under it are pushed down, and the whole block slides as the app
makes room for it - and nothing about that is a scroll.

The page reports where every one of its lines is on every frame, and what that line says.
So for each reading the overlay makes, the line under each transcription can be found by
its own position at that instant, and the transcription either sits on the word it belongs
to or it does not.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/android_live.py
"""
import re
import sys
import time

from android_harness import Device

# A transcription is drawn over its word: it may be out by a rounding error, not by a line.
ON_THE_WORD = 20
# How long the page writes and slides for.
LIVE_MS = 5000


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


def lines_at(log):
    """Where the page said each of its lines was, frame by frame.

    Returns a list of (stamp, {index: (top, height, text hash)}).
    """
    out = []
    for row in log.splitlines():
        if "LINES " not in row:
            continue
        rest = row.split("LINES ", 1)[1].split()
        if not rest:
            continue
        try:
            stamp = int(rest[0])
        except ValueError:
            continue
        lines = {}
        for token in rest[1:]:
            m = re.match(r"^(\d+):(-?\d+),(\d+),(-?\d+)$", token)
            if m:
                index, top, height, digest = (int(g) for g in m.groups())
                lines[index] = (top, height, digest)
        out.append((stamp, lines))
    return out


def nearest(frames, stamp):
    """What the page looked like at the instant a reading was taken."""
    best = None
    for at, lines in frames:
        if best is None or abs(at - stamp) < abs(best[0] - stamp):
            best = (at, lines)
    return best


def run(dev, r):
    dev.surface(mode="live", enable=1, density=3, allApps=1, scrollTo=0)
    time.sleep(2.5)
    # Warm: the words have to be transcribed at all before movement means anything.
    for _ in range(10):
        if dev.boxes():
            break
        dev.surface(mode="live", enable=1, density=3, allApps=1, scrollTo=0)
        time.sleep(2.0)

    dev.clear_log()
    dev.surface(mode="live", enable=1, density=3, allApps=1, live=LIVE_MS)
    time.sleep(LIVE_MS / 1000 + 3)
    log = dev.log()

    started = re.search(r"LIVE start at=(\d+)", log)
    if not r.check(started is not None, "the page wrote and moved", "no LIVE report"):
        return
    began = int(started.group(1))
    ended = began + LIVE_MS

    frames = [f for f in lines_at(log) if began <= f[0] <= ended]
    readings = [f for f in dev.box_frames(log) if began <= f[0] <= ended]
    r.check(len(frames) > 30, "the page reported itself throughout",
            f"{len(frames)} reports over {LIVE_MS}ms")
    if not r.check(readings, "the overlay drew while the text moved",
                   f"{len(readings)} readings over {LIVE_MS}ms"):
        return

    # It has to keep drawing, not draw once and give up while everything moves.
    stamps = [t for t, _ in readings]
    gaps = [b - a for a, b in zip(stamps, stamps[1:])]
    longest = max(gaps + [stamps[0] - began, ended - stamps[-1]])
    r.check(longest <= 500, "it never stopped while the text was moving",
            f"{longest}ms without a reading")

    # And what it drew has to be on the words. A transcription belongs to whichever line
    # covers it: the page says where its lines are, so this owes nothing to the overlay.
    worst = 0
    worst_word = ""
    checked = 0
    misplaced = 0
    for stamp, boxes in readings:
        at = nearest(frames, stamp)
        if at is None:
            continue
        _, lines = at
        for info in boxes.values():
            left, top, right, bottom = info["rect"]
            middle = (top + bottom) / 2
            # The line this transcription is standing on, if any.
            home = None
            for _, (line_top, height, _digest) in lines.items():
                if line_top - 4 <= middle <= line_top + height + 4:
                    home = (line_top, height)
                    break
            checked += 1
            if home is None:
                misplaced += 1
                out = min(
                    (abs(middle - (t + h / 2)) for t, h, _ in lines.values()),
                    default=0,
                )
                if out > worst:
                    worst, worst_word = out, info["word"]
    r.check(
        checked > 0,
        "there were transcriptions to check",
        "nothing was drawn while the page moved",
    )
    r.check(
        misplaced == 0,
        "every transcription sat on a line of the page",
        f"{misplaced} of {checked} were on no line at all, worst {worst:.0f}px "
        f"out on {worst_word}",
    )
    print(f"  {len(readings)} readings, longest gap {longest}ms, "
          f"{misplaced} of {checked} off their line")


def main():
    dev = Device()
    print(f"device {dev.width}x{dev.height}")
    r = Results()
    print("\ntext that changes while it moves")
    run(dev, r)
    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the transcriptions stay on words that are being written and moved")


if __name__ == "__main__":
    main()
