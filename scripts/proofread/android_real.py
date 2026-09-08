#!/usr/bin/env python3
"""Is a transcription on its word, in an app nobody wrote for this test?

Every other check here uses a page that reports where its own lines are, which means the
overlay and the judge are reading the same app, in the same process, with the page's
cooperation. A reader's apps do not cooperate, and the fault they see does not appear in a
fixture: photographed on the settings app, the transcription of "apps" was left standing over
"Notification history, conversations" after "apps" had scrolled away.

So the ground truth here comes from somewhere the overlay has nothing to do with: uiautomator
dumps the accessibility tree itself, independently, and says what text is on the screen and
where. What the service says it drew is read from its own log. A transcription is right when
the word it names is where it was drawn, and wrong when that word is somewhere else or is not
on the screen at all.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/android_real.py
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from android_harness import Device, adb, shell

# The apps to read. Each is on the device already and none of them knows about this.
APPS = [
    ("the settings app", "com.android.settings/.Settings"),
    ("its list of apps", "com.android.settings/.Settings$ManageApplicationsActivity"),
]
# How far a transcription may sit from the word it names before it is somebody else's.
A_LINE = 55


def tree():
    """Every piece of text on the screen and where it is, read without the app's help."""
    adb("shell", "uiautomator", "dump", "/sdcard/win.xml", timeout=60)
    xml = adb("shell", "cat", "/sdcard/win.xml", timeout=60)
    start = xml.find("<?xml")
    if start < 0:
        return []
    out = []
    try:
        root = ET.fromstring(xml[start:])
    except ET.ParseError:
        return []
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        if not text:
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
        if not m:
            continue
        left, top, right, bottom = (int(x) for x in m.groups())
        out.append((text, left, top, right, bottom))
    return out


def words_on_screen(nodes):
    """Which words are on the screen, and the vertical middles of every place they appear."""
    where = {}
    for text, _l, top, _r, bottom in nodes:
        middle = (top + bottom) / 2
        for word in re.findall(r"[^\W\d_]+", text.lower()):
            where.setdefault(word, []).append(middle)
    return where


def judge(dev, label, results, pkg):
    nodes = tree()
    if len(nodes) < 3:
        print(f"  {label}: the tree said nothing, skipped")
        return
    seen = words_on_screen(nodes)
    # The whole log, not a cleared one: a service watching a screen that is not moving says
    # nothing, so clearing the log and waiting reads an empty log rather than a quiet overlay.
    # The last reading of this app is what is on the screen now.
    log = "\n".join(l for l in dev.log().splitlines() if f"BOXES " not in l or pkg in l)
    frames = [f for f in dev.box_frames(log) if f[1]]
    if not frames:
        print(f"  {label}: the overlay has drawn nothing on this app")
        return
    _stamp, boxes = frames[-1]
    adrift, gone, checked = [], [], 0
    for box in boxes.values():
        word = box["word"].lower()
        middle = (box["rect"][1] + box["rect"][3]) / 2
        checked += 1
        if word not in seen:
            gone.append(box["word"])
            continue
        if min(abs(middle - m) for m in seen[word]) > A_LINE:
            adrift.append(box["word"])
    wrong = len(adrift) + len(gone)
    print(f"  {label}: {wrong} of {checked} are not on their word"
          f"{f', {len(gone)} name a word that is not on the screen at all' if gone else ''}"
          f"{f' (e.g. {gone[:3]})' if gone else ''}"
          f"{f' and {len(adrift)} are on other text (e.g. {adrift[:3]})' if adrift else ''}")
    results.append((label, wrong, checked))


def main():
    dev = Device()
    if not dev.enable_service():
        print("the service will not start")
        return 1
    results = []
    for name, activity in APPS:
        print(f"{name}:")
        pkg = activity.split("/")[0]
        shell("am", "start", "-n", activity)
        time.sleep(5)
        judge(dev, "standing still", results, pkg)
        # A real gesture: a finger that lifts, so the app flings on after it.
        shell("input", "swipe", "540", "1500", "540", "600", "250")
        time.sleep(0.4)
        judge(dev, "just after a fling", results, pkg)
        time.sleep(3)
        judge(dev, "once it has settled", results, pkg)
    bad = [(l, w, c) for l, w, c in results if w]
    print(f"\n{len(results) - len(bad)}/{len(results)} moments had every transcription on its word")
    if bad:
        print("\nFAIL:")
        for label, wrong, checked in bad:
            print(f"    {label}: {wrong} of {checked}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
