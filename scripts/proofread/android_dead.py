"""Catch a fling the words did not follow, and keep its log.

Under load a fling comes out one of two ways: the words ride the page the whole way, or they
do not move at all. The ratio between them averages the two into a number that describes
neither. This flings until it catches a dead one and writes the whole log out, so the reason
can be read rather than guessed at.
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import SERIAL, Device, shell

load = int(sys.argv[1]) if len(sys.argv) > 1 else 4
tries = int(sys.argv[2]) if len(sys.argv) > 2 else 12
out = sys.argv[3] if len(sys.argv) > 3 else "/tmp/dead.log"
# Load left behind by a run that was cut short, which would otherwise be measured as well.
subprocess.run(["adb", "-s", SERIAL, "shell", "pkill -f 'while true'"], capture_output=True)
busy = [subprocess.Popen(["adb", "-s", SERIAL, "shell", "while true; do echo -n; done"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(load)]
dev = Device()
if not dev.enable_service():
    print("the service will not start")
    sys.exit(1)
alive, dead = 0, 0
try:
    for n in range(tries):
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
        swing = (max(carried) - min(carried)) if carried else 0.0
        if abs(moved) < 50:
            continue
        share = swing / abs(moved)
        if share < 0.3:
            dead += 1
            if dead == 1:
                with open(out, "w") as f:
                    f.write(log)
                print(f"  run {n + 1}: dead ({100 * share:.0f}%), log written to {out}")
            else:
                print(f"  run {n + 1}: dead ({100 * share:.0f}%)")
        else:
            alive += 1
            print(f"  run {n + 1}: followed ({100 * share:.0f}%)")
finally:
    for p in busy:
        p.kill()
print(f"\n{dead} of {alive + dead} flings the words did not follow")
