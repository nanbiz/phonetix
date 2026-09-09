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

from android_harness import SERIAL, Device, adb, shell

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
    # A real article in a real browser. Everything above is either a framework list on a
    # near-empty screen or a page of ours, and neither is the shape of an app a reader
    # actually reads in: this is a hundred and twenty pieces of text in a tree the overlay
    # has no cooperation from, rendered by a process that is doing its own work, which is
    # what makes its answers slow enough to be worth measuring against.
    ("an article in the browser", "com.android.chrome/#article"),
]
# Where an app has to be pointed at something rather than merely started.
OPENS = {
    "com.android.chrome/#article": "https://en.wikipedia.org/wiki/Phonetics",
}
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
# How near a drawn frame has to be to count as saying what was on the screen at that moment.
# Wider than a frame, because the tree takes a moment to read; far narrower than FRESH_MS,
# because a frame from the previous fling says nothing about a page standing still now.
LAYER_FRESH_MS = 700


_screen = [0]


def screen_height():
    """How tall the screen is, so a word carried past its edge can be told from one on it."""
    if not _screen[0]:
        out = shell("wm", "size")
        m = re.search(r"(\d+)x(\d+)", out)
        _screen[0] = int(m.group(2)) if m else 1920
    return _screen[0]


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


def drawn_at(log, when):
    """Whether the layer was actually showing anything at that moment, and where it had
    carried the words to.

    Counting only the transcriptions that were drawn answers "is a transcription on the wrong
    word" and cannot answer "is there a transcription at all". A layer that takes itself off
    the screen for the whole of a fling scores a clean nothing-wrong while showing a reader
    nothing, which is indistinguishable from working and is what a page that does not follow
    actually looks like. The frame nearest the moment says which of the two happened.

    A page standing still has no layer at all - the words are back in their own small windows,
    which is a different thing being on the screen - so only a frame from about this moment
    counts. Without that bound the last frame of the previous fling answers for a still page.
    """
    best = None
    for line in log.splitlines():
        m = re.search(r"LAYER (\d+) (-?[\d.]+) (\d+) showing=(\d)", line)
        if not m:
            continue
        stamp = int(m.group(1))
        if abs(stamp - when) > LAYER_FRESH_MS:
            continue
        if best is None or abs(stamp - when) < abs(best[0] - when):
            # The reading this frame was drawn from is named in the frame, because the carry
            # is measured from that reading's positions and means nothing against any other.
            best = (stamp, float(m.group(2)), m.group(4) == "1", int(m.group(3)))
    return best


