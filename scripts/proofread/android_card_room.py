#!/usr/bin/env python3
"""A card too tall for the room beside its word still goes beside it, never over it.

The side button's card sits under the word it is about, or over it, or beside it, wherever it
is clear of the word, the circle on it and the finger. Where there was room on no side it used
to be pushed back onto the screen whole - over the very word it was about, and over the circle
pointing at it, so the reader saw the answer and lost the question.

Asked where it is hardest: the system font at more than twice its size makes the card as tall
as a reader can make it, and the word asked about is the one nearest the middle of the screen,
where each side has half of it. The side button is held over that word, and every place the
card is put while it is up has to leave the word in sight and stay on the screen. The card is
compact now, about a fifth of the screen even at this size, so this is a guard that the
placement keeps its rule rather than a case that is tight today.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_card_room.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, card_while_held, shell, SERIAL
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
        shell("input", "keyevent", "3")
        time.sleep(2)
        dev.surface(mode="plain", enable=1, density=1, target="none")
        time.sleep(8)
        boxes = []
        for _ in range(12):
            boxes = (believed().get("overlay") or {}).get("boxes") or []
            if boxes:
                break
            time.sleep(2)
        if not boxes:
            print("FAIL - no word on the page is known, so there is no word to ask about")
            sys.exit(1)
        # A word the page has once, so the card that comes up can only be about this one.
        once = [b for b in boxes if sum(1 for o in boxes if o["word"] == b["word"]) == 1]
        middle = min(once or boxes, key=lambda b: abs((b["rect"]["top"] + b["rect"]["bottom"]) / 2
                                                     - dev.height / 2))
        word = middle["word"]
        r = middle["rect"]
        left, top, right, bottom = r["left"], r["top"], r["right"], r["bottom"]
        texts, _ = card_while_held(dev, word)
        placed = [(int(x), int(y), int(w), int(h)) for x, y, w, h in re.findall(
            rf"CARDAT {re.escape(word)} at=(-?\d+),(-?\d+) size=(\d+)x(\d+)", dev.lines("CARDAT"))]
        # Only where it was put once it had been measured: before that it is placed by the
        # size of the card before it, which is a guess the next frame corrects.
        placed = [p for p in placed if p[2] > 0 and p[3] > 0]
        print(f"  asked about {word!r} at {left},{top}..{right},{bottom}: "
              f"the card says {texts or 'nothing'}")
        if not texts or not placed:
            failures.append(f"no card came up for {word!r}")
        for x, y, w, h in sorted(set(placed)):
            print(f"  the card is at {x},{y} {w}x{h}")
            if x < right and x + w > left and y < bottom and y + h > top:
                failures.append(
                    f"the card at {x},{y} {w}x{h} lies over its word at "
                    f"{left},{top}..{right},{bottom}")
            if x < 0 or y < 0 or x + w > dev.width or y + h > dev.height:
                failures.append(f"the card at {x},{y} {w}x{h} is partly off the screen")
    finally:
        shell("settings", "put", "system", "font_scale", "1.0")

    if failures:
        print("\nFAIL")
        for line in sorted(set(failures)):
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a card with no room either side of its word is still beside it")


if __name__ == "__main__":
    main()
