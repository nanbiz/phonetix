#!/usr/bin/env python3
"""The lens on the captions of the real YouTube app.

A caption is a line of text that becomes another line every second or two, in an app that does
not announce it and does not say where its letters are. The debug page's rolling caption does
not behave like that closely enough: it was green while the real captions gave no card at all.
So this plays a spoken video in the ReVanced YouTube the emulator carries, which needs no
account, and holds the lens on the places a caption is drawn. It needs the network.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_youtube.py
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import state as State  # noqa: E402
from android_harness import SERIAL, Device, adb, finger_for, shell  # noqa: E402

YOUTUBE = "app.revanced.android.youtube"
SPOKEN = "https://www.youtube.com/watch?v=UF8uR6Z6KLc"
# Where a phone-shaped portrait player draws its two caption lines, as shares of the screen.
ON_THE_CAPTION = ((0.37, 0.306), (0.60, 0.333), (0.28, 0.333))


def hold(dev, target):
    """Hold the lens on [target] for seven seconds; the words a card was laid out for."""
    mark = State.fetch(SERIAL, State.ask(SERIAL))["mark"]["markAt"]
    home = (mark["x"] + 52, mark["y"] + 52)
    dpi = int(re.search(r"(\d+)", shell("wm", "density")).group(1))
    aim = finger_for(target, home, dpi, dev.width, dev.height)
    lines = ["type= raw events", "count= 1", "speed= 1.0", "start data >>",
             f"DispatchPointer(0,0,0,{home[0]},{home[1]},1,1,0,1,1,0,0)"]
    for i in range(1, 13):
        x = home[0] + (aim[0] - home[0]) * i / 12
        y = home[1] + (aim[1] - home[1]) * i / 12
        lines += ["UserWait(30)", f"DispatchPointer(0,0,2,{int(x)},{int(y)},1,1,0,1,1,0,0)"]
    for _ in range(14):
        lines += ["UserWait(500)",
                  f"DispatchPointer(0,0,2,{int(aim[0])},{int(aim[1])},1,1,0,1,1,0,0)"]
    lines.append(f"DispatchPointer(0,0,1,{int(aim[0])},{int(aim[1])},1,1,0,1,1,0,0)")
    local = "/tmp/phonetix-youtube.monkey"
    with open(local, "w") as f:
        f.write("\n".join(lines) + "\n")
    adb("push", local, "/data/local/tmp/phonetix-youtube.monkey")
    dev.clear_log()
    subprocess.run(["adb", "-s", SERIAL, "shell", "monkey", "-f",
                    "/data/local/tmp/phonetix-youtube.monkey", "1"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60, check=False)
    # A card whose height is zero was torn down before it was ever laid out.
    return re.findall(r"CARD card@\d+,\d+,\d+,[1-9]\d* scrollable=\d+ \[([^@]+)@", dev.lines("CARD"))


def main():
    dev = Device()
    dev.enable_service()
    dev.set_enabled(True)
    if YOUTUBE not in shell("pm", "list", "packages", YOUTUBE):
        print(f"FAIL - {YOUTUBE} is not on this emulator")
        sys.exit(1)
    shell("settings", "put", "secure", "accessibility_captioning_enabled", "1")
    shell("am", "force-stop", YOUTUBE)
    shell("am", "start", "-a", "android.intent.action.VIEW", "-d", SPOKEN, YOUTUBE)
    time.sleep(45)
    answered = 0
    for x, y in ON_THE_CAPTION:
        words = hold(dev, (int(dev.width * x), int(dev.height * y)))
        print(f"  held at {x:.2f},{y:.2f}: {sorted(set(words))}")
        answered += bool(words)
    shell("am", "force-stop", YOUTUBE)
    if answered < 2:
        print(f"\nFAIL - a card on {answered} of {len(ON_THE_CAPTION)} holds over a caption")
        sys.exit(1)
    print("\nPASS - the lens opens a card on the real app's captions")


if __name__ == "__main__":
    main()
