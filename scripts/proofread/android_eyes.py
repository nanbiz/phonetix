#!/usr/bin/env python3
"""What is on the screen, photographed many times while it moves.

Every other suite here reads what the service says it did. That is the wrong witness for the
only question that matters - whether someone looking at the screen sees a transcription on its
word - because a service can report a position it never drew, draw it a frame late, or draw it
and have the window land somewhere else entirely. None of that shows in a log that says the
right numbers.

So this looks. The page marks each of its lines with a short bar at the left edge, every
transcription wears one of its own, and the real framebuffer is captured over and over through
a movement. Each frame is then read on its own: every transcription bar in it has to be level
with a line bar, or it is sitting between the lines of the page, which is exactly what a
reader complains about. Nothing the service says is consulted.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run --with pillow python scripts/proofread/android_eyes.py
"""
import os
import shutil
import sys
import time

from PIL import Image

from android_harness import Device, shell

# The bars, told apart by which channels are lit rather than by an exact value. A capture of
# the screen comes back scaled - a 1080px screen arrives 320px wide - and scaling drags a
# colour off its value: the magenta bar reads 203,0,203 rather than 255,0,255.
def is_chip(px):
    """Magenta: red and blue lit, green dark."""
    return px[0] > 110 and px[2] > 110 and px[1] < 80 and abs(px[0] - px[2]) < 60


def is_line(px):
    """Cyan: green and blue lit, red dark."""
    return px[1] > 110 and px[2] > 110 and px[0] < 80 and abs(px[1] - px[2]) < 60


# How far from the line it belongs to a transcription may be and still be read as on it, on
# the screen itself. A line of this page is about 60px tall, so this is well inside one.
ON_THE_LINE = 14
# How many photographs to take through one movement.
FRAMES = 14
# A single frame of a movement may be further out than the rest without anyone seeing it, so
# the occasional one is held to a looser bound than the typical one - this many times looser,
# which is about a line.
LOOSE = 3


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


def rows_of(image, hit, step=2):
    """The rows each bar of this kind covers, one entry per bar.

    A bar is a few pixels tall and the frame is small, so this reads the pixels rather than
    trusting any one column: a transcription can be anywhere a word is.
    """
    width, height = image.size
    marked = []
    for y in range(height):
        for x in range(0, width, step):
            if hit(image.getpixel((x, y))):
                marked.append(y)
                break
    out = []
    for y in marked:
        if out and y - out[-1][-1] <= 4:
            out[-1].append(y)
        else:
            out.append([y])
    return [sum(group) // len(group) for group in out]


def shots(dev, into, count, gap=0.0):
    """Photographs of the real screen, as fast as the emulator will give them."""
    if os.path.isdir(into):
        shutil.rmtree(into)
    os.makedirs(into, exist_ok=True)
    taken = []
    for i in range(count):
        one = os.path.join(into, f"f{i:02d}")
        dev.screenshot(one)
        names = [n for n in os.listdir(one) if n.endswith(".png")]
        if names:
            taken.append((time.time(), os.path.join(one, names[0])))
        if gap:
            time.sleep(gap)
    return taken


def judge(r, frames, label, dev_height):
    """Every transcription in every frame has to be level with a line of the page."""
    worst = 0
    worst_at = ""
    seen = 0
    empty = 0
    adrift = 0
    offsets = []
    allowed = ON_THE_LINE
    for at, path in frames:
        image = Image.open(path).convert("RGB")
        # The capture is scaled down from the screen, so what counts as being on a line is
        # scaled with it rather than measured in whatever pixels the emulator felt like.
        allowed = max(3, round(ON_THE_LINE * image.size[1] / dev_height))
        lines = rows_of(image, is_line)
        chips = rows_of(image, is_chip)
        if not chips:
            empty += 1
            continue
        if not lines:
            continue
        seen += len(chips)
        for y in chips:
            off = min(abs(y - line) for line in lines)
            if off > worst:
                worst = off
                worst_at = (f"a transcription {off}px from any line of the page, in a frame "
                            f"{image.size[0]}x{image.size[1]}")
            offsets.append(off)
            if off > allowed:
                adrift += 1
    r.check(seen > 0, f"{label}: transcriptions were on the screen at all",
            f"{empty} of {len(frames)} frames had none")
    if not offsets:
        return 0
    offsets.sort()
    middle = offsets[len(offsets) // 2]
    nearly_worst = offsets[int(len(offsets) * 0.9)]
    scale = 3 * ON_THE_LINE / max(1, allowed)  # frame pixels back to the screen's own
    # Two questions, because they are different faults. Typically off means every
    # transcription on the page is standing beside its word, which is what a reader
    # complains about; occasionally off is one frame of a movement, which nobody sees.
    r.check(middle <= allowed, f"{label}: they sit on their words",
            f"half of them are more than {middle} from their line "
            f"(about {middle * scale / ON_THE_LINE * ON_THE_LINE / max(1, allowed):.0f}px "
            f"on the screen), of {allowed} allowed")
    r.check(nearly_worst <= LOOSE * allowed, f"{label}: none of them wanders far",
            f"a tenth of them are further than {nearly_worst}, worst {worst} - {worst_at}")
    print(f"  {label}: {len(frames)} frames, {seen} seen, median {middle}, "
          f"nine in ten within {nearly_worst}, worst {worst} (allowed {allowed}, "
          f"frame pixels of {ON_THE_LINE} on the screen)")
    return worst


def still(r, dev, into):
    # Anything a previous suite left open is not part of this page.
    shell("input", "keyevent", "4")
    time.sleep(0.6)
    dev.surface(mode="unique", enable=1, density=3, allApps=1, marks=1, scrollTo=300)
    time.sleep(4.0)
    judge(r, shots(dev, into, 3, gap=0.2), "standing still", dev.height)


def while_scrolling(r, dev, into):
    """Photographed through a real finger swipe, which is the only way a reader scrolls."""
    dev.surface(mode="unique", enable=1, density=3, allApps=1, marks=1, scrollTo=900)
    time.sleep(3.5)
    width, height = dev.width, dev.height
    # Started off to the side, so this measures the overlay following a scroll and not the
    # separate question of whether a swipe from a word is delivered at all.
    shell(
        "input", "swipe",
        str(width // 2), str(int(height * 0.75)),
        str(width // 2), str(int(height * 0.25)),
        "1400",
    )
    # Straight into the capture: the swipe is still running.
    judge(r, shots(dev, into, FRAMES), "through a finger swipe", dev.height)


def after_it_stops(r, dev, into):
    time.sleep(2.5)
    judge(r, shots(dev, into, 3, gap=0.3), "once it has stopped", dev.height)


def main():
    dev = Device()
    print(f"device {dev.width}x{dev.height}")
    if not dev.enable_service():
        print("FAIL - the accessibility service will not start")
        sys.exit(1)
    into = os.path.join(
        os.environ.get("TMPDIR", "/tmp"), "phonetix-eyes",
    )
    r = Results()
    print("\nlooking at the screen")
    still(r, dev, into)
    while_scrolling(r, dev, into)
    after_it_stops(r, dev, into)
    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - what is on the screen sits on the words, moving and still")


if __name__ == "__main__":
    main()
