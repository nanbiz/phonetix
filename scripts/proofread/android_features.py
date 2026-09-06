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


def press(dev, x, y, ms=700):
    """A press held on one spot, which is what opens the card.

    A swipe that goes nowhere: `input tap` is too brief to be a long press, and the card is
    deliberately not on a tap - the windows cover the words themselves, so every touch that
    lands on text lands on one, and opening a card for each would make a page unreadable.
    """
    shell("input", "swipe", str(x), str(y), str(x), str(y), str(ms))


def show(dev, settle=2.5, **extras):
    """Apply a setting and read what the overlay did about it.

    A read is only meaningful if the service actually looked again afterwards, so this
    insists on seeing a fresh report rather than accepting an empty log as "nothing
    transcribed" - which is how a suite concludes the frequency bar does nothing.
    """
    extras.setdefault("enable", 1)
    mode = extras.pop("mode", "plain")
    log = ""
    for attempt in range(5):
        dev.clear_log()
        dev.surface(mode=mode, **extras)
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
        boxes, _ = show(dev, enable=1, density=3, allApps=1, scrollTo=0, settle=2.5)
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
    # The words do not take touches unless the reader has asked them to: they are windows
    # lying over the text, and a window that takes a touch keeps the whole gesture, so with
    # them touchable every swipe that starts on a word is lost. The card is what that setting
    # buys, so it is switched on for these checks and off again after.
    boxes, _ = show(dev, density=3, scrollTo=0, settle=3, touchWords=1)
    if not r.check(bool(boxes), "card: there is a word to press", "nothing transcribed"):
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
            fresh = dev.boxes() or show(dev, density=3, scrollTo=0, settle=2.5, touchWords=1)[0]
        if not fresh:
            continue
        # The longest word on screen: it has the most symbols, so the card is at its
        # fullest and its list is the one most likely to need scrolling.
        key = max(fresh, key=lambda k: len(fresh[k]["word"]))
        left, top, right, bottom = fresh[key]["rect"]
        word = fresh[key]["word"]
        dev.clear_log()
        # Held, not tapped. The card would otherwise open on any touch that landed on text,
        # and on a page of transcriptions that is most of the page.
        press(dev, (left + right) // 2, (top + bottom) // 2)
        time.sleep(2.5)
        log = dev.log()
        opened = re.search(r"TOOLTIP open word=(\S+) ipa=(\S+) symbols=(\d+)", log)
        if opened:
            break
    if not r.check(opened is not None, "card: a press held opens it", f"no card for {word}"):
        return
    r.check(opened.group(1) == word, "card: it is about the word that was pressed",
            f"pressed {word}, card says {opened.group(1)}")
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

    # The card scrolls under the finger. It is an overlay window of the same process as the
    # service, so its own list scrolling arrives as an accessibility event like any app's:
    # the card used to be taken down by the very swipe that was scrolling it.
    where = re.search(r"CARD card@(-?\d+),(-?\d+),(\d+),(\d+) scrollable=(\d+)", log)
    if not r.check(where is not None, "card: it reports where it is", "no card bounds logged"):
        return
    cx, cy, cw, ch, room = (int(g) for g in where.groups())
    dev.clear_log()
    shell("input", "swipe", str(cx + cw // 2), str(cy + int(ch * 0.75)),
          str(cx + cw // 2), str(cy + int(ch * 0.3)), "500")
    time.sleep(2.0)
    scrolled = dev.log()
    r.check("TOOLTIP closed" not in scrolled, "card: scrolling it does not close it",
            "the card went away while being scrolled")
    moved = [int(m) for m in re.findall(r"CARDSCROLL y=(\d+)", scrolled)]
    if room > 0:
        r.check(bool(moved) and max(moved) > 0, "card: it scrolls under the finger",
                f"{room}px of list out of sight, scroll positions reported: {moved[:6]}")
    else:
        r.check(not moved, "card: a list that fits does not scroll", str(moved[:4]))

    # A symbol opens where it stands, keeping the card and where it was scrolled to.
    dev.clear_log()
    fresh_card = re.findall(r"\[([^@\]]+)@(\d+),(\d+),(\d+),(\d+)\]", scrolled)
    rows = fresh_card or laid_out
    names = [m for m in rows if any(
        k in m[0] for k in ("plosive", "fricative", "vowel", "approximant", "nasal", "lateral")
    )]
    if r.check(bool(names), "card: there is a sound to open", "no named sound on the card"):
        _, x, y, w, h = names[0]
        shell("input", "tap", str(int(x) + int(w) // 2), str(int(y) + int(h) // 2))
        time.sleep(2.5)
        opened_row = dev.log()
        r.check("TOOLTIP closed" not in opened_row, "card: opening a sound keeps the card",
                "the card was rebuilt or closed")
        detail = re.findall(r"\[([^@\]]+)@", opened_row)
        r.check(
            any("Read" in t or "See" in t for t in detail),
            "card: an opened sound shows what to read and watch",
            f"laid out after the tap: {detail[:8]}",
        )

    # A tap away from it closes it again.
    dev.clear_log()
    shell("input", "tap", "20", "20")
    time.sleep(2.0)
    r.check("TOOLTIP closed" in dev.log(), "card: a tap outside closes it", "it stayed open")


# --------------------------------------------------------------------------------------
# Which apps the reader chose.
# --------------------------------------------------------------------------------------

    # And a touch that is not held opens nothing. Every transcription is its own window over
    # the word it covers, so a tap opening a card would put one in the way of any reader who
    # touched the text at all. Left to the end: the card is dismissed to run it, and a card
    # that has been dismissed and opened again does not report its layout a second time.
    shell("input", "keyevent", "4")
    time.sleep(1.2)
    dev.clear_log()
    shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(2.0)
    r.check(
        "TOOLTIP open" not in dev.log(),
        "card: a tap does not open it",
        "a plain tap opened the card",
    )

    # And with the words left as they come - a picture, taking no touches - a press held on
    # one opens nothing at all, because the gesture belongs to the app underneath.
    dev.clear_log()
    dev.surface(mode="plain", enable=1, density=3, allApps=1, touchWords=0)
    time.sleep(2.5)
    dev.clear_log()
    press(dev, (left + right) // 2, (top + bottom) // 2)
    time.sleep(2.0)
    r.check(
        "TOOLTIP open" not in dev.log(),
        "card: it stays shut when the words are not touchable",
        "the card opened although the words take no touches",
    )


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
# The colours a transcription is drawn in, which are the colours of the text it replaces.
# --------------------------------------------------------------------------------------

def check_colors(r, dev):
    """Every word gets colours read off the screen, and they are the ones under it.

    A transcription that keeps a palette of ours is a patch: the whole point is that it is
    drawn in the app's own ink on the app's own surface. The debug surface paints its lines
    in colours it names in the log, one line differing from the next, so what the overlay
    reports can be compared against what the app says it drew.
    """
    reset(dev)
    boxes, log = show(dev, mode="colors", density=3, scrollTo=0, settle=4)
    if not r.check(bool(boxes), "colours: there is something to colour", "nothing transcribed"):
        return

    sampled = [b for b in boxes.values() if b["sampled"]]
    r.check(
        len(sampled) == len(boxes),
        "colours: every transcription has colours read off the screen",
        f"{len(boxes) - len(sampled)} of {len(boxes)} fell back to a palette",
    )

    # What the surface says it painted, line by line, so the comparison owes nothing to our
    # own sampling. Matched by where the line is rather than by the words on it: the page
    # repeats its words, and matching by name compares the amber line's "immediately"
    # against a white line's.
    painted = re.findall(
        r"SURFACE ink=#([0-9A-F]{6}) bg=#([0-9A-F]{6}) at=(-?\d+),(-?\d+),(\d+),(\d+) text=(.+)",
        log,
    )
    r.check(bool(painted), "colours: the surface reported what it drew", "no SURFACE lines")
    checked = 0
    for ink_hex, bg_hex, x, y, w, h, text in painted:
        x, y, w, h = int(x), int(y), int(w), int(h)
        on_this_line = [
            b for b in boxes.values()
            if x <= (b["rect"][0] + b["rect"][2]) // 2 <= x + w
            and y <= (b["rect"][1] + b["rect"][3]) // 2 <= y + h
        ]
        if not on_this_line:
            continue
        checked += 1
        first = text.split()[0][:14]
        r.check(
            all(close(b["bg"], int(bg_hex, 16)) for b in on_this_line),
            f"colours: words on '{first}' sit on that line's own surface #{bg_hex}",
            str([hex(b["bg"]) for b in on_this_line]),
        )
        r.check(
            all(close(b["ink"], int(ink_hex, 16), tolerance=90) for b in on_this_line),
            f"colours: words on '{first}' are written in that line's own ink #{ink_hex}",
            str([(b["word"], hex(b["ink"])) for b in on_this_line]),
        )
    r.check(checked >= 2, "colours: more than one differently coloured line was measured",
            f"only {checked} line(s) carried a transcription")

    # Colours survive a scroll: they are carried with the words rather than read again, and
    # a set that loses them mid-scroll flickers into our palette and back.
    dev.clear_log()
    shell("input", "swipe", "540", "1500", "540", "1100", "300")
    time.sleep(2.5)
    after = dev.boxes()
    if after:
        kept = [b for b in after.values() if b["sampled"]]
        r.check(
            len(kept) == len(after),
            "colours: they survive a scroll",
            f"{len(after) - len(kept)} of {len(after)} lost their colours while moving",
        )



def check_unreadable_colors(r, dev):
    """A line whose own colours cannot be read is given the ones where it stands.

    Not every line can be measured. Text over artwork, a word drawn in a tone a shade from
    its surface, a page that will not be captured at all: the sampler gives up on those, and
    what it falls back to has to be a colour the word can be read on. One colour for the
    whole screen is not that. A player with a title over its cover art and a dark half below
    it is mostly dark, so its title got a black patch on a coloured surface - which is what
    the reader saw on their phone.

    Here nothing at all can be read, since the text is drawn in nothing, and four lines stand
    on a colour the rest of the screen does not have.
    """
    reset(dev)
    # Waited for rather than timed. Reading a line's colours means taking the overlay down
    # for a frame, and that is throttled and asynchronous: until the attempts have been made
    # and given up on, a line is painted in nothing at all, which is not what this is asking
    # about. What says they are done is the transcriptions carrying a colour of the page's.
    # Waited for until the screen is telling the truth about itself. "Has colours" is not
    # enough to wait on: a line that has been given up on is painted in the colour of the
    # whole screen, which is a colour, and looks read. This page is deliberately two colours,
    # so until two come back the reading is still arriving.
    boxes, log = {}, ""
    for _ in range(6):
        boxes, log = show(dev, mode="gradient", density=3, scrollTo=0, settle=7)
        if boxes and len({b["bg"] for b in boxes.values()}) >= 2:
            break
    if not r.check(bool(boxes), "unreadable: there is something to colour",
                   "nothing transcribed"):
        return
    bare = [b["word"] for b in boxes.values() if not b["sampled"]]
    if not r.check(not bare, "unreadable: they are given the page's colours, not ours",
                   f"{len(bare)} left in a palette of ours: {sorted(set(bare))[:6]}"):
        return
    painted = re.findall(
        r"SURFACE ink=#([0-9A-F]{6}) bg=#([0-9A-F]{6}) at=(-?\d+),(-?\d+),(\d+),(\d+) text=(.+)",
        log,
    )
    checked = 0
    for _ink_hex, bg_hex, x, y, w, h, text in painted:
        x, y, w, h = int(x), int(y), int(w), int(h)
        on_this_line = [
            b for b in boxes.values()
            if x <= (b["rect"][0] + b["rect"][2]) // 2 <= x + w
            and y <= (b["rect"][1] + b["rect"][3]) // 2 <= y + h
        ]
        if not on_this_line:
            continue
        checked += 1
        first = text.split()[0][:14]
        r.check(
            all(close(b["bg"], int(bg_hex, 16), tolerance=40) for b in on_this_line),
            f"unreadable: words on '{first}' stand on the surface they are on #{bg_hex}",
            str([hex(b["bg"]) for b in on_this_line]),
        )
    r.check(checked >= 2, "unreadable: lines on both surfaces carried transcriptions",
            f"only {checked} line(s) carried one")


def check_language(r, dev):
    """A page in a language the dictionary is not for is left alone.

    There is one dictionary here and it is English, generated by espeak, which will pronounce
    any string of letters put to it - so "und", "der" and "das" are all in it, with English
    vowels. Left to itself it put English pronunciations through German sentences, on the
    loanwords a German page really has and on the words the two languages happen to share.

    What decides it is which language's commonest words a line is made of, and a line too
    short to hold one is decided for by the screen it is on. So this asks two pages the same
    question: a German one, which should come back untouched, and an English one, which
    should come back transcribed as it always was.
    """
    reset(dev)
    german, _ = show(dev, mode="german", density=2, scrollTo=0, settle=4)
    english, _ = show(dev, mode="unique", density=2, scrollTo=0, settle=4)
    r.check(
        len(english) > 0,
        "language: an English page is still transcribed",
        f"{len(english)} transcriptions on it",
    )
    r.check(
        len(german) == 0,
        "language: a German page is left alone",
        f"{len(german)} transcriptions on it: "
        + str(sorted({b["word"] for b in german.values()})[:8]),
    )


def check_accessibility_button(r, dev):
    """The service asks for the button that switches it off without leaving the page.

    It is the one control a reader can reach while reading, and whether it exists at all comes
    down to one flag in the service's configuration - which nothing else would notice the loss
    of. The system draws it as a floating button, or in the navigation bar, depending on how
    the device is set up, so this asks the service rather than hunting for it on the screen.
    """
    # A fresh process is what makes it say what it asked for. Switching the service off and
    # on again is not enough: it reconnects in the process it was already running in, and
    # says nothing the second time.
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    time.sleep(2)
    dev.clear_log()
    dev.enable_service()
    time.sleep(6)
    log = dev.log()
    r.check(
        "BUTTON registered" in log,
        "the button: the service asks for it",
        "no registration in the log",
    )
    r.check(
        "no accessibility button" not in log,
        "the button: the platform did not refuse it",
        "registering the callback threw",
    )
    # Whether the button is showing is the reader's business - it depends on how they
    # navigate and on what they have assigned it to - so that is not asserted here.
    reset(dev)


def check_a_real_app(r, dev):
    """An app nobody wrote for this test gets transcriptions, and keeps them through a scroll.

    Every other page here is one this repository draws, and a page this repository draws is a
    page whose every quirk has been designed around. The settings app is not: it is a real
    list, laid out by someone else, and it was invisible to the overlay for the whole life of
    this feature. Its events were dropped as a bystander's - the device's home intent is
    answered by that same app's placeholder activity, so the launcher lookup named it - and a
    bystander is dropped before anything is read or logged, so nothing anywhere said so.
    """
    reset(dev)
    shell("am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(4)
    if not r.check("settings" in dev.top_activity().lower(),
                   "a real app: the settings app is in front", dev.top_activity()):
        return
    boxes = {}
    for _ in range(6):
        time.sleep(2.0)
        boxes = dev.boxes()
        if boxes:
            break
    if not r.check(bool(boxes), "a real app: its words are transcribed",
                   "nothing at all on a screen full of English"):
        return
    r.check(
        all(b["sampled"] for b in boxes.values()),
        "a real app: they wear its own colours",
        f"{sum(1 for b in boxes.values() if not b['sampled'])} of {len(boxes)} fell back",
    )
    # And they survive being scrolled, which is a different list implementation from any of
    # the pages here.
    shell("input", "swipe", "540", "1400", "540", "800", "400")
    time.sleep(3.0)
    after = dev.boxes()
    r.check(bool(after), "a real app: they are still there after a scroll",
            "the screen came back empty")


def close(got, want, tolerance=60):
    """Whether two colours are the same to the eye, the sampler quantising as it does."""
    return (
        abs(((got >> 16) & 0xFF) - ((want >> 16) & 0xFF))
        + abs(((got >> 8) & 0xFF) - ((want >> 8) & 0xFF))
        + abs((got & 0xFF) - (want & 0xFF))
    ) <= tolerance


# --------------------------------------------------------------------------------------
# The app's own screen, which unlike the overlay is an ordinary window a dump can see.
# --------------------------------------------------------------------------------------

def to_top():
    """Back to the top of the app's own screen before reading it.

    It keeps where it was scrolled to, so a check that left it half way down had the next
    one reading the second half twice and reporting the first half missing from a screen
    that showed it perfectly well.
    """
    for _ in range(8):
        shell("input", "swipe", "540", "700", "540", "1500", "300")
        time.sleep(0.4)
    time.sleep(1.2)


def ui_text(dev):
    shell("uiautomator", "dump", "/sdcard/ui.xml")
    dump = shell("cat", "/sdcard/ui.xml")
    return re.findall(r'text="([^"]+)"', dump), dump


def check_settings_screen(r, dev):
    # Anything the previous checks left open - a card, most of all - would swallow the
    # first swipe and leave the screen where it started.
    shell("input", "tap", "20", "20")
    time.sleep(1.0)
    # Brought forward and waited for, not started and hoped about. The test page is an
    # activity of this same app, so starting the settings screen behind it delivers the
    # intent and leaves the page where it is - and the dump below then reads the page,
    # reports every section of the settings screen missing, and blames the screen.
    for attempt in range(6):
        shell(
            "am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
            "--activity-reorder-to-front",
        )
        time.sleep(2.0)
        if "MainActivity" in dev.top_activity():
            break
        shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
              "--activity-clear-task", "--activity-new-task")
        time.sleep(2.0)
    if not r.check("MainActivity" in dev.top_activity(),
                   "settings: the app's own screen comes to the front",
                   f"the device is showing {dev.top_activity()}"):
        return
    to_top()
    # A dump only contains what is on screen, and the screen is taller than the window, so
    # the whole of it is collected by scrolling through it.
    texts, dump = ui_text(dev)
    # Short steps, and a dump after each. A long swipe scrolls a whole section past
    # between two dumps, and the section is then reported missing from a screen that
    # showed it perfectly well.
    for _ in range(10):
        shell("input", "swipe", "540", "1300", "540", "950", "400")
        time.sleep(1.2)
        more, more_dump = ui_text(dev)
        texts += more
        dump += more_dump

    # The screen sets its section titles in capitals, so the comparison is on the words
    # rather than on their case.
    seen = {t.lower() for t in texts}
    for wanted in ("Phonetix", "Frequency", "Preview", "Apps"):
        r.check(wanted.lower() in seen, f"settings: the screen shows {wanted}", str(texts[:12]))

    # The frequency reads as one word in so many, and the preview shows what that does.
    r.check(
        any(re.match(r"1 in \d+", t) for t in texts),
        "settings: the frequency is stated as one word in so many",
        str([t for t in texts if "in" in t][:5]),
    )
    r.check(
        "Every word" in texts,
        "settings: the dense end of the bar says it means every word",
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
    shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
          "--activity-reorder-to-front")
    time.sleep(3)
    to_top()
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
    print("the colours")
    check_colors(r, dev)
    check_unreadable_colors(r, dev)
    print("the language of the page")
    check_language(r, dev)
    print("an app nobody wrote for this test")
    check_a_real_app(r, dev)
    print("the button that switches it off")
    check_accessibility_button(r, dev)
    print("the app's own screen")
    check_settings_screen(r, dev)
    check_switch_in_ui(r, dev)

    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the bar, the card, the scope, the switch and the colours all do as they say")


if __name__ == "__main__":
    main()