def judge(dev, label, results, pkg, covers=None):
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
    # What a reader had in front of them, counted whether or not anything was there. A moment
    # with no transcriptions on it is the failure this suite could not see: it used to return
    # here and leave the moment out of the score entirely, so a fling the overlay sat out
    # scored the same as one it followed.
    showing = drawn_at(log, when)
    blank = not frames or (showing is not None and not showing[2])
    if covers is not None:
        covers.append((label, 0 if blank else 1))
    if blank:
        why = ("the overlay drew nothing on this app" if not frames
               else "the layer had taken the words off the screen")
        print(f"  {label}: {why}, so nothing was on the screen to be right or wrong")
        return
    # The reading the drawn frame was drawn from, rather than merely the newest one: the carry
    # below is a distance from that reading's positions.
    drew_from = showing[3] if showing is not None else None
    if drew_from is not None:
        paired = [f for f in frames if f[0] == drew_from]
        if paired:
            frames = paired
    _stamp, boxes = frames[-1]
    # Where the words were drawn, not where they were read.
    #
    # A reading says where a line was when it was read; the layer then carries the words on
    # from there, and on a page being flung that is hundreds of pixels. Judging the reading's
    # positions judges a screen nobody saw - and it judged them as correct exactly when the
    # layer was carrying them wrongly, which is the failure this suite exists to catch.
    carried = showing[1] if showing is not None else 0.0
    adrift, gone, offscreen, checked = [], [], 0, 0
    for box in boxes.values():
        word = box["word"].lower()
        middle = (box["rect"][1] + box["rect"][3]) / 2 + carried
        # A word carried off the screen has gone there with its own text, which is where it
        # belongs. It is not on the screen to be right or wrong about, so it is neither
        # counted nor forgiven: only what a reader can see is judged.
        if middle < 0 or middle > screen_height():
            offscreen += 1
            continue
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
            near = min(seen[word], key=lambda b: min(abs(middle - b[0]), abs(middle - b[1])))
            adrift.append((box["word"], int(middle), near, int(off)))
    wrong = len(adrift) + len(gone)
    for word, drawn, (top, bottom), off in adrift:
        print(f"      {word}: drawn at y={drawn}, its word is in {top}..{bottom}, {off}px out")
    print(f"  {label}: {wrong} of {checked} are not on their word"
          f"{f' ({offscreen} were carried off the screen)' if offscreen else ''}"
          f"{f', {len(gone)} name a word that is not on the screen at all' if gone else ''}"
          f"{f' (e.g. {gone[:3]})' if gone else ''}"
          f"{f' and {len(adrift)} are on other text' if adrift else ''}")
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
    # How many lines a read may measure while the page is moving. Left settable because it is
    # the difference between a browser drawing nothing and a browser drawing its words.
    moving = os.environ.get("PHONETIX_MEASURE_MOVING")
    if said is not None:
        shell("am", "start", "-n", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity",
              "--es", "mode", "plain", "--ei", "enable", "1", "--ei", "allApps", "1",
              "--ei", "useSaidScroll", said)
        time.sleep(2)
        print(f"(control: the app's own scroll deltas are {'used' if said != '0' else 'ignored'})")
    # Busy work on the device, because the fault this is looking for is a starvation fault.
    #
    # Through a fling the overlay has to read the app faster than the page moves, and when it
    # cannot, every line it was following leaves the screen before the next reading. On an idle
    # emulator that never happens and every run is clean; the failures that were photographed
    # appeared while the machine was loaded. A phone running a real app is the loaded case, so
    # the load is part of the test rather than a thing to wait out.
    busy = []
    for _ in range(int(os.environ.get("PHONETIX_LOAD", "0"))):
        busy.append(subprocess.Popen(
            ["adb", "-s", SERIAL, "shell", "while true; do echo -n; done"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    if busy:
        print(f"(the device is running {len(busy)} busy loops while this is measured)")
    results = []
    covers = []
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
        extras = list(EXTRAS.get(activity, []))
        # Our own page is force stopped above, which resets the statics these controls set, so
        # they go in with the launch rather than once at the start. A control that is quietly
        # dropped for two of the four pages is how a run reported fourteen clean looks while
        # every transcription was supposed to be drawn 250px from its word.
        if pkg == "io.github.nanbiz.phonetix":
            if wrong_by is not None:
                extras += ["--ei", "putThemWrongBy", wrong_by]
            if said is not None:
                extras += ["--ei", "useSaidScroll", said]
            if moving is not None:
                extras += ["--ei", "measureMovingMax", moving]
        opens = OPENS.get(activity)
        if opens:
            shell("am", "force-stop", pkg)
            time.sleep(1)
            shell("am", "start", "-a", "android.intent.action.VIEW", "-d", opens)
            # A page has to arrive over the network before there is anything to read.
            time.sleep(10)
        else:
            shell("am", "start", "-n", activity.split("#")[0], *extras)
        time.sleep(2)
        if pkg == "io.github.nanbiz.phonetix":
            dev.enable_service()
        time.sleep(4)
        judge(dev, "standing still", results, pkg, covers)
        # A real gesture: a finger that lifts, so the app flings on after it.
        # A fling is over in well under a second and one dump of the tree takes about that
        # long, so a single look is one moment with a handful of words in it, which decides
        # nothing. Several flings, each looked at a different distance into it, is a
        # distribution rather than an anecdote.
        for n, delay in enumerate((0.30, 0.55, 0.85, 0.30, 0.55, 0.85)):
            shell("input", "swipe", "540", "1500", "540", "600", "250")
            time.sleep(delay)
            judge(dev, f"{int(delay * 1000)}ms into a fling", results, pkg, covers)
            time.sleep(2.5)
            if n % 3 == 2:
                shell("input", "swipe", "540", "600", "540", "1500", "250")
                time.sleep(2)
        judge(dev, "once it has settled", results, pkg, covers)
    # Reported per moment, not pooled. A look 300ms into a fling and one at 850ms are
    # different questions - one catches the page at speed, the other after it has settled -
    # and adding them together gave the same build 0 of 679 one run and 232 of 1124 the next,
    # which made every comparison meaningless.
    by_moment = {}
    for label, wrong, checked in results:
        moment = label.strip()
        got = by_moment.setdefault(moment, [0, 0, 0])
        got[0] += wrong
        got[1] += checked
        got[2] += 1
    print()
    total = 0
    for moment, (wrong, checked, looks) in by_moment.items():
        share = f"{100 * wrong / checked:.0f}%" if checked else "-"
        print(f"  {moment:26} {wrong:4} of {checked:5} ({share:>4}) over {looks} looks")
        total += wrong
    checked = sum(c for _l, _w, c in results)
    print(f"  {'every moment':26} {total:4} of {checked:5}")
    # And separately, how often there was anything on the screen at all. A moment with no
    # transcriptions on it cannot have a wrong one, so it scores perfectly above; here it
    # scores as what it is.
    print()
    blank_moments = {}
    for label, shown in covers:
        got = blank_moments.setdefault(label.strip(), [0, 0])
        got[0] += 1 - shown
        got[1] += 1
    blank_total = sum(b for b, _n in blank_moments.values())
    for moment, (blank, looks) in blank_moments.items():
        print(f"  {moment:26} {blank:4} of {looks:5} looks had nothing on the screen")
    print(f"  {'every moment':26} {blank_total:4} of {len(covers):5} looks had nothing "
          f"on the screen")
    for p in busy:
        p.kill()
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
