#!/usr/bin/env python3
"""Whether a transcription is legible where it stands.

A transcription is drawn in colours read off the screen, so that it looks like the text it
replaces rather than a patch stuck over it. When that reading goes wrong it does not fail
loudly: it returns two colours that are nearly the same, and the word is painted in a shade
of its own background. On the screen that reads as text half rubbed out, and it is what a
reader called semi-transparent, though nothing anywhere is transparent.

The two ways it goes wrong are both here. A capture taken while the page is moving smears
white letters into a white page and averages out to the grey between them, and a line whose
glyphs are too thin to separate from the surface answers with the surface twice. Either way
the pair is close, so closeness is the thing measured: for every transcription the service
says it drew, how far its ink stands from its own background, and separately - off the real
framebuffer, owing nothing to our bookkeeping - how far the darkest pixels of the drawn word
stand from the commonest ones around them.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run scripts/proofread/android_ink.py
"""
# The image library is declared here rather than asked of the caller: a check that needs
# it fails halfway through otherwise, after the emulator has already been driven.
# /// script
# dependencies = ["pillow"]
# ///

import os
import re
import sys
import time

from PIL import Image

from android_harness import Device, shell

# The sum of the three channel differences, so black on white is 765. Below this the pair is
# not text and its surface, it is a surface measured twice: the service is meant to reject
# such a reading and paint a legible ink on the surface instead, so anything drawn this close
# together is a reading that got through.
LEGIBLE = 210
# What that same distance may be off the framebuffer, which is scaled down before it reaches
# us and blurs the two colours towards each other along every edge of every glyph. A capture
# of black on white through that scaling comes back around 600.
LEGIBLE_ON_SCREEN = 150
# How much of a chip is ink rather than surface. Letters are the minority of the pixels in
# their own rectangle, so the ink is looked for among the darkest, not the commonest.
INK_SHARE = 0.12
# How far from the top and bottom edges a transcription has to be before it is judged, and
# how tall it has to be, in the coordinates the service works in.
EDGE = 8
MIN_LINE = 20


def contrast(a, b):
    return sum(abs(((a >> s) & 0xFF) - ((b >> s) & 0xFF)) for s in (0, 8, 16))


class Results:
    def __init__(self):
        self.passed = 0
        self.failed = []

    def check(self, ok, what):
        if ok:
            self.passed += 1
        else:
            self.failed.append(what)
            print(f"    {what}")


def drawn(dev, log=None):
    """Every transcription the service last drew that had its colours read off the screen.

    Not simply the newest reading. The service reports one every time it follows the page,
    and a pass taken while a line is being re-measured reports the two words it managed - so
    reading the last line of the log judged two transcriptions on a screen showing thirty,
    and then failed to find them where the page had since moved on. The fullest of the last
    few readings is the screen as it stands.
    """
    frames = [f for f in dev.box_frames(log) if f[1]]
    if not frames:
        return {}
    recent = frames[-6:]
    best = max(recent, key=lambda f: len(f[1]))
    return {k: v for k, v in best[1].items() if v["sampled"]}


def settled(dev, tries=10):
    """Wait until two readings running describe the same screen, and answer with it.

    A reading taken while the page is still coming to rest describes where the words were,
    not where they are, and the capture that follows it is of a screen that has moved on: the
    rectangles then hold the app's own text and nothing of ours, which reads exactly like a
    transcription that was never painted.
    """
    last = None
    for _ in range(tries):
        now = drawn(dev)
        if last is not None and now and now.keys() == last.keys() and all(
            now[k]["rect"] == last[k]["rect"] for k in now
        ):
            return now
        last = now
        time.sleep(1)
    return last or {}


def judge(r, dev, label, log=None):
    boxes = drawn(dev, log)
    r.check(len(boxes) > 0, f"{label}: nothing was drawn with colours read off the screen")
    faint = [
        (b["word"], contrast(b["bg"], b["ink"]))
        for b in boxes.values()
        if contrast(b["bg"], b["ink"]) < LEGIBLE
    ]
    worst = min((contrast(b["bg"], b["ink"]) for b in boxes.values()), default=0)
    r.check(
        not faint,
        f"{label}: {len(faint)} of {len(boxes)} transcriptions are painted in a shade of "
        f"their own background, worst {faint[0][0] if faint else ''} at "
        f"{min((c for _, c in faint), default=0)} of {LEGIBLE}",
    )
    if not faint and boxes:
        print(f"    {label}: {len(boxes)} legible, closest pair {worst}")
    return boxes


def paint_of(image, dev, rect):
    """The crop of a capture that holds one transcription."""
    sx, sy = image.width / dev.width, image.height / dev.height
    left, top, right, bottom = rect
    return image.crop((int(left * sx), int(top * sy), int(right * sx), int(bottom * sy)))


