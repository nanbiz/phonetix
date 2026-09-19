#!/usr/bin/env python3
"""Tapping the button opens the ask panel, and tapping it again puts it away.

The panel is opened from the button, which is where the reader's finger already is. A second
tap on it used to do nothing at all, so the way out of the panel was the way back or a touch
on whatever was behind it - and a reader who had just opened it by tapping the button tapped
the button again first.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_panel.py
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


def panel_up():
    return bool((believed().get("ask") or {}).get("panelUp"))


def tap(at):
    shell("input", "tap", str(at[0]), str(at[1]))


def settled(want, seconds=6):
    """What the panel is doing, once it has stopped changing."""
    until = time.time() + seconds
    while time.time() < until:
        if panel_up() == want:
            return want
        time.sleep(0.5)
    return panel_up()


def main():
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    failures = []

    dev.surface(mode="plain", enable=1, density=1, lens=1, layer="both", target="none")
    time.sleep(8)
    def middle():
        """Where the button is now.

        Asked again before every tap rather than once: the panel brings the keyboard with it
        and the button moves up out of its way, so the place it was tapped to open the panel
        is a keyboard key by the time the panel is open.
        """
        for _ in range(12):
            at = ((believed().get("mark") or {}).get("markAt") or {})
            if at:
                return (at["x"] + 52, at["y"] + 52)
            time.sleep(1)
        return None

    if middle() is None:
        print("FAIL - the button is not on screen, so it cannot be tapped")
        sys.exit(1)

    if panel_up():
        print("  a panel was already open; putting it away first")
        tap(middle())
        settled(False)

    tap(middle())
    opened = settled(True)
    print(f"  tapped once: the panel is {'open' if opened else 'not open'}")
    if not opened:
        failures.append("tapping the button did not open the panel")

    # A moment for the button to finish climbing over the keyboard before it is aimed at.
    time.sleep(1.5)
    tap(middle())
    closed = settled(False)
    print(f"  tapped again: the panel is {'still open' if closed else 'away'}")
    if closed:
        failures.append("tapping the button again left the panel open")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the button opens the panel and the same tap puts it away")


if __name__ == "__main__":
    main()
