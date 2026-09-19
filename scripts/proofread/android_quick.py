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

# How long a page may take to be drawn once the service has read it. The read itself is tens
# of milliseconds; what this catches is anything that makes the drawing wait on something else.
DRAWN_WITHIN_S = 0.6


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

    for mode, least in (("essay", 100), ("chat", 100)):
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
        first_read = read[0][0]
        up = [at for at, n in drawn if n > 0]
        if not up:
            failures.append(f"{mode}: nothing was ever put on the screen")
            continue
        waited = up[0] - first_read
        print(f"  {mode}: first words {waited:.2f}s after the first read")
        if waited > DRAWN_WITHIN_S:
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
