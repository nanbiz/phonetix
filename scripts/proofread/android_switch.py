#!/usr/bin/env python3
"""One app's words must not be left believed over another app's screen.

Filmed by a reader switching between two apps: a screenful of chips from the page before,
piled in the corner of the app they had just opened, each carrying a word that app never had.
Two things put them there. What was known stayed known until the next read finished, and the
next scroll the new app announced was followed rather than read - and following deliberately
does not fetch the window, so it carried the old app's plan and placed it over the new app's
text. Nothing is painted now, but the words the service knows are the words the side button
answers, and an old app's word left there is a card about a word that is not on the screen.

What this asks is the plain thing: once the app in front has changed, nothing of the app
before it may still be known.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_switch.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell
import state as State

# How long the old words may still be known for. They go when the app in front is seen to have
# changed, and what is left is the moment between the switch and that being noticed.
GRACE_S = 2.5
# Words only the fixture has, so a word known with one of them came from it and nowhere else.
ITS_OWN = ("Reading", "paragraph", "pronunciation", "unfamiliar", "dictionary")


def believed(serial):
    """What the service says it knows, and on which app."""
    name = State.ask(serial)
    if not name:
        return None, []
    told = State.fetch(serial, name)
    overlay = told.get("overlay") or {}
    return (told.get("screen") or {}).get("package"), [
        box.get("word", "") for box in (overlay.get("boxes") or [])
    ]


def main():
    serial = State.device()
    os.environ.setdefault("PHONETIX_ANDROID_SERIAL", serial)
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)

    # A page of ours, read.
    dev.surface(mode="plain", enable=1, density=1, lens=1)
    drawn = dev.annotated(seconds=40)
    if not drawn:
        print("\nFAIL - nothing was read on the page to begin with")
        sys.exit(1)
    print(f"  on our own page: {len(drawn)} words known")

    # And then another app entirely.
    shell("am", "start", "-a", "android.settings.SETTINGS")
    began = time.time()
    left = []
    for _ in range(12):
        time.sleep(1)
        package, words = believed(serial)
        mine = [w for w in words if any(own in w for own in ITS_OWN)]
        if package and "settings" in package:
            left = mine
            if not mine:
                break
    waited = time.time() - began
    print(f"  {waited:.0f}s after opening the settings app: "
          f"{len(left)} words of the page before still known")
    if left and waited > GRACE_S:
        print(f"\nFAIL - the page before is still known over the settings app: {left[:6]}")
        sys.exit(1)

    # And the new app is read, rather than merely cleared.
    its_own = []
    for _ in range(12):
        package, words = believed(serial)
        if package and "settings" in package and words:
            its_own = words
            break
        time.sleep(2)
    print(f"  the settings app's own words: {its_own[:6] or 'none'}")
    if not its_own:
        print("\nFAIL - the app switched to was never read")
        sys.exit(1)
    print("\nPASS - the words of one app do not stay over another")


if __name__ == "__main__":
    main()
