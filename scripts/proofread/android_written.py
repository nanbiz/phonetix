#!/usr/bin/env python3
"""What the mark knows while an answer is being written into the page.

A conversation that is being written into changes the lines under the overlay's plan several
times a second. A following pass then carries only the lines that happened not to change, and
that was counted a success as long as it carried one: the overlay was left believing in a
single word on a screenful of them, and the mark dragged across the page answered "nothing"
from one end to the other.

Taken off a reader's own phone while an answer was being written: one word believed, where a
full read of the same screen found forty-seven.

What this asks is that the overlay keeps believing in most of the page while the page is being
written into - not that it is perfect, which it cannot be on a screen whose text is changing,
but that it does not collapse to a handful.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_written.py
"""
import os
import re
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell
import state as State

PKG = "io.github.nanbiz.phonetix"

# How much of what a still screen holds must survive while it is being written into.
ENOUGH = 0.5


def believed(serial):
    """How many words the overlay believes are on the screen, asked of the app itself.

    Not counted out of the log: a page holding still announces nothing, so there are no
    passes to count, and in this mode nothing is painted either. What the mark can answer
    about is what the overlay believes, which is what the dump says.
    """
    name = State.ask(serial)
    if not name:
        return 0
    return (State.fetch(serial, name).get("overlay") or {}).get("words") or 0


def main():
    serial = State.device()
    os.environ.setdefault("PHONETIX_ANDROID_SERIAL", serial)
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)

    # A page holding still, to say what the screen is worth.
    #
    # Started afresh each time: the page builds its list when it is created, so asking the
    # one already up to start writing into itself changes nothing at all.
    shell("am", "force-stop", PKG)
    time.sleep(1)
    dev.enable_service()
    dev.surface(mode="chat", enable=1, density=1, lens=1)
    time.sleep(8)
    # Waited for rather than asked once: a page that has just come up has not been read yet,
    # and "nothing believed" then is the check measuring its own impatience.
    settled = 0
    for _ in range(10):
        settled = believed(serial)
        if settled:
            break
        time.sleep(2)
    print(f"  holding still, the overlay believed in up to {settled} words")
    if settled < 8:
        print("\nFAIL - the still page was never read, so there is nothing to compare against")
        sys.exit(1)

    # The same page with an answer being written into it.
    shell("am", "force-stop", PKG)
    time.sleep(1)
    dev.enable_service()
    dev.surface(mode="chat", enable=1, density=1, lens=1, growEvery=400)
    time.sleep(8)
    written = []
    for _ in range(6):
        written.append(believed(serial))
        time.sleep(1.5)
    if not written:
        print("\nFAIL - nothing was drawn at all while the page was being written into")
        sys.exit(1)
    middle = statistics.median(written)
    print(f"  while it was being written into: {min(written)} at worst, {middle:.0f} typically, "
          f"{max(written)} at best, over {len(written)} passes")
    if middle < settled * ENOUGH:
        print(f"\nFAIL - the overlay kept {middle:.0f} of {settled} words while the page was "
              f"being written into, fewer than the {ENOUGH:.0%} this asks for")
        sys.exit(1)
    print("\nPASS - the page keeps its words while an answer is being written into it")


if __name__ == "__main__":
    main()
