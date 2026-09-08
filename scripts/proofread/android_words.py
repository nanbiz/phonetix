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
    # The list an ordinary app is built from, and one of the two containers whose scroll
    # events say in real pixels how far it has just moved.
    ("a list", "recycler"),
    # A page that scrolls as one piece, which is what a ScrollView, a WebView and most
    # article-shaped screens are - and the only kind whose scroll events say in pixels how far
    # it has just moved.
    ("wrapped paragraphs", "essay"),
    # A conversation: a list that recycles, whose rows are messages wrapping over many lines.
    # This is the shape of the app the fault was reported on, and the only fixture that
    # reproduces it - a list of one-line rows never does, because the row and the line are the
    # same thing.
    ("a conversation", "chat"),
]
# What share of the transcriptions on the screen may name a word that is not under them. Not
# what the code manages - what a reader would accept, which is next to none.
STRAYS_ALLOWED = 0.05
# How near in time a report of where the lines are has to be to be used for a reading.
SAME_MOMENT_MS = 400
# How far a transcription may sit from the line its word is on. A line of these pages is about
# 55 pixels, so this is one: nearer than that and it is late, further and it is on other text.
A_LINE = 55
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


def layer_frames(log):
    """Every frame the moving layer drew, and where it had carried the words to.

    The rectangles the service logs are the ones it worked out at a reading. Between readings
    the layer slides the whole set by a prediction of its own, so what a reader sees is the
    rectangle plus that carry - and a check that read only the rectangles was judging numbers
    that were never on the screen. Through a drag the two are eighty pixels apart on average
    and once were two hundred.
    """
    out = []
    for line in log.splitlines():
        m = re.search(r"LAYER (\d+) (-?[\d.]+) \d+ showing=(\d)", line)
        if m:
            out.append((int(m.group(1)), float(m.group(2)), m.group(3) == "1"))
    return out


def drawing(log):
    """When anything of ours was on the screen, from the service's own account.

    Two things take the words off: the moving layer stops drawing when it cannot place them,
    and the pass that hands them back to the small windows draws nothing when the reading it
    would place them from is too old. Both have to be read, or a fix that withholds is
    measured as though it had drawn.
    """
    out = []
    for line in log.splitlines():
        m = re.search(r"LAYER (\d+) \S+ \d+ showing=(\d)", line)
        if m:
            out.append((int(m.group(1)), m.group(2) == "1"))
        m = re.search(r"DREW none at (\d+)", line)
        if m:
            out.append((int(m.group(1)), False))
    return out


def was_drawing(spans, when, window=120):
    """Whether anything was on the layer then. True when the layer was not running at all."""
    near = [(abs(at - when), on) for at, on in spans if abs(at - when) <= window]
    return min(near)[1] if near else True


def judge(r, dev, log, label, newest_only=False, window=SAME_MOMENT_MS, document=None):
    """How far each transcription is from the line its own word is on.

    Not "which line is under it": a transcription that lands in the gap between two lines, or
    off the text altogether, is under no line at all - and asking it that way skipped exactly
    those, which are the ones a reader complains about. Deliberately putting every
    transcription two hundred pixels from its word made this say "nothing to judge" rather
    than "all of them are wrong".

    So the question is asked the other way round: the word this transcription names is on a
    known line, that line is in a known place at that moment, and the distance between the two
    is the answer. A transcription whose word is on no line the page reported is skipped, and
    the count of those is reported so that skipping cannot hide anything either.
    """
    reports = lines_over_time(log)
    spans = drawing(log)
    timeline = dev.scroll_timeline(log)
    frames = [f for f in dev.box_frames(log) if f[1]]
    # What was on the screen, moment by moment: every frame the layer drew, holding whichever
    # reading it was drawing then, moved by however far it had carried it. Where the layer is
    # not running there is nothing between one reading and the next, so the readings are the
    # moments.
    layers = [f for f in layer_frames(log) if frames and f[0] >= frames[0][0]]
    if layers:
        moments = []
        i = 0
        for when, carry, showing in layers:
            while i + 1 < len(frames) and frames[i + 1][0] <= when:
                i += 1
            if frames[i][0] > when:
                continue
            moments.append((when, frames[i][1], carry, showing))
    else:
        moments = [(stamp, boxes, 0.0, True) for stamp, boxes in frames]
    if newest_only:
        moments = moments[-1:]
    adrift, checked, withheld, unknown = [], 0, 0, 0
    for stamp, boxes, carry, showing in moments:
        lines = lines_at(reports, stamp, window)
        if not lines and document:
            where = dev.scroll_at(timeline, stamp)
            if where is not None:
                lines = sorted((top - where, height, text) for top, height, text in document)
        if not lines:
            continue
        if not showing or not was_drawing(spans, stamp):
            withheld += len(boxes)
            continue
        for box in boxes.values():
            word = box["word"].lower()
            mine = [(top, height) for top, height, text in lines if word in text.lower().split()]
            if not mine:
                # A transcription of a word that is not on the screen at all.
                #
                # This used to be skipped as unjudgeable, which hid the worst thing the
                # overlay does: through a real scroll on a real app, a transcription is left
                # standing where its word used to be while the word itself has gone, so the
                # reader is shown a pronunciation of something they cannot see, sitting on
                # somebody else's text. Photographed on the settings app: the transcription of
                # "apps" floating over "Notification history, conversations" after "apps" had
                # scrolled away. Counting it as unknown scored that as nothing at all.
                adrift.append((box["word"], "its word is gone"))
                checked += 1
                unknown += 1
                continue
            middle = (box["rect"][1] + box["rect"][3]) / 2 + carry
            # The nearest place its own word is, since a page can hold the same word twice.
            off = min(
                0.0 if top <= middle <= top + height
                else min(abs(middle - top), abs(middle - (top + height)))
                for top, height in mine
            )
            checked += 1
            if off > A_LINE:
                adrift.append((box["word"], f"{round(off)}px away"))
    if not checked:
        print(f"  {label}: nothing to judge ({withheld} withheld, {unknown} whose word the "
              f"page did not report)")
        return None
    share = len(adrift) / checked
    print(f"  {label}: {len(adrift)} of {checked} ({100 * share:.0f}%) are not on their own "
          f"word, {withheld} withheld, {unknown} of them name a word that is not on screen")
    r.check(
        share <= STRAYS_ALLOWED,
        f"{label}: every transcription is on its own word",
        f"{len(adrift)} of {checked} are not, e.g. "
        f"{', '.join(f'{w} {d}' for w, d in adrift[:3])}",
    )
    return share


