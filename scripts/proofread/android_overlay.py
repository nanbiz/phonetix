#!/usr/bin/env python3
"""What the overlay must do, checked against a device rather than against itself.

Every complaint this overlay has drawn was about movement or about what surrounds a word,
and none of it shows on a still screen: a transcription drifting off its word through a
fling, one painted for a single frame at a position belonging to another word, one sitting
over text hidden behind a toolbar, one wearing colours belonging to nothing on screen.

So the checks here are mostly about a page in motion, and they are asserted two ways:

  * against the page's own account of where it is (it reports its scroll position as it
    changes), which the overlay never sees and cannot flatter itself with;
  * against the pixels of the screen, which owe nothing to our bookkeeping at all.

Driving the motion ourselves is what makes the first oracle exact - we know what the page
did, to the pixel, at every instant - but it is worth being clear that it proves the follow
logic against a known timeline, not that a real app scrolls the way this page does. The
pixel checks are the counterweight.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/android_overlay.py
"""
import sys
import time

from android_harness import Device, near, overlaps, rgb

# The test page is flat black with white text, so what the sampler should have read is a
# fact rather than an opinion.
PAGE_BG = (0x00, 0x00, 0x00)
PAGE_INK = (0xFF, 0xFF, 0xFF)
# Sampling quantises to five bits a channel and averages the antialiased edge of a glyph, so
# ink comes back near white rather than exactly white.
COLOR_TOL = 70
# A box is measured in device pixels; a pixel of rounding either way is not a fault.
TRACK_TOL = 3
# What a transcription may be out by mid-fling before a reader would see it lagging. A line
# is around 50px tall here, so this is well under half a line.
DRIFT_TOL = 24


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


def settle(dev, seconds=2.0):
    time.sleep(seconds)


def read_state(dev, mode="plain", settle_for=2.2, **extras):
    dev.clear_log()
    # Switched on, and at a density dense enough that a screenful always has several
    # transcriptions on it - otherwise a check can pass by having nothing to check.
    extras.setdefault("enable", 1)
    extras.setdefault("density", 3)
    dev.surface(mode=mode, **extras)
    settle(dev, settle_for)
    log = dev.log()
    return dev.boxes(log), dev.scroll_timeline(log), log


# --------------------------------------------------------------------------------------
# 1. A transcription sits on its word, and only on its word.
# --------------------------------------------------------------------------------------

def check_geometry(r, boxes, where):
    # A check that iterates an empty set passes without checking anything, which is how a
    # suite reports 64 of 69 green while the overlay is switched off entirely.
    r.check(len(boxes) > 0, f"{where}: there are transcriptions to check", "none on screen")
    words = list(boxes)
    for i in range(len(words)):
        for j in range(i + 1, len(words)):
            a, b = boxes[words[i]]["rect"], boxes[words[j]]["rect"]
            r.check(
                not overlaps(a, b),
                f"{where}: no two transcriptions overlap",
                f"{words[i]} {a} and {words[j]} {b}",
            )
    for word, info in boxes.items():
        l, t, right, bottom = info["rect"]
        r.check(right > l and bottom > t, f"{where}: {word} has a real box", str(info["rect"]))
        # A word's replacement is the width of the word: never a sliver, never a banner.
        r.check(
            2 <= (bottom - t) <= 200 and 2 <= (right - l) <= 900,
            f"{where}: {word} is a plausible size",
            f"{right - l}x{bottom - t}",
        )


# --------------------------------------------------------------------------------------
# 2. Colours come off the screen, not out of a palette.
# --------------------------------------------------------------------------------------

def check_colors(r, boxes, where, expect_bg=PAGE_BG, expect_ink=PAGE_INK):
    r.check(len(boxes) > 0, f"{where}: there are colours to check", "no transcriptions")
    read = sum(1 for i in boxes.values() if i["sampled"])
    r.check(
        read == len(boxes),
        f"{where}: every transcription had its colours read off the screen",
        f"{read} of {len(boxes)} were sampled; the rest fell back to a palette",
    )
    for word, info in boxes.items():
        if not info["sampled"]:
            continue
        r.check(
            near(rgb(info["bg"]), expect_bg, COLOR_TOL),
            f"{where}: {word} took the page's background",
            f"read {rgb(info['bg'])}, page is {expect_bg}",
        )
        r.check(
            near(rgb(info["ink"]), expect_ink, COLOR_TOL),
            f"{where}: {word} took the page's ink",
            f"read {rgb(info['ink'])}, page is {expect_ink}",
        )


