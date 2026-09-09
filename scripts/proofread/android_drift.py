"""How far the words are from where they belong, in pixels, frame by frame.

Every other measure here answers yes or no about each transcription and then reports a share,
and a share of a few thousand judgements has been noisy enough that the same build reads 10%
one run and 30% the next. This asks the continuous question instead, which needs no judging at
all: the fixture reports its own scroll position as it changes, every frame the layer draws
names the reading it was drawn from, and the distance between where that reading put the words
and where the page has since got to is the error a reader is looking at.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/android_drift.py
"""
import bisect
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell

PAGES = [("a list", "recycler"), ("wrapped paragraphs", "essay"), ("a conversation", "chat")]
DRAGS = int(os.environ.get("PHONETIX_DRAGS", "5"))
# Half a line of text. Nearer than this and the transcription is on its word; further and it is
# on the line above or below.
NEAR_PX = 30
# The same drag the other suites use, so their numbers and these describe one movement.
MOVE_PX = 900
MOVE_MS = 1300


def scroll_of(log):
    """Where the page said it was, over time."""
    out = []
    for line in log.splitlines():
        m = re.search(r"PhonetixTest: SCROLLY (\d+) (-?\d+)$", line.strip())
        if m:
            out.append((int(m.group(1)), int(m.group(2))))
    out.sort()
    return out


def frames_of(log):
    """Every frame the layer drew: when, how far it had carried, which reading, and whether
    anything was on the screen."""
    out = []
    for line in log.splitlines():
        m = re.search(r"Phonetix: LAYER (\d+) (-?[\d.]+) (\d+) showing=(\d)", line)
        if m:
            out.append((int(m.group(1)), float(m.group(2)), int(m.group(3)),
                        m.group(4) == "1"))
    return out


def where_at(scroll, when):
    """The page's position at a moment, between the two reports either side of it."""
    if not scroll:
        return None
    times = [s[0] for s in scroll]
    i = bisect.bisect_left(times, when)
    if i == 0:
        return scroll[0][1]
    if i >= len(scroll):
        return scroll[-1][1]
    (t0, y0), (t1, y1) = scroll[i - 1], scroll[i]
    return y0 if t1 == t0 else y0 + (y1 - y0) * (when - t0) / (t1 - t0)


def drift(log):
    """Every drawn frame's error, and how many frames had nothing on them."""
    scroll = scroll_of(log)
    errors, blank = [], 0
    for when, drawn, read_at, showing in frames_of(log):
        if not showing:
            blank += 1
            continue
        now, then = where_at(scroll, when), where_at(scroll, read_at)
        if now is None or then is None:
            continue
        # The reading placed the words for the page as it was at read_at; the layer has carried
        # them by drawn since. They are right where the two cancel.
        errors.append(abs(drawn + (now - then)))
    return errors, blank


def main():
    dev = Device()
    if not dev.enable_service():
        print("the service will not start")
        return 1
    for label, mode in PAGES:
        errors, blank = [], 0
        for n in range(DRAGS):
            shell("am", "force-stop", "io.github.nanbiz.phonetix")
            time.sleep(1.5)
            dev.surface(mode=mode, enable=1, density=3, allApps=1, marks=1, rows=60)
            time.sleep(2)
            dev.enable_service()
            time.sleep(5)
            dev.clear_log()
            dev.surface(mode=mode, enable=1, density=3, allApps=1, marks=1, rows=60,
                        motion="linear", distance=MOVE_PX, duration=MOVE_MS, strokes=1,
                        seed=1)
            time.sleep(MOVE_MS / 1000 + 0.2)
            got, dark = drift(dev.log())
            errors += got
            blank += dark
        if not errors:
            print(f"  {label}: nothing was drawn")
            continue
        errors.sort()
        near = 100 * sum(1 for e in errors if e < NEAR_PX) / len(errors)
        print(f"  {label:22} {len(errors):5} frames drawn, {blank:4} blank. "
              f"error px: middle {errors[len(errors) // 2]:5.0f}, "
              f"nine in ten under {errors[int(0.9 * len(errors))]:5.0f}. "
              f"{near:3.0f}% within half a line")
    return 0


if __name__ == "__main__":
    sys.exit(main())
