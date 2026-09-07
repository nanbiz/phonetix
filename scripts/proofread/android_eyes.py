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
import threading
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
# the screen itself. A line of this page is about 55px tall, so this is well inside one.
ON_THE_LINE = 14
# And what is allowed while the page is actually moving under a finger.
#
# What a reader needs, not what the code currently manages. This was set to the latter once -
# forty-two pixels of the capture, which is nearly three lines of the page - on the reasoning
# that a bar measured from behaviour is a ratchet against getting worse. It is not: it is a
# test that passes while a transcription sits three lines from its word, which is what the
# reader of this was complaining about the whole time it was green.
#
# The same bar as standing still, and for a reason that took a photograph to see. Half a line
# was allowed here once, on the reasoning that a movement cannot be as exact as stillness. But
# every transcription is judged against the nearest line of the page, and the lines of a page
# are about a line apart: a transcription sitting exactly between two of them is half a line
# from each, which scored as well as one sitting on its word. Photographed mid-drag, the
# screen showed transcriptions floating in the gaps between the lines while this suite called
# them placed. Anything looser than the still bar cannot tell the two apart.
WHILE_MOVING = ON_THE_LINE
# How many photographs to take through one movement, and how long the movement lasts. The
# camera manages about twenty frames a second, so these are chosen to fill the swipe.
FRAMES = 16
SWIPE_MS = 1400
# How many swipes are photographed and judged together.
SWIPES = 3
# The font size a reader who needs one sets. Android goes to 2.0; this is the ordinary end of
# "larger", and the placement holds from 0.85 through 1.5.
BIGGER = 1.3
# How many of a still page's transcriptions have to survive the page being moved. Not all of
# them: a line leaving the top of the screen takes its own with it, and a line arriving at the
# bottom has not been read yet. Most of them, though - a reader scrolling a page of text
# watched two thirds of them disappear for as long as the finger was down.
KEPT_MOVING = 0.75
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


def shots(dev, into, count, gap=0.0, keep=0):
    """Photographs of the real screen, as fast as the emulator will give them.

    @param keep how many were taken before, so a second round does not overwrite the first.
    """
    if os.path.isdir(into) and keep == 0:
        shutil.rmtree(into)
    os.makedirs(into, exist_ok=True)
    taken = []
    for i in range(keep, keep + count):
        one = os.path.join(into, f"f{i:02d}")
        dev.screenshot(one)
        names = [n for n in os.listdir(one) if n.endswith(".png")]
        if names:
            taken.append((time.time(), os.path.join(one, names[0])))
        if gap:
            time.sleep(gap)
    return taken


def judge(r, frames, label, dev_height, bar=ON_THE_LINE, turn=False, against=None):
    """Every transcription in every frame has to be level with a line of the page.

    @param against how many transcriptions a frame of this page carries when it is standing
        still. A movement that keeps them on their words but keeps only a third of them is
        not a movement anybody would call working, and every check here but this one is
        blind to it: they all judge the transcriptions that are on the screen, so the fewer
        survive the better the page scores.
    """
    worst = 0
    worst_at = ""
    seen = 0
    empty = 0
    adrift = 0
    offsets = []
    allowed = ON_THE_LINE
    for at, path in frames:
        image = Image.open(path).convert("RGB")
        if turn:
            image = image.transpose(Image.ROTATE_90)
        # The capture is scaled down from the screen, so what counts as being on a line is
        # scaled with it rather than measured in whatever pixels the emulator felt like.
        allowed = max(3, round(bar * image.size[1] / dev_height))
        lines = rows_of(image, is_line)
        chips = rows_of(image, is_chip)
        if not chips:
            empty += 1
            continue
        if not lines:
            continue
        # Only where the page has told us something. A row cut off by the bottom of the
        # screen shows its text but not the bar at its left, so a transcription down there
        # has nothing to be held against - and was being held against the last row that did
        # have one, two lines above it.
        first, last = min(lines), max(lines)
        chips = [y for y in chips if first - allowed <= y <= last + allowed]
        if not chips:
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
        return 0, 0
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
    per_frame = seen / max(1, len([f for f in frames if f]))
    if against:
        r.check(
            per_frame >= against * KEPT_MOVING,
            f"{label}: the page keeps its transcriptions",
            f"{per_frame:.1f} a frame against {against:.1f} standing still - "
            f"{100 * per_frame / against:.0f}% of them, of {100 * KEPT_MOVING:.0f}% wanted",
        )
    print(f"  {label}: {len(frames)} frames, {seen} seen ({per_frame:.1f} a frame), "
          f"median {middle}, nine in ten within {nearly_worst}, worst {worst} "
          f"(allowed {allowed}, frame pixels of {ON_THE_LINE} on the screen)")
    return worst, per_frame


def still(r, dev, into):
    # Anything a previous suite left open is not part of this page.
    shell("input", "keyevent", "4")
    time.sleep(0.6)
    # Waited for there to be something to photograph. This is the first thing the suite does,
    # so it is the one that pays for the dictionary being loaded, the colours being read and
    # whatever the last suite left on screen.
    for _ in range(6):
        dev.surface(mode="unique", enable=1, density=3, allApps=1, marks=1, scrollTo=300)
        time.sleep(4.0)
        if dev.boxes():
            break
    return judge(r, shots(dev, into, 3, gap=0.2), "standing still", dev.height)[1]