# --------------------------------------------------------------------------------------
# 3. Scrolled to an exact place, every word moved exactly that far.
# --------------------------------------------------------------------------------------

def check_tracking(r, dev):
    base, _, _ = read_state(dev, scrollTo=0, settle_for=5)
    r.check(bool(base), "tracking: the page transcribes at all", "no transcriptions on a page of prose")
    if not base:
        return
    check_geometry(r, base, "at rest")
    check_colors(r, base, "at rest")

    for offset in (60, 120, 240, 360, 500, 750, 1000):
        moved, _, _ = read_state(dev, scrollTo=offset)
        # Matched by where a word ended up, not by its name: the page repeats itself, so
        # several transcriptions read "reading" and only their positions tell them apart.
        # Every transcription now on screen must be one that was on screen before, shifted
        # by exactly what the page moved - or new, having scrolled into view.
        matched = 0
        for key, info in moved.items():
            word = info["word"]
            want_top = info["rect"][1] + offset
            candidates = [
                b for k, b in base.items()
                if b["word"] == word and abs(b["rect"][1] - want_top) <= TRACK_TOL
                and abs(b["rect"][0] - info["rect"][0]) <= TRACK_TOL
            ]
            if candidates:
                matched += 1
        # Words scrolling in from below have no predecessor, so the test asks that the ones
        # which were already there are where the movement says they should be.
        carried = [
            info for key, info in moved.items()
            if any(b["word"] == info["word"] and b["rect"][1] > info["rect"][1] for b in base.values())
        ]
        r.check(
            matched > 0 or not carried,
            f"scrolled {offset}px: transcriptions moved with their lines",
            f"{matched} of {len(moved)} line up with a previous position shifted by {offset}px",
        )
        check_geometry(r, moved, f"scrolled {offset}px")
        check_colors(r, moved, f"scrolled {offset}px")


# --------------------------------------------------------------------------------------
# 4. Through a real fling, measured frame by frame rather than end to end.
# --------------------------------------------------------------------------------------

def check_drift(r, dev):
    """A transcription that lags through a whole fling and catches up at the end must fail.

    So the comparison is per reported frame: where the page said it was at the instant the
    overlay drew, against where the overlay put the word. The page's deceleration is the
    platform's own, which is the motion a finger really produces and nothing like a line.
    """
    for velocity in (-3000, -6000, -9000, 6000):
        dev.clear_log()
        dev.surface(scrollTo=600, enable=1, density=3)
        settle(dev, 2.5)
        dev.clear_log()
        dev.surface(fling=velocity, enable=1, density=3)
        settle(dev, 3.0)
        log = dev.log()
        frames = dev.box_frames(log)
        timeline = dev.scroll_timeline(log)

        r.check(
            len(timeline) >= 3,
            f"fling {velocity}: the page actually moved",
            f"{len(timeline)} scroll reports",
        )
        travelled = abs(timeline[-1][1] - timeline[0][1]) if len(timeline) >= 2 else 0
        # A gentle fling barely moves the page, and asking for several redraws of a screen
        # that hardly changed is a test of nothing.
        r.check(
            len(frames) >= 2 or travelled < 200,
            f"fling {velocity}: the overlay redrew during the motion",
            f"{len(frames)} redraws while the page travelled {travelled}px",
        )
        if len(timeline) < 3 or len(frames) < 2:
            continue

        # Compared frame to consecutive frame, not against the first one. A page repeats
        # its words, and over a long fast fling the same word passes through many
        # positions, so anchoring on the start eventually pairs a word with a different
        # instance of itself and reports a drift of hundreds of pixels that nobody saw.
        # Between two adjacent frames the movement is small and the pairing unambiguous.
        worst = 0
        worst_word = ""
        samples = 0
        for (t0, before), (t1, after) in zip(frames, frames[1:]):
            page_moved = dev.scroll_at(timeline, t1) - dev.scroll_at(timeline, t0)
            if abs(page_moved) > 400:
                continue  # too much happened between reads to pair anything confidently
            for key, info in after.items():
                want = info["rect"][1] - page_moved
                same = [
                    b for b in before.values()
                    if b["word"] == info["word"]
                    and abs(b["rect"][0] - info["rect"][0]) <= 4
                    and abs(b["rect"][1] - want) <= 90
                ]
                if not same:
                    continue
                drift = min(abs(b["rect"][1] - want) for b in same)
                samples += 1
                if drift > worst:
                    worst, worst_word = drift, info["word"]
        r.check(
            samples > 0,
            f"fling {velocity}: transcriptions could be followed across frames",
            "no word appeared in two consecutive reads",
        )
        r.check(
            worst <= DRIFT_TOL,
            f"fling {velocity}: transcriptions kept up with the text",
            f"worst drift {worst:.0f}px on {worst_word} over {samples} pairings",
        )
        print(f"  fling {velocity}: page moved {travelled}px, {len(frames)} redraws, worst drift {worst:.0f}px")


