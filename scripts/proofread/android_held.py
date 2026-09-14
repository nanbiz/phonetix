#!/usr/bin/env python3
"""The lens held still over a word, on a screen that keeps being written into.

Every other check here drags the lens and lets go, because neither `input swipe` nor `input
motionevent` can hold a finger down: the first always lifts at the end of its path and the
second does not keep one gesture open. So the case a reader is actually in - holding the mark
over a word while they read the card - was never driven, and what happens there is this:

A word that reflows by a pixel arrives as a new box. Treated as a different word, it built the
card again - the window torn down and another put up, with a tick under the thumb for each.
Held on a screen whose text keeps moving, that is a card that flickers and a phone that buzzes
without stopping. Measured on an emulator before the fix: six cards built and five torn down in
eight seconds while the finger did not move.

What this asks is narrow and definite: the same word must never be built twice in a row. A
different word arriving under a still lens is the page moving under it, which is the card
doing its job.

Root is needed to write to the touchscreen, and asking for it restarts adbd - which on this
machine took three emulators down within a minute each, and a relaunched one crash-looped and
wrote a gigabyte core per death. So `adb root` is left to whoever runs this, deliberately, and
this is its own script rather than part of android_lens.py: it must not be able to kill an
ordinary run.

What is honest about it: the six-built-five-torn-down measurement above was taken once, on one
emulator, against the build before the fix. Neither fixture here has reproduced it since - the
held word's box has to actually move, and `live` only grows one line while `chat` was never
driven to the end. So this drives the gesture that nothing else here can drive, and it has
never yet failed on the code it was written for. Treat a pass as "the gesture ran", not as
"the flicker cannot come back", until a fixture is found that fails without the fix.

  adb -s emulator-5556 root
  PHONETIX_ANDROID_SERIAL=emulator-5556 uv run python scripts/proofread/android_held.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell

EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
SLOT, TRACKING, POS_X, POS_Y, MAJOR, PRESSURE = 47, 57, 53, 54, 48, 58
BTN_TOUCH = 330
FULL = 32767
# Long enough that the message is still being written for the whole of the hold.
WRITING_MS = 30000
HOLD_S = 8


def touchscreen():
    """Which input device is the touchscreen, since it differs from device to device."""
    node = None
    for line in shell("getevent", "-pl").splitlines():
        if line.startswith("add device"):
            node = line.rsplit(" ", 1)[-1].strip()
        if "ABS_MT_POSITION_X" in line and node:
            return node
    return None


def frame(dev, touch, x, y, first=False):
    rx, ry = int(x * FULL / dev.width), int(y * FULL / dev.height)
    out = [f"sendevent {touch} {EV_ABS} {SLOT} 0"]
    if first:
        out += [f"sendevent {touch} {EV_ABS} {TRACKING} 7",
                f"sendevent {touch} {EV_KEY} {BTN_TOUCH} 1",
                f"sendevent {touch} {EV_ABS} {MAJOR} 12",
                f"sendevent {touch} {EV_ABS} {PRESSURE} 81"]
    out += [f"sendevent {touch} {EV_ABS} {POS_X} {rx}",
            f"sendevent {touch} {EV_ABS} {POS_Y} {ry}",
            f"sendevent {touch} {EV_SYN} 0 0"]
    return out


def lift(touch):
    return [f"sendevent {touch} {EV_ABS} {SLOT} 0",
            f"sendevent {touch} {EV_ABS} {TRACKING} -1",
            f"sendevent {touch} {EV_KEY} {BTN_TOUCH} 0",
            f"sendevent {touch} {EV_SYN} 0 0"]


def repark(dev):
    """Put the mark back where it parks by itself, halfway down the right edge.

    It stays where a drag last left it, and a drag can leave it in the status bar, where the
    system takes the touch and pulls the notification shade rather than the mark. Where it
    rests lives in the service and nowhere else, so the service has to go; stopping the app
    switches accessibility off, which is why it is turned on again straight afterwards.
    """
    shell("cmd", "statusbar", "collapse")
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    time.sleep(2)
    return dev.enable_service()


def main():
    dev = Device()
    if "uid=0" not in shell("id"):
        print("FAIL - the finger cannot be held down without root: run `adb root` first")
        sys.exit(1)
    touch = touchscreen()
    if not touch:
        print("FAIL - no touchscreen to write to")
        sys.exit(1)
    shell(*["sh", "-c", "; ".join(lift(touch))])
    if not repark(dev):
        raise SystemExit("the service would not start")

    # A conversation with a message growing in it: everything under it shifts a little every
    # time, which is what makes a word's box arrive a pixel from where it was - the phone the
    # reader was holding was showing exactly this.
    dev.surface(mode="chat", enable=1, density=1, lens=0, growEvery=400)
    time.sleep(3)
    dev.surface(mode="chat", enable=1, density=1, lens=1, growEvery=400)
    time.sleep(4)
    boxes = dev.annotated()
    where = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
    if not boxes or not where:
        print("FAIL - nothing to hold the lens over on a screen that changes")
        sys.exit(1)
    x, y, w, h = (int(v) for v in where[-1])
    parked = (x + w // 2, y + h // 2)
    # Below the line that grows, so this word's box moves every time the message does.
    word = sorted(boxes.values(), key=lambda b: b["rect"][1])[-2]
    left, top, right, bottom = word["rect"]
    onto = ((left + right) // 2, (top + bottom) // 2)
    print(f"  the mark is at {parked}, held over {word['word']!r} at {onto}")

    cards = []
    for attempt in range(3):
        dev.clear_log()
        path = frame(dev, touch, parked[0], parked[1], first=True)
        # Away from the top edge first: a drag that begins inside the status bar pulls the
        # notification shade rather than the mark.
        away = (parked[0], min(max(parked[1], dev.height // 3), dev.height * 2 // 3))
        for i in range(1, 5):
            path += frame(dev, touch, parked[0],
                          parked[1] + (away[1] - parked[1]) * i // 4)
        for i in range(1, 13):
            path += frame(dev, touch,
                          away[0] + (onto[0] - away[0]) * i // 12,
                          away[1] + (onto[1] - away[1]) * i // 12)
        shell(*["sh", "-c", "; ".join(path)])
        time.sleep(HOLD_S)
        cards = re.findall(r"TOOLTIP open word=(\S+)", dev.lines("TOOLTIP open"))
        shell(*["sh", "-c", "; ".join(lift(touch))])
        shell("cmd", "statusbar", "collapse")
        if cards:
            break
        repark(dev)
        dev.surface(mode="chat", enable=1, density=1, lens=1, growEvery=400)
        time.sleep(4)
        found = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
        if found:
            x, y, w, h = (int(v) for v in found[-1])
            parked = (x + w // 2, y + h // 2)

    again = [name for i, name in enumerate(cards) if i > 0 and name == cards[i - 1]]
    print(f"  {len(cards)} cards while it was held for {HOLD_S}s, "
          f"{len(again)} of them the same word twice over")
    if not cards:
        # Nothing was asked about, so nothing was measured: a check that passes on that would
        # go on passing while the card flickered in a reader's hand.
        print("\nFAIL - the lens was held over nothing, so the flicker was never measured")
        sys.exit(1)
    if again:
        print(f"\nFAIL - the card was built again for a word it was already about: {again[:4]}")
        sys.exit(1)
    print("\nPASS - the card is not built again for the word it is already about")


if __name__ == "__main__":
    main()
