#!/usr/bin/env python3
"""A word behind something is not a word on the screen.

An app's own menu or bottom sheet is drawn inside that app's window, not in a window of its
own, so nothing in the window list says it is there. The words underneath it are still in the
tree and still report where they are, and their transcriptions were painted on top of the
sheet - which is what a reader photographed and called covered words still being displayed.

Draw order is what tells: anything painted after a word's own node is over it. Text was
exempt from that, because two runs of text in one flow share the visual line where one ends
and the next begins and neither covers anything - but a sheet carries text too. What tells
those apart is room: a neighbouring run begins and ends about where the word does, while a
sheet reaches well past it on both sides.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_covered.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, SERIAL
import state as State

# The sheet's own words, which are on the screen and must still be transcribed.
ITS_OWN = ("the", "sheet", "over", "it")


def believed():
    try:
        name = State.ask(SERIAL)
        return State.fetch(SERIAL, name) if name else {}
    except Exception:
        return {}


def drawn(dev, want=True):
    """What is painted, waited for rather than sampled once."""
    for _ in range(10):
        boxes = ((believed().get("overlay") or {}).get("boxes") or [])
        if bool(boxes) == want:
            return boxes
        time.sleep(1.5)
    return ((believed().get("overlay") or {}).get("boxes") or [])


def main():
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    failures = []

    # First the same page with nothing over it, so what follows is about the sheet and not
    # about the page being unreadable.
    dev.surface(mode="unique", enable=1, density=1, lens=1, layer="sound")
    time.sleep(8)
    bare = drawn(dev)
    print(f"  with nothing over it: {len(bare)} words")
    if not bare:
        print("FAIL - nothing was transcribed with the page clear, so there is nothing to cover")
        sys.exit(1)

    dev.surface(mode="sheet", enable=1, density=1, lens=1, layer="sound")
    time.sleep(10)
    boxes = drawn(dev)
    top = dev.height // 2

    under = [b for b in boxes
             if ((b.get("rect") or {}).get("top") or 0) >= top
             and b.get("word") not in ITS_OWN]
    above = [b for b in boxes if ((b.get("rect") or {}).get("top") or 0) < top]
    its_own = [b for b in boxes if b.get("word") in ITS_OWN]
    print(f"  with a sheet over the lower half: {len(above)} words above it, "
          f"{len(under)} of the page under it, {len(its_own)} of the sheet's own")

    if under:
        failures.append(
            f"{len(under)} words behind the sheet are still transcribed, among them "
            f"{[b.get('word') for b in under[:5]]}")
    if not above:
        failures.append("nothing above the sheet is transcribed, so the page was lost entirely")
    if not its_own:
        failures.append("the sheet's own words are not transcribed, though they are on top")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - what a sheet covers is not transcribed, and what it says is")


if __name__ == "__main__":
    main()
