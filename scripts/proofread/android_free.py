#!/usr/bin/env python3
"""On neither side, the button stays where it is put down.

Left and right are the hand the phone is held in, and the button flies back to that edge when
it is let go. A reader who wants it somewhere else - out of the way of one app's own controls,
say - has a third answer, and what it means is that letting go leaves it there.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_free.py
"""
import os
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


def where():
    """Where the button is, waited for: it parks with an animation."""
    for _ in range(12):
        at = ((believed().get("mark") or {}).get("markAt") or {})
        if at:
            return at
        time.sleep(1)
    return {}


def settled():
    """Where it comes to rest, once it has stopped moving."""
    last = where()
    for _ in range(8):
        time.sleep(1)
        now = where()
        if now == last:
            return now
        last = now
    return last


def carry(frm, to):
    """Drag it across the screen, slowly enough to be a drag and not a fling."""
    shell("input", "swipe", str(frm[0]), str(frm[1]), str(to[0]), str(to[1]), "700")


def main():
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    failures = []
    across, down = dev.width, dev.height
    middle = (across // 2, down // 2)

    for side, stays in (("free", True), ("right", False)):
        dev.surface(mode="plain", enable=1, density=1, lens=1, layer="both",
                    target="none", side=side, pin=0)
        time.sleep(6)
        at = where()
        if not at:
            print(f"FAIL - the button is not on screen with the side set to {side!r}")
            sys.exit(1)
        carry((at["x"] + 52, at["y"] + 52), middle)
        rest = settled()
        # How far in from the right edge it ended, which is the edge it started against. A
        # drag driven from here does not put the button exactly where it was aimed, so what is
        # measured is whether it went back to its edge at all, not the pixel it stopped on.
        edge = across - (rest.get("x", 0) + 105)
        print(f"  {side}: dragged in from the edge, it came to rest {edge}px from it")
        if stays and edge < 105:
            failures.append(
                "on neither side the button went back to the edge it was dragged off")
        if not stays and edge > 105:
            failures.append(
                f"on the right the button stayed {edge}px in from its own edge")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the button stays where it is left, or comes back to its side")


if __name__ == "__main__":
    main()