# How many movements are judged before an answer is given, and how they are made.
#
# One is not a measurement: the same page dragged four times running gave 44%, 45%, 73% and
# 16% on builds that differed in one constant. Part of that is the gesture - `input swipe`
# does not deliver the same movement twice - so the page is asked to scroll itself by exactly
# the same distance over exactly the same time instead, and the answer is the middle of five.
DRAGS = 5
MOVE_PX = 900
MOVE_MS = 1300


def a_page(r, dev, label, mode):
    print(f"{label}:")
    wrong, settled = [], []
    for round_number in range(DRAGS):
        shell("am", "force-stop", "io.github.nanbiz.phonetix")
        time.sleep(2)
        dev.enable_service()
        dev.clear_log()
        dev.surface(mode=mode, enable=1, density=3, allApps=1, marks=1, placed=1,
                    **(KNOBS or {}))
        time.sleep(8)
        document = lines_in_the_document(dev.log())
        quiet = Results()
        if round_number == 0:
            judge(r, dev, dev.log(), "  standing still", newest_only=True, window=20000,
                  document=document)

        dev.clear_log()
        dev.surface(mode=mode, enable=1, density=3, allApps=1, marks=1, rows=60,
                    motion="linear", distance=MOVE_PX, duration=MOVE_MS, strokes=1, seed=1,
                    **(KNOBS or {}))
        time.sleep(MOVE_MS / 1000 + 0.2)
        log = dev.log()
        passes = [l for l in log.splitlines() if "follow=" in l]
        gaps = [int(m.group(1)) for m in
                (re.search(r"MEAS .*arrived=(\d+)", l) for l in log.splitlines()) if m]
        share = judge(quiet, dev, log, f"  drag {round_number + 1}", document=document)
        print(f"      {len(passes)} passes, readings every "
              f"{statistics.median(gaps) if gaps else 0:.0f}ms, "
              f"{len([l for l in log.splitlines() if 'BAND ' in l])} strips")
        if share is not None:
            wrong.append(share)
        time.sleep(3)
        # A page that has stopped stops saying where its lines are, so what it last said is
        # from the end of the movement. Nudged by a pixel, which makes it say where everything
        # is now without moving anything a reader would see.
        shell("input", "swipe", "540", "1200", "540", "1199", "120")
        time.sleep(1.5)
        share = judge(quiet, dev, dev.log(), f"  after drag {round_number + 1}",
                      newest_only=True, window=3000, document=document)
        if share is not None:
            settled.append(share)
    for name, got in (("through a drag", wrong), ("once it has settled", settled)):
        if not got:
            r.check(False, f"{label}, {name}: there was something to judge", "nothing was")
            continue
        middle = statistics.median(got)
        r.check(
            middle <= STRAYS_ALLOWED,
            f"{label}, {name}: every transcription names a word that is under it",
            f"the middle drag of {len(got)} had {100 * middle:.0f}% naming a word that is not "
            f"under them (the drags: {', '.join(f'{100 * g:.0f}%' for g in got)})",
        )


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
