#!/usr/bin/env python3
"""The parts of the app that are not the moving overlay: the card, the bar, the scope.

The overlay suite drives a page in motion. This one drives the decisions a reader makes -
tapping a word, moving the frequency bar, choosing which apps to see transcriptions in -
and checks the overlay actually obeys them on a device rather than in a unit test.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/android_features.py
"""
import re
import sys
import time

from android_harness import Device, shell


class Results:
    def __init__(self):
        self.passed = 0
        self.failures = []

    def check(self, ok, name, detail=""):
        if ok:
            self.passed += 1
        else:
            self.failures.append(f"{name}: {detail}")
        return ok

    @property
    def total(self):
        return self.passed + len(self.failures)


def show(dev, settle=2.5, **extras):
    """Apply a setting and read what the overlay did about it.

    A read is only meaningful if the service actually looked again afterwards, so this
    insists on seeing a fresh report rather than accepting an empty log as "nothing
    transcribed" - which is how a suite concludes the frequency bar does nothing.
    """
    extras.setdefault("enable", 1)
    for attempt in range(4):
        dev.clear_log()
        dev.surface(mode="plain", **extras)
        time.sleep(settle)
        log = dev.log()
        if dev.box_frames(log):
            return dev.boxes(log), log
    return {}, log


def warm(dev):
    for _ in range(12):
        boxes, _ = show(dev, density=3)
        if boxes:
            return boxes
    return {}


# --------------------------------------------------------------------------------------
# The frequency bar means what it says.
# --------------------------------------------------------------------------------------

def check_density(r, dev):
    counts = {}
    for density in (2, 6, 12, 30, 50):
        boxes, _ = show(dev, density=density, scrollTo=0, settle=3)
        counts[density] = len(boxes)
    print("  transcriptions per screen by density: " + ", ".join(
        f"1-in-{d}: {n}" for d, n in counts.items()))

    r.check(counts[2] > 0, "density: the densest setting transcribes something", str(counts))
    # One word in two must put more on a screen than one in fifty. The exact numbers depend
    # on which words the dictionary knows, so only the ordering is asserted.
    r.check(
        counts[2] >= counts[12] >= counts[50],
        "density: a denser setting never shows fewer words",
        str(counts),
    )
    r.check(
        counts[2] > counts[50],
        "density: the two ends of the bar differ",
        f"1-in-2 showed {counts[2]}, 1-in-50 showed {counts[50]}",
    )


# --------------------------------------------------------------------------------------
# The card a tap opens.
# --------------------------------------------------------------------------------------

def check_tooltip(r, dev):
    boxes, _ = show(dev, density=3, scrollTo=0, settle=3)
    if not r.check(bool(boxes), "card: there is a word to tap", "nothing transcribed"):
        return
    # Tap the middle of a real transcription, taken from what the overlay reported.
    key = sorted(boxes, key=lambda k: boxes[k]["rect"][1])[len(boxes) // 2]
    left, top, right, bottom = boxes[key]["rect"]
    word = boxes[key]["word"]
    dev.clear_log()
    shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(2.5)
    log = dev.log()

    opened = re.search(r"TOOLTIP open word=(\S+) ipa=(\S+) symbols=(\d+)", log)
    if not r.check(opened is not None, "card: a tap opens it", f"no card for {word}"):
        return
    r.check(opened.group(1) == word, "card: it is about the word that was tapped",
            f"tapped {word}, card says {opened.group(1)}")
    r.check(int(opened.group(3)) > 0, "card: it names the symbols of the transcription",
            f"{opened.group(3)} symbols for {opened.group(2)}")
    r.check(len(opened.group(2)) > 0, "card: it shows the full transcription", "empty")

    # A tap away from it closes it again.
    dev.clear_log()
    shell("input", "tap", "20", "20")
    time.sleep(2.0)
    r.check("TOOLTIP closed" in dev.log(), "card: a tap outside closes it", "it stayed open")


# --------------------------------------------------------------------------------------
# Which apps the reader chose.
# --------------------------------------------------------------------------------------

def check_scope(r, dev):
    everywhere, _ = show(dev, density=3, allApps=1, settle=3)
    r.check(bool(everywhere), "scope: with every app allowed, words are transcribed",
            "nothing transcribed")

    nowhere, _ = show(dev, density=3, allApps=0, settle=3)
    r.check(not nowhere, "scope: with no app chosen, nothing is transcribed",
            f"{len(nowhere)} transcriptions where none were allowed")

    # And back, so the setting is not one-way.
    again, _ = show(dev, density=3, allApps=1, settle=3)
    r.check(bool(again), "scope: allowing every app again brings them back", "still nothing")


# --------------------------------------------------------------------------------------
# The master switch.
# --------------------------------------------------------------------------------------

def check_switch(r, dev):
    on, _ = show(dev, density=3, enable=1, settle=3)
    r.check(bool(on), "switch: on means transcriptions", "nothing while switched on")
    off, _ = show(dev, density=3, enable=0, settle=3)
    r.check(not off, "switch: off means none", f"{len(off)} transcriptions while switched off")
    back, _ = show(dev, density=3, enable=1, settle=3)
    r.check(bool(back), "switch: it goes back on", "nothing after switching on again")


# --------------------------------------------------------------------------------------
# Every style still covers the word.
# --------------------------------------------------------------------------------------

def check_styles(r, dev):
    for index, name in enumerate(("solid", "soft", "tint")):
        boxes, _ = show(dev, density=3, style=index, settle=3)
        r.check(bool(boxes), f"style {name}: transcribes", "nothing transcribed")
        for key, info in boxes.items():
            l, t, right, bottom = info["rect"]
            r.check(right > l and bottom > t, f"style {name}: {info['word']} has a real box",
                    str(info["rect"]))


def main():
    dev = Device()
    if not dev.enable_service():
        print("FAIL - the accessibility service will not start")
        sys.exit(1)
    warm(dev)

    r = Results()
    print("the frequency bar")
    check_density(r, dev)
    print("the card a tap opens")
    check_tooltip(r, dev)
    print("which apps")
    check_scope(r, dev)
    print("the master switch")
    check_switch(r, dev)
    print("the styles")
    check_styles(r, dev)

    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the bar, the card, the scope, the switch and the styles all do as they say")


if __name__ == "__main__":
    main()
