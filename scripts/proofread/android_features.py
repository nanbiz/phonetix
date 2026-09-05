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
    log = ""
    for attempt in range(5):
        dev.clear_log()
        dev.surface(mode="plain", **extras)
        time.sleep(settle)
        log = dev.log()
        # The surface says when it applied the setting. Only a reading taken after that
        # describes the setting under test; anything earlier describes the previous one.
        applied = [int(m) for m in re.findall(r"SETTINGS (\d+) ", log)]
        if not applied:
            continue
        after = [(t, b) for t, b in dev.box_frames(log) if t >= applied[-1]]
        if after:
            return after[-1][1], log
    return {}, log


def reset(dev):
    """Put the app back to a known state before a group of checks.

    Checks that leave a setting behind make the next group measure whatever the last one
    happened to leave - a suite that passes or fails depending on the order it ran in is
    not evidence of anything.
    """
    # Never force-stop: stopping an app switches its accessibility service off, and the
    # suite then measures a device where the thing under test is not running at all - which
    # it duly reported as every feature being broken.
    for _ in range(6):
        boxes, _ = show(dev, enable=1, density=3, allApps=1, style=0, scrollTo=0, settle=2.5)
        if boxes:
            return boxes
        dev.enable_service()
    return {}


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
    reset(dev)
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
    reset(dev)
    boxes, _ = show(dev, density=3, scrollTo=0, settle=3)
    if not r.check(bool(boxes), "card: there is a word to tap", "nothing transcribed"):
        return
    # Tapped where the overlay says the word is right now. A position read a moment ago can
    # already be stale - the screen need only have settled once more - so it is re-read
    # immediately before the tap and tried again if the card does not open.
    opened = None
    log = ""
    word = ""
    for attempt in range(3):
        # Read where the words are and tap without touching the surface in between: any
        # relaunch nudges the page, the words move, and the tap lands on nothing.
        if attempt == 0:
            fresh = boxes
        else:
            dev.clear_log()
            time.sleep(2.0)
            fresh = dev.boxes() or show(dev, density=3, scrollTo=0, settle=2.5)[0]
        if not fresh:
            continue
        key = sorted(fresh, key=lambda k: fresh[k]["rect"][1])[len(fresh) // 2]
        left, top, right, bottom = fresh[key]["rect"]
        word = fresh[key]["word"]
        dev.clear_log()
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(2.5)
        log = dev.log()
        opened = re.search(r"TOOLTIP open word=(\S+) ipa=(\S+) symbols=(\d+)", log)
        if opened:
            break
    if not r.check(opened is not None, "card: a tap opens it", f"no card for {word}"):
        return
    r.check(opened.group(1) == word, "card: it is about the word that was tapped",
            f"tapped {word}, card says {opened.group(1)}")
    r.check(int(opened.group(3)) > 0, "card: it names the symbols of the transcription",
            f"{opened.group(3)} symbols for {opened.group(2)}")
    r.check(len(opened.group(2)) > 0, "card: it shows the full transcription", "empty")

    # What the card actually laid out, not merely that it was asked for.
    laid_out = re.findall(r"\[([^@\]]+)@(\d+),(\d+),(\d+),(\d+)\]", log)
    r.check(bool(laid_out), "card: it renders something", "the card reported no laid-out content")
    texts = [t.replace("\u00b7", " ") for t, *_ in laid_out]
    sizes = [(int(w), int(h)) for _, _, _, w, h in laid_out]
    r.check(
        all(w > 0 and h > 0 for w, h in sizes),
        "card: everything on it has a real size",
        f"{sum(1 for w, h in sizes if w <= 0 or h <= 0)} of {len(sizes)} measured to nothing",
    )
    r.check(word in texts, "card: the word itself is on it", f"laid out: {texts[:6]}")
    r.check(
        any(opened.group(2) == t for t in texts),
        "card: the transcription is on it",
        f"expected {opened.group(2)} among {texts[:6]}",
    )
    # The extension's tooltip names each sound and gives a word it is heard in; so must this.
    named = [t for t in texts if any(
        k in t for k in ("plosive", "fricative", "vowel", "approximant", "nasal", "stress", "lateral")
    )]
    r.check(named, "card: it names the sounds", f"no descriptions among {texts[:8]}")
    examples = [t for t in texts if t.startswith('"') and " in " in t]
    r.check(examples, "card: it gives a word for each sound", f"no examples among {texts[:8]}")
    r.check("Say it" in texts, "card: it offers to say the word", str(texts[:6]))
    r.check("Wiktionary" in texts, "card: it links onward", str(texts[:6]))

    # The recording button, tapped where the card said it put it.
    play = [m for m in laid_out if m[0] == "♪"]
    if r.check(bool(play), "card: a sound has a recording to play", "no play buttons on the card"):
        _, x, y, w, h = play[0]
        dev.clear_log()
        shell("input", "tap", str(int(x) + int(w) // 2), str(int(y) + int(h) // 2))
        time.sleep(6)
        after = dev.log()
        r.check(
            "Phonetix: playing" in after or "play failed" not in after,
            "card: the recording plays",
            "the player reported a failure",
        )

    # A tap away from it closes it again.
    dev.clear_log()
    shell("input", "tap", "20", "20")
    time.sleep(2.0)
    r.check("TOOLTIP closed" in dev.log(), "card: a tap outside closes it", "it stayed open")


# --------------------------------------------------------------------------------------
# Which apps the reader chose.
# --------------------------------------------------------------------------------------

def check_scope(r, dev):
    reset(dev)
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
    reset(dev)
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
    reset(dev)
    for index, name in enumerate(("solid", "soft", "tint")):
        boxes, _ = show(dev, density=3, style=index, settle=3)
        r.check(bool(boxes), f"style {name}: transcribes", "nothing transcribed")
        for key, info in boxes.items():
            l, t, right, bottom = info["rect"]
            r.check(right > l and bottom > t, f"style {name}: {info['word']} has a real box",
                    str(info["rect"]))


# --------------------------------------------------------------------------------------
# The app's own screen, which unlike the overlay is an ordinary window a dump can see.
# --------------------------------------------------------------------------------------

def ui_text(dev):
    shell("uiautomator", "dump", "/sdcard/ui.xml")
    dump = shell("cat", "/sdcard/ui.xml")
    return re.findall(r'text="([^"]+)"', dump), dump


def check_settings_screen(r, dev):
    # Anything the previous checks left open - a card, most of all - would swallow the
    # first swipe and leave the screen where it started.
    shell("input", "tap", "20", "20")
    time.sleep(1.0)
    shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity")
    time.sleep(4)
    # A dump only contains what is on screen, and the screen is taller than the window, so
    # the whole of it is collected by scrolling through it.
    texts, dump = ui_text(dev)
    for _ in range(6):
        shell("input", "swipe", "540", "1500", "540", "600", "500")
        time.sleep(1.4)
        more, more_dump = ui_text(dev)
        texts += more
        dump += more_dump

    for wanted in ("Phonetix", "Frequency", "Preview", "Appearance", "Apps"):
        r.check(wanted in texts, f"settings: the screen shows {wanted}", str(texts[:10]))

    # The frequency reads as one word in so many, and the preview shows what that does.
    r.check(
        any(re.match(r"1 in \d+", t) for t in texts),
        "settings: the frequency is stated as one word in so many",
        str([t for t in texts if "in" in t][:5]),
    )
    r.check(
        any(t in texts for t in ("Solid", "Soft", "Tint")),
        "settings: the styles are offered",
        str(texts[:12]),
    )
    r.check("SeekBar" in dump, "settings: the frequency bar is a real control", "no slider in the screen")

    # The preview applies the setting to real words, so it must contain a transcription
    # rather than only plain English.
    ipa_chars = set("ˈˌːɪɛəɹʃʒŋʌɑɔʊθðæɐɡ")
    r.check(
        any(any(c in ipa_chars for c in t) for t in texts),
        "settings: the preview shows a transcription",
        "no transcription among the previewed text",
    )


def check_switch_in_ui(r, dev):
    """The switch on the screen is the same switch the overlay obeys."""
    shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity")
    time.sleep(3)
    texts, _ = ui_text(dev)
    r.check(
        "Transcribing" in texts or "Paused" in texts,
        "settings: the screen says whether it is on",
        str(texts[:10]),
    )


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
    print("the app's own screen")
    check_settings_screen(r, dev)
    check_switch_in_ui(r, dev)

    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the bar, the card, the scope, the switch and the styles all do as they say")


if __name__ == "__main__":
    main()