def on_the_screen(r, dev, boxes, into, label):
    """The same question asked of the framebuffer, which knows nothing of what we reported.

    The rectangle alone is not evidence: it is the app's own word's rectangle, so a crop of it
    holds legible text whether or not anything of ours was ever painted there. This suite
    passed a whole run against a screen that had no transcriptions on it at all, because the
    words it was measuring were the app's own. So the same screen is photographed again with
    the service switched off, and a transcription counts as painted only where the two
    captures differ.
    """
    with_us = dev.screenshot(os.path.join(into, label.replace(" ", "-")))
    shell("settings", "put", "secure", "enabled_accessibility_services", "none")
    time.sleep(3.5)
    without = dev.screenshot(os.path.join(into, label.replace(" ", "-") + "-bare"))
    dev.enable_service()
    time.sleep(2)
    if not with_us or not without:
        r.check(False, f"{label}: the screen could not be photographed")
        return
    ours = Image.open(with_us).convert("RGB")
    theirs = Image.open(without).convert("RGB")
    faint = []
    unpainted = []
    looked = 0
    for box in boxes.values():
        left, top, right, bottom = box["rect"]
        # A line half off the top or bottom of the screen is drawn as much of itself as fits,
        # and what fits can be a few pixels of the tops of its letters. There is nothing to
        # read in that, and nothing a reader would call a transcription either.
        if top < EDGE or bottom > dev.height - EDGE or bottom - top < MIN_LINE:
            continue
        mine = paint_of(ours, dev, box["rect"])
        bare = paint_of(theirs, dev, box["rect"])
        if mine.width < 8 or mine.height < 6 or mine.size != bare.size:
            continue
        here = list(mine.getdata())
        there = list(bare.getdata())
        changed = sum(1 for a, b in zip(here, there) if sum(abs(x - y) for x, y in zip(a, b)) > 40)
        # A transcription is a different word in the same place, so a good share of the
        # rectangle has to have changed. A handful of pixels is the page having moved a hair.
        if changed < len(here) * 0.02:
            unpainted.append(box["word"])
            continue
        looked += 1
        surface = max(set(here), key=here.count)
        light = sum(surface) > 384
        ordered = sorted(here, key=sum, reverse=not light)
        take = max(4, int(len(ordered) * INK_SHARE))
        ink = tuple(sum(c[i] for c in ordered[:take]) // take for i in range(3))
        gap = sum(abs(ink[i] - surface[i]) for i in range(3))
        if gap < LEGIBLE_ON_SCREEN:
            faint.append((box["word"], gap))
    r.check(
        not unpainted,
        f"{label}: {len(unpainted)} transcriptions the service says it drew changed nothing "
        f"on the screen, among them {', '.join(unpainted[:4])}",
    )
    r.check(looked > 0, f"{label}: no transcription was big enough to read on the screen")
    r.check(
        not faint,
        f"{label}: on the screen itself {len(faint)} of {looked} transcriptions have no "
        f"contrast, worst {faint[0][0] if faint else ''} at "
        f"{min((g for _, g in faint), default=0)} of {LEGIBLE_ON_SCREEN}",
    )
    if not faint and looked:
        print(f"    {label}: {looked} painted over the app and read off the screen, all with contrast")


def a_page(r, dev, into, mode, label, **extras):
    # Emptied before the page comes up, not after it settles: the service reports what it
    # drew when it draws it, and a screen that has stopped changing says nothing at all.
    dev.clear_log()
    dev.surface(mode=mode, enable=1, density=3, allApps=1, **extras)
    time.sleep(6)
    boxes = judge(r, dev, label)
    if boxes:
        on_the_screen(r, dev, boxes, into, label)


def in_the_dark(r, dev, into):
    shell("cmd", "uimode", "night", "yes")
    time.sleep(3)
    try:
        # An English page, deliberately: a page of German is left alone on purpose, since
        # the dictionary is English, so it would prove nothing about how words are painted.
        a_page(r, dev, into, "unique", "a dark page")
    finally:
        shell("cmd", "uimode", "night", "no")
        time.sleep(2)


def while_it_moves(r, dev, into):
    """The case that made a reader complain: colours read while the page was moving.

    Judging the screen once it has stopped says nothing about this. A page settles in a few
    hundred milliseconds and its colours are read again the moment it does, so a suite that
    waits for stillness only ever sees the corrected answer, while the reader spends the whole
    swipe looking at the wrong one. So every reading the service made during the movement is
    judged, not the last one: a word painted in a shade of its own background for a second and
    a half is the complaint, whatever it looks like afterwards.
    """
    dev.surface(mode="unique", enable=1, density=3, allApps=1)
    time.sleep(4)
    dev.clear_log()
    started = time.time()
    # Back to back, so the page is never still: a gap between swipes is a chance to settle
    # and read the colours again, which is the thing being kept out of the sample.
    for _ in range(6):
        shell("input", "swipe", str(dev.width // 2), str(int(dev.height * 0.8)),
              str(dev.width // 2), str(int(dev.height * 0.2)), "500")
    moved_for = time.time() - started
    faint = []
    seen = 0
    frames = [f for f in dev.box_frames() if f[1]]
    # The service stamps its readings on the device clock, so they are compared with each
    # other rather than with ours: everything from the first reading of the movement on.
    for _, boxes in frames:
        for box in boxes.values():
            if not box["sampled"]:
                continue
            seen += 1
            gap = contrast(box["bg"], box["ink"])
            if gap < LEGIBLE:
                faint.append((box["word"], gap))
    r.check(seen > 0, "while it moves: the service drew nothing during the movement")
    r.check(
        not faint,
        f"while it moves: {len(faint)} of {seen} transcriptions drawn during "
        f"{moved_for:.1f}s of scrolling are painted in a shade of their own background, "
        f"worst {faint[0][0] if faint else ''} at {min((g for _, g in faint), default=0)} "
        f"of {LEGIBLE}",
    )
    if not faint and seen:
        print(f"    while it moves: {seen} drawn during {moved_for:.1f}s of scrolling, all legible")
    settled(dev)
    boxes = judge(r, dev, "once it stops")
    if boxes:
        on_the_screen(r, dev, boxes, into, "once it stops")


def ours_only(log):
    """The log with any reading of another screen dropped.

    An app's own readings outlive the app being in front, and the settings screen is arrived
    at through ours: without this the newest reading in the log belongs to the page that was
    on screen a moment ago, and the rectangles are judged against a screen that is gone.
    """
    return "\n".join(
        line for line in log.splitlines()
        if "BOXES " not in line or "com.android.settings" in line
    )


def a_real_app(r, dev, into):
    """An app nobody wrote for this, on a screen with several surfaces on it."""
    # Through our own page first, which is what turns the marks on and what tells the service
    # to transcribe every app.
    dev.surface(mode="unique", enable=1, density=3, allApps=1)
    time.sleep(3)
    dev.clear_log()
    for _ in range(6):
        shell("am", "start", "-a", "android.settings.SETTINGS")
        time.sleep(2.5)
        if "settings" in dev.top_activity().lower():
            break
    time.sleep(5)
    boxes = judge(r, dev, "the settings app", log=ours_only(dev.log()))
    if boxes:
        on_the_screen(r, dev, boxes, into, "the settings app")


# What the screen is set to for the length of this suite.
#
# The capture that contains our windows comes off the emulator console at the panel's own
# size, 320x640, while the device is normally driven at 1080x1920. A letter three pixels tall
# survives none of that reduction: every crop of a word came back one flat colour, so the
# question "is this word legible" was being asked of a smudge. Laid out nearer the size it is
# photographed at, a letter is a dozen pixels and the ink in it is a colour that can be read.
# Put back at the end, whatever happens.
# The shape matters as much as the size: the panel is 320x640, and a screen set to any other
# ratio is letterboxed inside the capture, so every rectangle read out of it lands beside the
# word it belongs to rather than on it.
READABLE_SIZE = (480, 960)
READABLE_DENSITY = 240


def as_it_is_photographed(dev):
    """Returns what the screen was set to, to be put back afterwards.

    Put back exactly, not reset: the device these suites run on is driven at a size of its
    own, and resetting it drops to the panel's 320x640, where a swipe of five hundred pixels
    runs off the bottom of the screen and every other suite fails on geometry it never chose.
    """
    was = (dev.width, dev.height)
    density = re.search(r"Override density: (\d+)", shell("wm", "density"))
    shell("wm", "size", f"{READABLE_SIZE[0]}x{READABLE_SIZE[1]}")
    shell("wm", "density", str(READABLE_DENSITY))
    time.sleep(2)
    dev.width, dev.height = READABLE_SIZE
    return was, (density.group(1) if density else None)


def put_the_screen_back(was):
    size, density = was
    shell("wm", "size", f"{size[0]}x{size[1]}")
    if density:
        shell("wm", "density", density)
    else:
        shell("wm", "density", "reset")
    time.sleep(2)


def main():
    into = os.environ.get("PHONETIX_SHOT_DIR", "/tmp/phonetix-ink")
    os.makedirs(into, exist_ok=True)
    dev = Device()
    if not dev.enable_service():
        print("the service never came up")
        return 1
    was = as_it_is_photographed(dev)
    # One page before anything is judged. A service that has just been switched on spends its
    # first seconds reading the screen and asking for colours, and a suite that measures then
    # is measuring the warm-up.
    dev.surface(mode="unique", enable=1, density=3, allApps=1)
    time.sleep(6)
    r = Results()
    runs = (
        lambda: a_page(r, dev, into, "unique", "a plain page"),
        lambda: a_page(r, dev, into, "gradient", "a page with a gradient behind it"),
        lambda: in_the_dark(r, dev, into),
        lambda: while_it_moves(r, dev, into),
        lambda: a_real_app(r, dev, into),
    )
    try:
        for run in runs:
            run()
    finally:
        put_the_screen_back(was)
    total = r.passed + len(r.failed)
    print(f"{r.passed}/{total} checks passed")
    return 1 if r.failed else 0


if __name__ == "__main__":
    sys.exit(main())
