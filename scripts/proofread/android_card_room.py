#!/usr/bin/env python3
"""A card too tall for the room beside its word goes beside it anyway, and scrolls.

A card sits under the word it is about, or over it where there is no room under. Where there
was room on neither side it was pushed back onto the screen whole - over the very word it was
about, and over the circle pointing at it. It is now cut to the larger side and scrolls.

Made to happen on purpose: the system font at more than twice its size makes any card tall, and the word
asked about is the one nearest the middle of the screen, where each side has half of it.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_card_room.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell, SERIAL
import state as State


def believed():
    try:
        name = State.ask(SERIAL)
        return State.fetch(SERIAL, name) if name else {}
    except Exception:
        return {}


def main():
    dev = Device()
    failures = []
    shell("settings", "put", "system", "font_scale", "2.6")
    try:
        # Stopped first, so the service comes back up with the font it is now given.
        shell("am", "force-stop", "io.github.nanbiz.phonetix")
        time.sleep(2)
        if not dev.enable_service():
            raise SystemExit("the service would not start")
        dev.set_enabled(True)
        shell("input", "keyevent", "3")
        time.sleep(2)
        dev.surface(mode="plain", enable=1, density=1, target="none", touchWords=1, paused=0,
                    layer="sound")
        time.sleep(8)
        boxes = []
        for _ in range(12):
            boxes = (believed().get("overlay") or {}).get("boxes") or []
            if boxes:
                break
            time.sleep(2)
        if not boxes:
            print("FAIL - nothing was drawn, so there is no word to ask about")
            sys.exit(1)
        middle = min(boxes, key=lambda b: abs((b["rect"]["top"] + b["rect"]["bottom"]) / 2
                                               - dev.height / 2))
        r = middle["rect"]
        left, top, right, bottom = r["left"], r["top"], r["right"], r["bottom"]
        dev.clear_log()
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(4)
        lines = dev.lines("CARDAT").splitlines()
        print(f"  asked about {middle['word']!r} at {top}..{bottom}: {lines[-1:] or 'no card'}")
        if not lines:
            failures.append("no card opened")
        else:
            last = lines[-1]
            y = int(re.search(r" y=(\d+)", last).group(1))
            cut = re.search(r"cut to (\d+)", last)
            height = int(cut.group(1)) if cut else int(re.search(r"height=(\d+)", last).group(1))
            print(f"  the card is at {y}..{y + height}{' (cut to fit)' if cut else ''}")
            if y < bottom and y + height > top:
                failures.append(f"the card at {y}..{y + height} lies over its word at {top}..{bottom}")
    finally:
        shell("settings", "put", "system", "font_scale", "1.0")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a card with no room either side of its word is still beside it")


if __name__ == "__main__":
    main()
