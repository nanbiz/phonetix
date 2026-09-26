#!/usr/bin/env python3
"""A page has to be transcribed whole, and at once.

Two things a reader notices before anything else: whether the words on the screen are all
answered, and how long they wait. Both were wrong and neither was measured.

The overlay used to put every word on a window of its own. A device will not hand an app an
unbounded number of windows, so it stopped at ninety-six: a page with more words than that was
transcribed down to there and left bare below - ninety-six of a hundred and forty-four on a
page somebody was reading. And a word was withheld until its own colours had been read off a
photograph of the screen, which is retried whenever the screen moves, so the whole page waited
on the camera: the plan was ready at 1.8s and nothing was drawn until 2.9s.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_quick.py
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, SERIAL

# How long a page may take to be drawn once the service has read it.
#
# The read itself is tens of milliseconds. The rest is the colours: a word is held back until
# the line it covers has been photographed, because a transcription in the wrong colour is
# worse than one a moment late, and the photograph cannot be taken while our own paint is on
# the screen. Drawing first and repainting was tried and is not worth it - the photograph then
# has our own fallback in it and the line comes back wearing it. So what this catches is the
# drawing waiting on anything beyond one capture.
DRAWN_WITHIN_S = 1.6


def timed(kind):
    """Every line the service wrote, with the second it was written."""
    out = subprocess.run(
        ["adb", "-s", SERIAL, "logcat", "-d", "-v", "epoch", "-s", "Phonetix:D"],
        capture_output=True, text=True, timeout=180,
    ).stdout
    found = []
    for line in out.splitlines():
        if kind in line:
            found.append((float(line.split()[0]), line))
    return found


def main():
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    failures = []

    # The essay is laid out once and stands still, so the time between its being read and its
    # being drawn is this service's own. The chat page is built by its app a piece at a time,
    # so the same measurement there is partly the app still working, and only how much of it
    # ends up transcribed is worth asserting.
    for mode, least, clocked in (("essay", 100, True), ("chat", 100, False)):
        dev.clear_log()
        dev.surface(mode=mode, enable=1, density=1, lens=1, layer="sound")
        time.sleep(16)

        read = timed("READING source")
        drawn = [(at, int(re.search(r"SHOWING (\d+)", line).group(1)))
                 for at, line in timed("SHOWING ")]
        planned = [int(re.search(r"lines=(\d+)", line).group(1)) for _, line in read]
        if not read or not drawn:
            failures.append(f"{mode}: the page was never read or never drawn")
            continue

        most = max(n for _, n in drawn)
        # Whole, not merely started: the words known and the words drawn are the same number.
        known = dev.annotated(seconds=20)
        print(f"  {mode}: {most} drawn, {len(known)} known, {max(planned)} lines read")
        if most < least:
            failures.append(
                f"{mode}: only {most} words were ever drawn, which is not a page")
        if known and most < len(known) * 0.95:
            failures.append(
                f"{mode}: {most} words drawn of {len(known)} known - the rest of the page has "
                f"nothing on it")

        # And drawn as soon as it is read, rather than after something else.
        #
        # Measured from the read that produced these words, not from the first read in the
        # log: a page that has just been opened is often read once while the screen before it
        # is still up, and timing from that reads the app launching rather than anything this
        # service did.
        # Only what was drawn after this page was read. The log is cleared before the page is
        # asked for, but a draw of the page before can still land after that, and pairing it
        # with this page's read times something that never happened.
        up = [at for at, n in drawn if n > 0 and at >= read[0][0]]
        if not up:
            failures.append(f"{mode}: nothing was ever put on the screen")
            continue
        before = [at for at, _ in read if at <= up[0]]
        waited = up[0] - (before[-1] if before else read[0][0])
        print(f"  {mode}: first words {waited:.2f}s after the read that found them")
        if clocked and waited > DRAWN_WITHIN_S:
            failures.append(
                f"{mode}: the page was read and then nothing was drawn for {waited:.1f}s, "
                f"which is longer than {DRAWN_WITHIN_S}s")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a page is transcribed whole, and as soon as it is read")


if __name__ == "__main__":
    main()
