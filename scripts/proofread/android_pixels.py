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

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/android_pixels.py
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import SERIAL, Device, shell

PAGES = [("a list", "recycler"), ("a recycling list", "list")]
SHOTS = int(os.environ.get("PHONETIX_SHOTS", "6"))
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
    return right, wrong, unplaced


def main():
    dev = Device()
    if not dev.enable_service():
        print("the service will not start")
        return 1
    total = [0, 0, 0]
    for label, mode in PAGES:
        right, wrong, unplaced, blank = 0, 0, 0, 0
        for _ in range(SHOTS):
            shell("am", "force-stop", "io.github.nanbiz.phonetix")
            time.sleep(1.5)
            dev.surface(mode=mode, enable=1, density=3, allApps=1, markLines=1, rows=60)
            time.sleep(2)
            dev.enable_service()
            time.sleep(5)
            dev.surface(mode=mode, enable=1, density=3, allApps=1, markLines=1, rows=60,
                        motion="linear", distance=MOVE_PX, duration=MOVE_MS, strokes=1, seed=1)
            # Part way into the movement, which is where the words are hardest to keep on.
            time.sleep(MOVE_MS / 2000)
            got = judge(screen())
            if got[0] + got[1] == 0:
                blank += 1
            right += got[0]
            wrong += got[1]
            unplaced += got[2]
        shown = right + wrong
        share = f"{100 * right / shown:.0f}%" if shown else "-"
        print(f"  {label:20} {shown:4} transcriptions on the screen mid-drag, "
              f"{share:>4} of them over their own line"
              f"{f', {blank} looks had none at all' if blank else ''}"
              f"{f', {unplaced} on no line the page painted' if unplaced else ''}")
        total[0] += right
        total[1] += wrong
        total[2] += unplaced
    shown = total[0] + total[1]
    if shown:
        print(f"  {'every page':20} {shown:4} transcriptions, "
              f"{100 * total[0] / shown:.0f}% over their own line")
    return 0


if __name__ == "__main__":
    sys.exit(main())
