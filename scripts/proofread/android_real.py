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
import os
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
    # A Compose list, which is the shape of the app this was reported broken on. It is one of
    # ours, but nothing about the judging uses that: the tree is read by uiautomator like any
    # other app's. What it brings is the case a framework list does not have, an app that
    # reports a scroll delta of one whatever it did, so the overlay has to follow it without
    # being told anything.
    ("a Compose conversation", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity"),
    # A conversation being added to: a further message arrives every three seconds, so rows
    # move to make room for it while the overlay is carrying words that belong to them. In
    # bursts rather than continuously, because a page that never stops changing cannot be held
    # against a reading of the screen that takes a second to take.
    ("a conversation being added to",
     "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity#growing"),
]
# Deliberately not here: a page whose text changes as fast as it can be read.
#
# That is the case the fault was described as, a chat with words showing up from below, and
# this instrument cannot judge it. Reading the screen takes about a second, and a page writing
# into itself has moved on by the time the reading is finished, so the tree and the overlay
# describe two different screens and every difference between them reads as a fault. Pointed
# at one, this reported sixteen of sixteen wrong on a page that was standing still.
#
# Judging it needs either a reading of the screen fast enough to be simultaneous, which
# uiautomator is not, or a page that arrives in bursts and is still between them, which is
# closer to what a chat does anyway. Neither is done yet.
# What each app needs before it can be read, if anything.
EXTRAS = {
    "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity": [
        "--es", "mode", "chat", "--ei", "enable", "1",
        "--ei", "density", "3", "--ei", "allApps", "1"],
    "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity#growing": [
        "--es", "mode", "chat", "--ei", "enable", "1", "--ei", "density", "3",
        "--ei", "allApps", "1", "--ei", "growEvery", "3000"],
}
# How far the middle of a transcription may sit outside the node whose text holds its word.
#
# The middle rather than the whole box, because a transcription is drawn with a bleed around
# the word and is taller than the letters, so demanding containment fails on every correctly
# placed one. The node's own box rather than that box plus a line, because a row of a list is
# already twice the height of its text and the extra line would forgive a transcription
# sitting on the row above.
SLACK = 8
# How near in time the overlay's reading has to be to the reading of the screen. Without this
# the last reading of an app is used whatever its age, so a page untouched for minutes can be
# judged against a screen it never described.
FRESH_MS = 3000


def uptime_ms():
    """The clock the service stamps its readings with."""
    out = shell("cat", "/proc/uptime").strip().split()
    return int(float(out[0]) * 1000) if out else 0


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
    """Which words are on the screen, and the band each place they appear sits in.

    A band rather than a point, because a node is not always one line. A row of a list is, and
    a message that wraps over three is not: its text is one node whose box covers all three, so
    the most that can be said about a word inside it is that it is somewhere in that box. Held
    to a point instead, every word of every wrapped message reads as half a message out of
    place, which is an accusation the reading cannot support.
    """
    where = {}
    for text, _l, top, _r, bottom in nodes:
        for word in re.findall(r"[^\W\d_]+", text.lower()):
            where.setdefault(word, []).append((top, bottom))
    return where


def judge(dev, label, results, pkg):
    when = uptime_ms()
    nodes = tree()
    if len(nodes) < 3:
        print(f"  {label}: the tree said nothing, skipped")
        return
    seen = words_on_screen(nodes)
    # The whole log, not a cleared one: a service watching a screen that is not moving says
    # nothing, so clearing the log and waiting reads an empty log rather than a quiet overlay.
    # The last reading of this app is what is on the screen now.
    log = "\n".join(l for l in dev.log().splitlines() if f"BOXES " not in l or pkg in l)
    frames = [f for f in dev.box_frames(log) if f[1] and abs(f[0] - when) <= FRESH_MS]
    if not frames:
        print(f"  {label}: the overlay drew nothing on this app within "
              f"{FRESH_MS}ms of the screen being read")
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
        # The transcription has to sit inside the node whose text holds its word. That is the
        # tightest thing this reading can honestly ask: it cannot say where a word is inside a
        # wrapped message, but it can say the transcription is not in that message at all.
        # Allowing a line of slack on top of the node's own box is too generous for a list
        # row, whose box is already twice the height of its text, and would forgive a
        # transcription sitting a row away.
        off = min(
            0.0 if top - SLACK <= middle <= bottom + SLACK
            else min(abs(middle - top), abs(middle - bottom))
            for top, bottom in seen[word]
        )
        if off > 0:
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
    # A control, so a green run can be shown to mean something. Setting this to 0 turns off
    # the one signal a framework list gives for free, which is the fault this test was written
    # to catch, and the run has to go red. A test that cannot be made to fail is not evidence.
    # The plainest control there is: every transcription is drawn a fixed distance from where
    # it belongs. A test that stays green through that is not reading the screen at all.
    wrong_by = os.environ.get("PHONETIX_WRONG_BY")
    if wrong_by is not None:
        shell("am", "start", "-n", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity",
              "--es", "mode", "plain", "--ei", "enable", "1", "--ei", "allApps", "1",
              "--ei", "putThemWrongBy", wrong_by)
        time.sleep(2)
        print(f"(control: every transcription is drawn {wrong_by}px from its word)")
    said = os.environ.get("PHONETIX_SAID")
    if said is not None:
        shell("am", "start", "-n", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity",
              "--es", "mode", "plain", "--ei", "enable", "1", "--ei", "allApps", "1",
              "--ei", "useSaidScroll", said)
        time.sleep(2)
        print(f"(control: the app's own scroll deltas are {'used' if said != '0' else 'ignored'})")
    results = []
    for name, activity in APPS:
        print(f"{name}:")
        pkg = activity.split("/")[0]
        # A fresh process for our own page, because the activity is already there from the
        # look before and a second intent only reaches onNewIntent: the Compose content is not
        # built again, so anything that shapes it is silently dropped and the look tests the
        # page from last time. Force stopping takes the service with it, so it is turned back
        # on afterwards.
        if pkg == "io.github.nanbiz.phonetix":
            shell("am", "force-stop", pkg)
            time.sleep(1.5)
        shell("am", "start", "-n", activity.split("#")[0], *EXTRAS.get(activity, []))
        time.sleep(2)
        if pkg == "io.github.nanbiz.phonetix":
            dev.enable_service()
        time.sleep(4)
        judge(dev, "standing still", results, pkg)
        # A real gesture: a finger that lifts, so the app flings on after it.
        # A fling is over in well under a second and one dump of the tree takes about that
        # long, so a single look is one moment with a handful of words in it, which decides
        # nothing. Several flings, each looked at a different distance into it, is a
        # distribution rather than an anecdote.
        for n, delay in enumerate((0.30, 0.55, 0.85, 0.30, 0.55, 0.85)):
            shell("input", "swipe", "540", "1500", "540", "600", "250")
            time.sleep(delay)
            judge(dev, f"{int(delay * 1000)}ms into a fling", results, pkg)
            time.sleep(2.5)
            if n % 3 == 2:
                shell("input", "swipe", "540", "600", "540", "1500", "250")
                time.sleep(2)
        judge(dev, "once it has settled", results, pkg)
    wrong = sum(w for _l, w, _c in results)
    checked = sum(c for _l, _w, c in results)
    clean = sum(1 for _l, w, _c in results if not w)
    print(f"\n{wrong} of {checked} transcriptions were not on their word, "
          f"over {len(results)} looks, {clean} of which were clean")
    return 1 if wrong else 0


if __name__ == "__main__":
    sys.exit(main())