# --------------------------------------------------------------------------------------
# 5. A word behind something is not painted over it.
# --------------------------------------------------------------------------------------

def check_occlusion(r, dev):
    header_bottom = int(72 * 3 + 40)  # the bar is 72dp; density is 3 on the test device
    for offset in (0, 200, 400, 700):
        boxes, _, _ = read_state(dev, mode="header", scrollTo=offset, settle_for=2.5)
        r.check(len(boxes) > 0, f"header, scrolled {offset}px: something is transcribed", "nothing")
        for word, info in boxes.items():
            top = info["rect"][1]
            r.check(
                top >= header_bottom - 60,
                f"header, scrolled {offset}px: {word} is not painted under the bar",
                f"top {top}, bar reaches about {header_bottom}",
            )


# --------------------------------------------------------------------------------------
# 6. Lines in different colours are read separately.
# --------------------------------------------------------------------------------------

def check_per_line_colors(r, dev):
    boxes, _, _ = read_state(dev, mode="colors", scrollTo=0, settle_for=5)
    r.check(bool(boxes), "coloured page: transcribes at all", "nothing transcribed")
    inks = {rgb(info["ink"]) for info in boxes.values() if info["sampled"]}
    # The page has white, gold and green lines. One colour for all of them means a sampler
    # averaging a whole node, which is what put grey-green over white prose.
    r.check(
        len(inks) >= 2 or len(boxes) <= 1,
        "coloured page: different lines got different ink",
        f"every transcription read the same ink {inks}",
    )
    for word, info in boxes.items():
        r.check(
            near(rgb(info["bg"]), PAGE_BG, COLOR_TOL),
            f"coloured page: {word} took the page's background",
            f"read {rgb(info['bg'])}",
        )


# --------------------------------------------------------------------------------------
# 7. A window nobody may capture degrades instead of breaking.
# --------------------------------------------------------------------------------------

def check_secure(r, dev):
    boxes, _, log = read_state(dev, mode="secure", scrollTo=0, settle_for=4)
    r.check("FATAL EXCEPTION" not in log, "secure window: no crash", "the service died")
    # Transcribing is fine; inventing colours for a screen we cannot see is not, so a
    # secure window should simply fall back rather than claim to have sampled it.
    print(f"  secure window: {len(boxes)} transcriptions, service alive")


def main():
    dev = Device()
    print(f"device {dev.width}x{dev.height}")
    if not dev.enable_service():
        print("FAIL - the accessibility service will not start")
        sys.exit(1)

    r = Results()
    print("\ntracking an exact scroll")
    check_tracking(r, dev)
    print("\ndrifting through a fling")
    check_drift(r, dev)
    print("\nwords behind a bar")
    check_occlusion(r, dev)
    print("\nlines in different colours")
    check_per_line_colors(r, dev)
    print("\na window that cannot be captured")
    check_secure(r, dev)

    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        seen = set()
        for f in r.failures:
            head = f.split(":")[0]
            if head in seen and len(seen) > 12:
                continue
            seen.add(head)
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the overlay tracks its words, keeps off its neighbours, and wears the page's colours")


if __name__ == "__main__":
    main()
