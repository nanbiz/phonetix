#!/usr/bin/env python3
"""The target at the foot of the screen the mark is put away on.

Dragged near the bottom of the screen, a round target rises in the middle of the foot. Let go
on it, the mark goes and Phonetix is switched off, the way the switch in the app, the tile and
the accessibility button switch it off. Let go beside it, nothing is put away. Switched on
again, the mark is back where it waited before the drag, in sight.

What is asked is what the service holds afterwards (its state dump), not what a log line
says it did, and where the target is comes from the service too: it stands above whatever
bar the system has at the foot, which is a different height on every phone.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run scripts/proofread/android_put_away.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, drag_and_dwell
from android_lens import believed, parked_at, repark


def held():
    """Whether Phonetix is on and whether the mark is showing, from one reading of what the
    service holds, asked again until it answers: a dump not written yet is not an answer."""
    for _ in range(5):
        state = believed()
        if state:
            return ((state.get("settings") or {}).get("on"),
                    (state.get("mark") or {}).get("markShowing"))
        time.sleep(1)
    return None, None


def main():
    dev = Device()
    if not repark(dev):
        raise SystemExit("the service would not start")
    failures = []

    home = parked_at(dev, mode="prose")
    if home is None:
        print("FAIL - the mark never said where it parked")
        sys.exit(1)

    # Where the target is: the service says so when it puts it up, which is when a finger
    # comes down on the mark. A drag let go in the middle of the page learns it and puts
    # nothing away.
    dev.clear_log()
    drag_and_dwell(home, (dev.width // 2, dev.height // 2), dwell=0.3)
    found = re.findall(r"LENSTARGET (\d+),(\d+)", dev.lines("LENSTARGET"))
    if not found:
        print("FAIL - the target never went up, so there was nothing to let go on")
        sys.exit(1)
    target = tuple(int(v) for v in found[-1])
    print(f"  the target stands at {target} of {dev.width}x{dev.height}")
    if abs(target[0] - dev.width // 2) > 2:
        failures.append(f"the target is not in the middle: {target[0]} of {dev.width}")
    if not dev.height * 0.8 < target[1] < dev.height:
        failures.append(f"the target is not at the foot of the screen: {target[1]}")

    # Let go at the foot, well to the side of it: still on.
    home = parked_at(dev, mode="prose")
    drag_and_dwell(home, (dev.width // 6, target[1]), dwell=0.6)
    time.sleep(2)
    on, _ = held()
    print(f"  let go beside it: on={on}")
    if on is not True:
        failures.append("letting go beside the target put Phonetix away")

    # Let go on it: off, and the mark gone.
    home = parked_at(dev, mode="prose")
    dev.clear_log()
    drag_and_dwell(home, target, dwell=0.6)
    time.sleep(2)
    on, showing = held()
    print(f"  let go on it: on={on} mark showing={showing}")
    if on is not False:
        failures.append("letting go on the target did not put Phonetix away")
    if showing is not False:
        failures.append("the mark is still on the screen after being put away")

    # Switched on again: the mark is back where it waited, in sight.
    again = parked_at(dev, mode="prose")
    print(f"  switched on again: parked at {again}, before the drag at {home}")
    if again is None:
        failures.append("switched on again, the mark never came back")
    elif abs(again[0] - home[0]) > 4 or abs(again[1] - home[1]) > 4:
        failures.append(f"switched on again, the mark came back at {again}, not at {home}")
    if held()[1] is not True:
        failures.append("switched on again, the mark is not in sight")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the mark is put away on the target at the foot of the screen, and only there")


if __name__ == "__main__":
    main()
