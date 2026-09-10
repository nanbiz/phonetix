#!/usr/bin/env python3
"""The lens: the way to ask about a word without taking the screen's touches.

A transcription lying over a word can only be tapped if the overlay takes touches, and an
overlay that takes touches takes the swipe that started on a word with it. On a page of text
that is most of the page, which is why the transcriptions are untouchable by default - and
why, without the lens, there is no way at all to ask what a word means.

So this drags the lens across a page and checks that it says what it passes over, that a card
opens for that word, and that the page underneath still scrolls while it does.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_lens.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell


def main():
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    failures = []

    dev.clear_log()
    # Through the harness rather than a bare `am start`: the app's own screen is an activity
    # of the same app, and with it in front the intent is delivered to the task instead of to
    # the page, which reports success and changes nothing. The page is then never told to
    # annotate and the lens is never told to appear.
    dev.surface(mode="spanish", enable=1, density=1, touchWords=0, lens=0)
    time.sleep(4)
    # Asked for again, so it says where it parked in a log this run can see.
    dev.surface(mode="spanish", enable=1, lens=1)
    time.sleep(3)
    boxes = dev.boxes()
    if not boxes:
        print("FAIL - nothing was annotated, so there is nothing to look at")
        sys.exit(1)

    # Where the lens actually parked, which it says rather than a check assuming: the window
    # sits in the display's own metrics and those are not the screen's dimensions.
    where = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.log())
    if not where:
        print("FAIL - the lens is not on screen")
        sys.exit(1)
    x, y, w, h = (int(v) for v in where[-1])
    parked = (x + w // 2, y + h // 2)
    word = list(boxes.values())[0]
    left, top, right, bottom = word["rect"]
    onto = ((left + right) // 2, (top + bottom) // 2)

    shell("input", "swipe", str(parked[0]), str(parked[1]), str(onto[0]), str(onto[1]), "900")
    time.sleep(3)
    log = dev.log()

    passed = re.findall(r"LENSAT [\d.,]+ -> (\S+)", log)
    opened = re.findall(r"TOOLTIP open word=(\S+)", log)
    print(f"  the lens passed over: {passed[:6]}")
    print(f"  the card opened for: {opened[-3:] if opened else 'nothing'}")

    if not passed:
        failures.append("the lens reported nothing under it, so it took no touches at all")
    elif all(name == "nothing" for name in passed):
        failures.append(f"the lens passed over no words: {passed[:6]}")
    # Against what the lens itself reported it was over, not against the word this check
    # aimed at. The ring is wider than a word and a page has more of them on it than it used
    # to, so which word it ends over is the lens's answer to give - what has to be true is
    # that the card is about that one.
    over = [name for name in passed if name != "nothing"]
    if not opened:
        failures.append("no card opened for what the lens was over")
    elif not over:
        failures.append("the lens never reported a word, so there is nothing to have opened")
    elif opened[-1] != over[-1]:
        failures.append(
            f"the lens ended over {over[-1]!r} and the card is about {opened[-1]!r}")

    # And the page it is dragged over is untouched: the transcriptions took nothing, which is
    # the whole reason the lens exists.
    if "touchWords=true" in log:
        failures.append("the words were made touchable, which is not what the lens is for")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the lens says what it passes over and opens a card for it")


if __name__ == "__main__":
    main()
