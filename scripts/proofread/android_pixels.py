"""Is the transcription over its own word? Asked of the screen, not of the log.

Every other check here reads what the service says it did. That is what the service believes,
so when the belief is wrong the check agrees with the mistake: measured all day, suites
reported nothing wrong while a photograph of the same moment showed two transcriptions sitting
on the wrong paragraph, and reported thousands of transcriptions judged on a screen that was
carrying none.

This asks the screen. The page paints each line's number into its own background, and the
overlay paints, on each word it draws, the number of the line it believes that word came from.
A photograph then holds both, and the check is a comparison of two colours: the one under the
word and the one on it. Nothing is read from the log, no text is recognised, and a wrong
belief cannot hide, because the page's colour does not come from the overlay.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run scripts/proofread/android_pixels.py
"""
# The image library is declared here rather than asked of the caller: a check that needs
# it fails halfway through otherwise, after the emulator has already been driven.
# /// script
# dependencies = ["pillow"]
# ///

import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import SERIAL, Device, shell

PAGES = [("a list", "recycler"), ("a recycling list", "list")]
# How many drags each page is put through. Each is looked at three times.
DRAGS = int(os.environ.get("PHONETIX_SHOTS", "6"))
# The same drag the other suites use.
MOVE_PX = 900
MOVE_MS = 1300
# How the line number is written into a colour, which has to match Marks in the app.
STEPS = 12
APART = 18
BASE = 24
# Full green marks the overlay's own patch; no page colour has any.
BELIEVED_GREEN = 220
PAGE_GREEN = 24
# Where to keep a look that went wrong, if anywhere.
KEEP = os.environ.get("PHONETIX_KEEP")


def decode(red, blue):
    """The line number a colour carries, or nothing when it carries none."""
    low, high = red - BASE, blue - BASE
    if low % APART or high % APART:
        return None
    low, high = low // APART, high // APART
    if not (0 <= low < STEPS and 0 <= high < STEPS):
        return None
    return high * STEPS + low


def screen():
    """The screen, as pixels."""
    raw = subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"],
                         capture_output=True, timeout=60).stdout
    from PIL import Image
    import io
    return Image.open(io.BytesIO(raw)).convert("RGB")


def judge(image):
    """Every marked word on this screen, and whether it is over the line it claims."""
    width, height = image.size
    pixels = image.load()
    # The page's colour at the left edge, which no transcription is ever drawn over, so it is
    # the line actually there at that height.
    truth = {}
    for y in range(height):
        r, g, b = pixels[2, y]
        if g == PAGE_GREEN:
            line = decode(r, b)
            if line is not None:
                truth[y] = line
    right, wrong, unplaced = 0, 0, 0
    # By how many lines, and which way. A whole screen out by exactly one line is a list that
    # has recycled its rows under the overlay; a spread of distances is the words being carried
    # to the wrong place.
    off = []
    seen = set()
    for y in range(0, height, 2):
        for x in range(0, width, 2):
            r, g, b = pixels[x, y]
            if g != BELIEVED_GREEN:
                continue
            believed = decode(r, b)
            if believed is None:
                continue
            # One patch is several pixels; the first corner found stands for it.
            if any(abs(x - px) < 24 and abs(y - py) < 24 for px, py in seen):
                continue
            seen.add((x, y))
            # What the page says is at this height. The patch sits at the top left of the
            # word, so the line under it is read a little lower, inside the word's own row.
            here = truth.get(y + 12) or truth.get(y + 6) or truth.get(y)
            if here is None:
                unplaced += 1
            elif here == believed:
                right += 1
            else:
                wrong += 1
                off.append(believed - here)
    return right, wrong, unplaced, off


def main():
    # Knobs passed through to the page, which sets them on the service, so a constant can be
    # swept against the one measure here that does not read the service's own account.
    knobs = {}
    for arg in sys.argv[1:]:
        if "=" in arg:
            key, value = arg.split("=", 1)
            knobs[key] = int(value)
    dev = Device()
    if not dev.enable_service():
        print("the service will not start")
        return 1
    total = [0, 0, 0, 0, 0]
    for label, mode in PAGES:
        right, wrong, unplaced, blank = 0, 0, 0, 0
        # Per look, because the total hides the shape: a page is not eight per cent wrong all
        # the time, it is right nearly always and then wholly wrong for one moment of one drag.
        looks = []
        misses = []
        for _ in range(DRAGS):
            shell("am", "force-stop", "io.github.nanbiz.phonetix")
            time.sleep(1.5)
            dev.surface(mode=mode, enable=1, density=3, allApps=1, markLines=1, rows=60,
                        **knobs)
            time.sleep(2)
            dev.enable_service()
            time.sleep(5)
            dev.surface(mode=mode, enable=1, density=3, allApps=1, markLines=1, rows=60,
                        motion="linear", distance=MOVE_PX, duration=MOVE_MS, strokes=1,
                        seed=1, **knobs)
            # Several moments of the same drag rather than one. A screenshot catches one
            # instant, and which instant it is moves the answer by eight points between runs of
            # the same build; the moments through a drag are what is being asked about anyway.
            began = time.time()
            for share_of_it in (0.25, 0.5, 0.75):
                wait = MOVE_MS / 1000 * share_of_it - (time.time() - began)
                if wait > 0:
                    time.sleep(wait)
                picture = screen()
                got = judge(picture)
                misses += got[3]
                if got[0] + got[1] == 0:
                    blank += 1
                right += got[0]
                wrong += got[1]
                unplaced += got[2]
                if got[0] + got[1] > 0:
                    share_right = got[0] / (got[0] + got[1])
                    looks.append(share_right)
                    # A look where nearly everything is wrong is not a worse average, it is a
                    # moment a reader would see as broken, and it is the only thing worth
                    # looking at. Kept so it can be.
                    if share_right < 0.5 and KEEP:
                        shot = f"{KEEP}/wrong-{mode}-{len(looks)}.png"
                        picture.save(shot)
                        print(f"      kept a look that was {100 * share_right:.0f}% right "
                              f"at {shot}")
        shown = right + wrong
        share = f"{100 * right / shown:.0f}%" if shown else "-"
        # The count that matters is of moments a reader would call broken, not the pooled
        # share: a page is right nearly always and then wholly wrong for one moment, and how
        # many chips happened to be on the screen for each look moves the pooled share about
        # far more than the fault does.
        bad = sum(1 for l in looks if l < 0.5)
        print(f"  {label:20} {shown:4} transcriptions on the screen mid-drag, "
              f"{share:>4} of them over their own line"
              f"{f', {blank} of {3 * DRAGS} looks had none at all' if blank else ''}"
              f"{f', {unplaced} on no line the page painted' if unplaced else ''}"
              f"{f', {bad} of {len(looks)} looks mostly wrong' if bad else ''}")
        if misses:
            from collections import Counter
            common = Counter(misses).most_common(4)
            print("      how far out, in lines: "
                  + ", ".join(f"{d:+d} ({n})" for d, n in common))
        total[0] += right
        total[1] += wrong
        total[2] += unplaced
        total[3] += bad
        total[4] += len(looks)
    shown = total[0] + total[1]
    if shown:
        print(f"  {'every page':20} {shown:4} transcriptions, "
              f"{100 * total[0] / shown:.0f}% over their own line, "
              f"{total[3]} of {total[4]} looks mostly wrong")
    return 0


if __name__ == "__main__":
    sys.exit(main())
