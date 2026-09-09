"""How much of a web page carries a transcription.

A browser hands its page over only while it is being touched and takes it back when it is
left alone, so the one moment its words can be measured is the moment it is moving - which is
the moment the read used to refuse to measure anything. This loads an article, flings it, and
counts the words drawn while it moves and after it stops, against the words uiautomator says
are on the screen.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/android_browser.py 0 4
"""
import os
import re
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, adb, shell

PAGE = "https://en.wikipedia.org/wiki/Phonetics"
ROUNDS = int(os.environ.get("PHONETIX_ROUNDS", "5"))


def on_screen():
    adb("shell", "uiautomator", "dump", "/sdcard/b.xml", timeout=60)
    xml = adb("shell", "cat", "/sdcard/b.xml", timeout=60)
    start = xml.find("<?xml")
    if start < 0:
        return 0
    try:
        root = ET.fromstring(xml[start:])
    except ET.ParseError:
        return 0
    words = set()
    for node in root.iter("node"):
        for word in re.findall(r"[^\W\d_]{3,}", (node.get("text") or "").lower()):
            words.add(word)
    return len(words)


def drawn(dev):
    """The words in the newest set the overlay reported for the browser."""
    frames = [f for f in dev.box_frames() if f[1]]
    if not frames:
        return 0
    return len({b["word"] for b in frames[-1][1].values()})


def main():
    caps = [int(a) for a in sys.argv[1:]] or [0]
    dev = Device()
    if not dev.enable_service():
        print("the service will not start")
        return 1
    for cap in caps:
        got = []
        for _ in range(ROUNDS):
            # A fresh process for our own page, so the cap set on this launch is the one in
            # force: the statics it writes live in the service's process.
            shell("am", "force-stop", "io.github.nanbiz.phonetix")
            time.sleep(1.5)
            shell("am", "start", "-n", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity",
                  "--ei", "measureMovingMax", str(cap), "--ei", "enable", "1")
            time.sleep(1.5)
            dev.enable_service()
            time.sleep(2)
            shell("am", "force-stop", "com.android.chrome")
            time.sleep(1)
            shell("am", "start", "-a", "android.intent.action.VIEW", "-d", PAGE)
            time.sleep(10)
            shell("logcat", "-c")
            shell("input", "swipe", "540", "1500", "540", "600", "250")
            time.sleep(1.2)
            moving = drawn(dev)
            time.sleep(2.5)
            settled = drawn(dev)
            there = on_screen()
            got.append((moving, settled, there))
            print(f"  cap {cap}: {moving} words drawn during the fling, {settled} after it, "
                  f"of {there} on the screen")
        during = sorted(g[0] for g in got)
        after = sorted(g[1] for g in got)
        print(f"cap {cap}: {during[len(during) // 2]} words during a fling, "
              f"{after[len(after) // 2]} after it (middle of {len(got)})\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
