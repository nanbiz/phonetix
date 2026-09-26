#!/usr/bin/env python3
"""Holding the button turns the replacing on and off, and the button says which it is.

The heavier gesture on the button used to put the whole screen into the reader's own
language, which is something they ask for rarely. What they reach for constantly is having
the words back, so that is what a hold does now - and because the button is what did it, the
button is the only thing that can say which state it left behind: a reader who held it and
saw the page not change cannot otherwise tell that from a gesture that did nothing.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_hold.py
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


def hold(at):
    shell("input", "swipe", str(at[0]), str(at[1]), str(at[0]), str(at[1]), "900")


def main():
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    failures = []

    dev.surface(mode="plain", enable=1, density=1, lens=1, layer="both", target="none")
    time.sleep(10)
    told = believed()
    at = ((told.get("mark") or {}).get("markAt") or {})
    if not at:
        print("FAIL - the button is not on screen, so it cannot be held")
        sys.exit(1)
    middle = (at["x"] + 52, at["y"] + 52)

    def painted():
        """What is actually on the screen, not what the service knows about.

        A word the reader can no longer see is still a word the service holds - that is what
        makes the mark able to answer it - so counting what was read says nothing about
        whether anything is drawn."""
        o = (believed().get("overlay") or {})
        return o.get("chipsShown") or 0

    # Waited for rather than sampled: a service that has just been told to read a page has
    # not read it yet, and a check that asks once reads the gap.
    drawn = 0
    for _ in range(10):
        drawn = painted()
        if drawn:
            break
        time.sleep(2)
    print(f"  with the replacing on: {drawn} words painted")
    if not drawn:
        print("FAIL - nothing was replaced to begin with")
        sys.exit(1)

    # Held once: the words go, and the setting says so.
    dev.clear_log()
    hold(middle)
    time.sleep(5)
    said = re.findall(r"HELD paused (\S+)", dev.log())
    after = painted()
    print(f"  held once: the service says {said[-1:] or 'nothing'}, {after} words painted")
    if not said:
        failures.append("holding the button said nothing about the replacing")
    elif said[-1] != "true":
        failures.append(f"holding the button with the overlay on left it {said[-1]!r}")
    if after:
        failures.append(f"the replacing was turned off and {after} words are still replaced")

    # And again: it comes back as it was, rather than as some mode nobody chose.
    dev.clear_log()
    hold(middle)
    time.sleep(6)
    said = re.findall(r"HELD paused (\S+)", dev.log())
    back = 0
    for _ in range(10):
        back = painted()
        if back:
            break
        time.sleep(2)
    print(f"  held again: {said[-1:] or 'nothing'}, {back} words painted")
    if not said or said[-1] != "false":
        failures.append("holding it again did not put the overlay back")
    if not back:
        failures.append("the replacing was turned back on and nothing was replaced")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - holding the button turns the replacing off and on again")


if __name__ == "__main__":
    main()
