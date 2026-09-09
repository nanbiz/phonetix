"""How much of a real app's movement the layer actually followed.

The failure is rare per run and total when it happens, so counting bad runs needs dozens of
them. What the fix changes is continuous and can be measured every fling: the settings app
reports how far it really scrolled, in pixels, and the layer reports how far it carried the
words. The ratio between them is what a reader sees.
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import SERIAL, Device, shell

# Load first, because on an idle emulator the overlay always keeps up and this always reads
# 100 percent. Six runs, because the outcome measure is bimodal - a run is either starved or
# it is not - while this one is continuous and a handful of runs settles it.
load = int(sys.argv[1]) if len(sys.argv) > 1 else 4
runs = int(sys.argv[2]) if len(sys.argv) > 2 else 6
busy = [subprocess.Popen(["adb", "-s", SERIAL, "shell", "while true; do echo -n; done"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(load)]
dev = Device()
# Installing a build clears the accessibility grant, so without this the first runs measure a
# device with no overlay on it at all and read as nought per cent followed.
if not dev.enable_service():
    print("the service will not start")
    sys.exit(1)
# Which page is flung. The settings app reports how far it scrolled and is measured against
# that; a Compose list reports nothing at all - a scroll event there carries -1 whatever it
# did - so it is measured against the position the page itself logs. That is the case a reader
# in a Compose app is in, and it is not the case the reported-scroll work touches.
compose = os.environ.get("PHONETIX_COMPOSE") is not None
got = []
try:
    for n in range(runs):
        if compose:
            shell("am", "force-stop", "io.github.nanbiz.phonetix")
            time.sleep(1.5)
            shell("am", "start", "-n", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity",
                  "--es", "mode", "chat", "--ei", "enable", "1", "--ei", "density", "3",
                  "--ei", "allApps", "1",
                  "--ei", "measureMovingMax",
                  os.environ.get("PHONETIX_MEASURE_MOVING", "0"))
            time.sleep(2)
            dev.enable_service()
            # A Compose page has to compose itself and then be read once before there is a plan
            # to carry, and the overlay is not running until there is. Measured at three
            # seconds, most runs reported no layer at all while the same swipe by hand carried
            # the words a third of the way.
            time.sleep(5)
            shell("logcat", "-c")
            shell("input", "swipe", "540", "1500", "540", "600", "250")
            time.sleep(2.5)
            log = dev.log()
            where = [int(m) for m in re.findall(r"SCROLLY \d+ (-?\d+)", log)]
            moved = (max(where) - min(where)) if where else 0
            carried = [float(m) for m in re.findall(r"LAYER \d+ (-?[\d.]+) ", log)]
            if not carried:
                print(f"  run {n + 1}: the layer was not drawing at all, skipped")
                continue
            swing = max(carried) - min(carried)
            if abs(moved) < 50:
                print(f"  run {n + 1}: the page barely moved ({moved}px), skipped")
                continue
            got.append(swing / abs(moved))
            print(f"  run {n + 1}: page moved {abs(moved):5}px, layer carried {swing:7.1f}px"
                  f"  ({100 * swing / abs(moved):5.1f}%)")
            time.sleep(2)
            continue
        # Started fresh every run, because a swipe leaves the list where it stopped and an
        # am start against an activity that is already top-most only reaches onNewIntent: the
        # second run then flings a list that is already at the bottom, the page does not move,
        # and nothing is measured. Five of six runs read as "the layer was not drawing at all"
        # that way, and the one number that survived was taken for the answer.
        shell("am", "force-stop", "com.android.settings")
        time.sleep(1)
        shell("am", "start", "-n", "com.android.settings/.Settings")
        time.sleep(3)
        shell("logcat", "-c")
        shell("input", "swipe", "540", "1500", "540", "600", "250")
        time.sleep(1.4)
        log = dev.log()
        moved = sum(int(m) for m in re.findall(r"SAIDSCROLL \d+ dy=(-?\d+)", log)
                    if abs(int(m)) > 1)
        carried = [float(m) for m in re.findall(r"LAYER \d+ (-?[\d.]+) ", log)]
        if not carried:
            print(f"  run {n + 1}: the layer was not drawing at all, skipped")
            continue
        swing = max(carried) - min(carried)
        if abs(moved) < 50:
            print(f"  run {n + 1}: the page barely moved ({moved}px), skipped")
            continue
        got.append(swing / abs(moved))
        print(f"  run {n + 1}: page moved {abs(moved):5}px, layer carried {swing:7.1f}px"
              f"  ({100 * swing / abs(moved):5.1f}%)")
        time.sleep(2)
finally:
    for p in busy:
        p.kill()
if got:
    got.sort()
    print(f"\nfollowed {100 * got[len(got) // 2]:.0f}% of the movement (middle of {len(got)})")
