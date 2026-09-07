#!/usr/bin/env python3
"""Is each transcription sitting on the word it is a transcription of?

Every other check here asks whether a transcription is level with a line of text. That cannot
tell one sitting on its own word from one left behind on somebody else's, because both are
level with a line - and a pronunciation of the wrong word is worse than none at all, since a
reader has no way to know it is being told about a word that is not there.

So the pages under test say where each of their lines is and what it says, as it moves, and
every transcription the service reports is matched to the line that was under it at that
moment. Its word has to be on that line or on one touching it: half a line behind is late,
which is a different fault, and only being somewhere else entirely is what this is about.

Readings the moving layer was not drawing do not count. It takes the words off the screen when
it knows it cannot follow the app, and a word withheld is not a word on the wrong text.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/android_words.py
"""
import re
import statistics
import sys
import time

from android_harness import Device, shell

# The pages this asks about, and why each one is here.
PAGES = [
    # A conversation: a list that recycles, whose rows are messages wrapping over many lines.
    # This is the shape of the app the fault was reported on, and the only fixture that
    # reproduces it - a list of one-line rows never does, because the row and the line are the
    # same thing.
    ("a conversation", "chat"),
    # The same question of a page of wrapped paragraphs that do not recycle.
    ("wrapped paragraphs", "essay"),
]
# What share of the transcriptions on the screen may name a word that is not under them. Not
# what the code manages - what a reader would accept, which is next to none.
STRAYS_ALLOWED = 0.05
# How near in time a report of where the lines are has to be to be used for a reading.
SAME_MOMENT_MS = 400
# Set from the command line, so the withholding can be turned off and its worth measured.
KNOBS = {}


class Results:
    def __init__(self):
        self.passed = 0
        self.failures = []

    def check(self, ok, what, why=""):
        if ok:
            self.passed += 1
        else:
            self.failures.append(f"{what}: {why}")
        return ok

    @property
    def total(self):
        return self.passed + len(self.failures)


def lines_over_time(log):
    """Every report of where a line of the page was on the screen, as it moved.

    A page that recycles its rows has no fixed layout to work this out from, so it says where
    each line is whenever it moves.
    """
    out = []
    for line in log.splitlines():
        m = re.search(r"PLACEDAT (\d+) (-?\d+) (\d+) (.+)$", line)
        if m:
            out.append((int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4).strip()))
    return out


def lines_in_the_document(log):
    """Where each line sits in the document, for a page whose layout does not change.

    Reported once. Where a line is on the screen at any moment is that, less how far the page
    is scrolled - which the page reports every time it moves.
    """
    out = []
    for line in log.splitlines():
        m = re.search(r"PLACED (\d+) \d+ (-?\d+) (\d+) (.+)$", line)
        if m:
            out.append((int(m.group(2)), int(m.group(3)), m.group(4).strip()))
    return out


def lines_at(reports, when, window):
    """Where the lines were at a moment: the last report of each, near enough in time."""
    seen = {}
    for stamp, top, height, text in reports:
        if stamp <= when + 50 and when - stamp < window:
            seen[text] = (top, height)
    return sorted((top, height, text) for text, (top, height) in seen.items())


def drawing(log):
    """When the moving layer was drawing anything, from its own account of itself."""
    out = []
    for line in log.splitlines():
        m = re.search(r"LAYER (\d+) \S+ \d+ showing=(\d)", line)
        if m:
            out.append((int(m.group(1)), m.group(2) == "1"))
    return out


def was_drawing(spans, when, window=120):
    """Whether anything was on the layer then. True when the layer was not running at all."""
    near = [(abs(at - when), on) for at, on in spans if abs(at - when) <= window]
    return min(near)[1] if near else True


def judge(r, dev, log, label, newest_only=False, window=SAME_MOMENT_MS, document=None):
    reports = lines_over_time(log)
    spans = drawing(log)
    timeline = dev.scroll_timeline(log)
    frames = [f for f in dev.box_frames(log) if f[1]]
    if newest_only:
        frames = frames[-1:]
    strays, checked, withheld = [], 0, 0
    for stamp, boxes in frames:
        lines = lines_at(reports, stamp, window)
        if not lines and document:
            where = dev.scroll_at(timeline, stamp)
            if where is not None:
                lines = sorted((top - where, height, text) for top, height, text in document)
        if not lines:
            continue
        if not was_drawing(spans, stamp):
            withheld += len(boxes)
            continue
        for box in boxes.values():
            middle = (box["rect"][1] + box["rect"][3]) / 2
            here = None
            for i, (top, height, _) in enumerate(lines):
                if top <= middle <= top + height:
                    here = i
                    break
            if here is None:
                continue
            checked += 1
            near = " ".join(
                lines[j][2] for j in (here - 1, here, here + 1) if 0 <= j < len(lines)
            ).lower()
            if box["word"].lower() not in near:
                strays.append((box["word"], lines[here][2][:24]))
    if not checked:
        print(f"  {label}: nothing to judge ({withheld} withheld)")
        return None
    share = len(strays) / checked
    print(f"  {label}: {len(strays)} of {checked} ({100 * share:.0f}%) name a word that is not "
          f"under them, {withheld} were withheld")
    r.check(
        share <= STRAYS_ALLOWED,
        f"{label}: every transcription names a word that is under it",
        f"{len(strays)} of {checked} do not, e.g. "
        f"{', '.join(f'{w} on {on!r}' for w, on in strays[:3])}",
    )
    return share


def a_page(r, dev, label, mode):
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    time.sleep(2)
    dev.enable_service()
    dev.clear_log()
    dev.surface(mode=mode, enable=1, density=3, allApps=1, marks=1, placed=1,
                **(KNOBS or {}))
    time.sleep(8)
    print(f"{label}:")
    document = lines_in_the_document(dev.log())
    judge(r, dev, dev.log(), "standing still", newest_only=True, window=20000, document=document)

    dev.clear_log()
    shell("input", "swipe", "540", "1536", "540", "500", "1300")
    time.sleep(1.5)
    judge(r, dev, dev.log(), "through a drag", document=document)
    time.sleep(3)
    # A page that has stopped stops saying where its lines are, so what it last said is from
    # the end of the movement. Nudged by a pixel, which makes it say where everything is now
    # without moving anything a reader would see.
    shell("input", "swipe", "540", "1200", "540", "1199", "120")
    time.sleep(1.5)
    judge(r, dev, dev.log(), "once it has settled", newest_only=True, window=3000,
          document=document)


def main():
    global KNOBS
    for arg in sys.argv[1:]:
        if "=" in arg:
            k, v = arg.split("=", 1)
            KNOBS[k] = int(v)
    dev = Device()
    if not dev.enable_service():
        print("the service will not start")
        return 1
    r = Results()
    for label, mode in PAGES:
        a_page(r, dev, label, mode)
    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        return 1
    print("\nPASS - every transcription is a transcription of the word under it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
