#!/usr/bin/env python3
"""The lens on a caption that keeps changing under it.

A video player writes its caption a word at a time, dropping the oldest, so the words under a
finger held still slide along every half second. The card has to come up all the same, for
whichever word the lens is on when its answer is ready. The "rolling" page of the debug app
plays such a caption; "still" freezes it full, as a caption that is not changing.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_rolling.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, card_while_held  # noqa: E402


def main():
    dev = Device()
    dev.enable_service()
    dev.set_enabled(True)
    failures = []
    for still in (1, 0):
        dev.surface(mode="rolling", target="en", known="none", learn="es", enable=1, density=1, still=still)
        time.sleep(8)
        texts = []
        for word in ("trabajo", "diferente", "siempre", "hacer"):
            texts, _ = card_while_held(dev, word, dwell=3.0)
            print(f"  {'still' if still else 'rolling'} {word}: {texts}")
            if texts:
                break
        if not texts:
            failures.append(f"no card on a {'still' if still else 'rolling'} caption")
    if failures:
        print("\nFAIL - " + "; ".join(failures))
        sys.exit(1)
    print("\nPASS - the lens opens a card on a caption, still or rolling")


if __name__ == "__main__":
    main()