def while_scrolling(r, dev, into, still_count):
    """Photographed through a real finger swipe, which is the only way a reader scrolls.

    The swipe is run on a thread of its own. `input swipe` does not return until the gesture
    it is performing has finished, so firing it and then reaching for the camera photographs
    the page after it has stopped - which is a different question, and one that was already
    being answered by the two checks either side of this one.
    """
    width, height = dev.width, dev.height
    # Three swipes, and every frame of them judged together. One swipe is not a measurement:
    # the same swipe measured five times running gave medians of 6, 11, 12, 8 and 7 pixels,
    # because how much of a swipe the emulator manages to render varies. Three hundred frames
    # do not move about like that.
    frames = []
    before = []
    for _ in range(SWIPES):
        dev.surface(mode="unique", enable=1, density=3, allApps=1, marks=1, scrollTo=900)
        time.sleep(3.5)
        # What this page carries standing still, at the position the swipe starts from. The
        # count taken at the top of the suite is of a different screen, and comparing against
        # it measures how many lines that screen happened to have rather than how many this
        # movement kept.
        # In a folder of its own: a round of photographs empties the one it is given.
        before += shots(dev, into + "-before", 1, keep=len(before))
        swipe = threading.Thread(
            target=shell,
            args=(
                "input", "swipe",
                str(width // 2), str(int(height * 0.8)),
                str(width // 2), str(int(height * 0.2)),
                str(SWIPE_MS),
            ),
            daemon=True,
        )
        swipe.start()
        # A moment for the gesture to start moving the page, then photograph it while it does.
        time.sleep(0.25)
        frames += shots(dev, into, FRAMES, keep=len(frames))
        swipe.join(timeout=5)
    steady = judge(r, before, "standing still where the swipe starts", dev.height)[1]
    judge(r, frames, "through a finger swipe", dev.height, bar=WHILE_MOVING, against=steady)


def turned_sideways(r, dev, into):
    """The screen rotated, which moves every word and every window on it.

    A transcription is a window placed in screen coordinates, so a rotation moves the words
    out from under all of them at once. The capture comes back the way the screen is wired
    rather than the way it is being held, so it is turned upright here before it is read.
    """
    shell("settings", "put", "system", "accelerometer_rotation", "0")
    shell("settings", "put", "system", "user_rotation", "1")
    try:
        time.sleep(3)
        dev.surface(mode="unique", enable=1, density=3, allApps=1, marks=1, scrollTo=200)
        time.sleep(5)
        judge(r, shots(dev, into, 3, gap=0.2), "turned sideways", dev.width, turn=True)
    finally:
        shell("settings", "put", "system", "user_rotation", "0")
        time.sleep(3)


def through_a_fling(r, dev, into, still_count):
    """Thrown rather than dragged, which is how a page is usually moved.

    A drag is a hand keeping pace with the eye; a fling is the page carrying on by itself,
    fastest at the moment the finger leaves and slowing after. It is the movement this was
    first reported broken on, and the only way to see it is to photograph it.
    """
    frames = []
    for _ in range(SWIPES):
        dev.surface(mode="unique", enable=1, density=3, allApps=1, marks=1, scrollTo=1200)
        time.sleep(4)
        throw = threading.Thread(
            target=shell,
            args=(
                "input", "swipe",
                str(dev.width // 2), str(int(dev.height * 0.78)),
                str(dev.width // 2), str(int(dev.height * 0.26)),
                "120",
            ),
            daemon=True,
        )
        throw.start()
        time.sleep(0.15)
        frames += shots(dev, into, 10, keep=len(frames))
        throw.join(timeout=5)
    judge(r, frames, "through a fling", dev.height, bar=WHILE_MOVING, against=still_count)  # noqa


def at_a_larger_font(r, dev, into):
    """A reader who has made the text bigger, which is who an overlay like this is for.

    Everything else here runs at the size the device came with. A larger font changes the
    height of every line and the width of every word, and the transcriptions are placed from
    where the app says its characters are - so if that ever stopped agreeing with what is
    drawn, this is where it would show.
    """
    shell("settings", "put", "system", "font_scale", str(BIGGER))
    try:
        time.sleep(3)
        dev.surface(mode="unique", enable=1, density=3, allApps=1, marks=1, scrollTo=300)
        time.sleep(5)
        judge(r, shots(dev, into, 3, gap=0.2), f"at {BIGGER} times the font size", dev.height)
    finally:
        shell("settings", "put", "system", "font_scale", "1.0")
        time.sleep(2)


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
    # Upright to begin with, whatever the last thing to touch this device left behind: the
    # capture comes back the way the screen is wired, so a device left sideways photographs
    # every page sideways and none of the marks line up.
    shell("settings", "put", "system", "accelerometer_rotation", "0")
    shell("settings", "put", "system", "user_rotation", "0")
    time.sleep(2)
    r = Results()
    print("\nlooking at the screen")
    still_count = still(r, dev, into)
    while_scrolling(r, dev, into, still_count)
    after_it_stops(r, dev, into)
    through_a_fling(r, dev, into, still_count)
    at_a_larger_font(r, dev, into)
    turned_sideways(r, dev, into)
    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - what is on the screen sits on the words, moving and still")


if __name__ == "__main__":
    main()
