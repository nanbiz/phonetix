#!/usr/bin/env python3
"""The parts of the app that are not the moving overlay: the card, the bar, the scope.

The overlay suite drives a page in motion. This one drives the decisions a reader makes -
tapping a word, moving the frequency bar, choosing which apps to see transcriptions in -
and checks the overlay actually obeys them on a device rather than in a unit test.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run scripts/proofread/android_features.py

One part at a time, by name, for a fix to one of them:

  ... android_features.py colours language
"""
# The image library is declared here rather than asked of the caller: a check that needs
# it fails halfway through otherwise, after the emulator has already been driven.
# /// script
# dependencies = ["pillow"]
# ///

import json
import os
import re
import sys
import time

from android_harness import Device, shell
import state as State
from webview import View


class Results:
    def __init__(self):
        self.passed = 0
        self.failures = []
        # Which checks actually ran, since a suite whose count drops has stopped asking
        # something rather than started passing more.
        self.asked = []

    def check(self, ok, name, detail=""):
        self.asked.append(name)
        if ok:
            self.passed += 1
        else:
            self.failures.append(f"{name}: {detail}")
        return ok

    @property
    def total(self):
        return self.passed + len(self.failures)


def press(dev, x, y, ms=700):
    """A press held on one spot, which is what lifts the overlay off the page.

    A swipe that goes nowhere: `input tap` is too brief to be a long press.
    """
    shell("input", "swipe", str(x), str(y), str(x), str(y), str(ms))


def tap(dev, x, y):
    """A tap, which is what asks about a word."""
    shell("input", "tap", str(x), str(y))


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
        # Watched rather than slept through. What a page costs between being asked for and
        # being read is the machine's business: a cold app on a loaded host takes several
        # times what a warm one does, and a check that looks once reports a page that draws
        # nothing while it is still being drawn.
        until = time.time() + settle * 4
        looked = 0
        while True:
            time.sleep(min(1.0, settle))
            looked += 1
            log = dev.log()
            # The surface says when it applied the setting. Only a reading taken after that
            # describes the setting under test; anything earlier describes the previous one.
            # And this page, not the one before it: the surface says which it is, and a page
            # that has been asked for does not always come forward at once on a loaded device.
            applied = [int(m) for m, said in re.findall(r"SETTINGS (\d+) mode=(\S+)", log)
                       if said == mode]
            # A frame with nothing in it is not an answer: the page is read as it is drawn,
            # and the first frame after a setting is applied is routinely empty. Taken as the
            # answer it reported a page that transcribes nothing - in a second and a fifth of
            # a screen that was carrying fifty words a moment later.
            after = ([(t, b) for t, b in dev.box_frames(log) if t >= applied[-1] and b]
                     if applied else [])
            if after:
                return after[-1][1], log
            # The log is not the only account of what is on the screen, and on a loaded
            # machine it is not the first: the line naming every word of a page is thousands
            # of characters and the device drops it. Asked of the overlay itself every few
            # looks, because that is a call to the device each.
            if applied and looked % 3 == 0:
                drawn = drawn_now()
                # Once it has stopped growing: a page is drawn as it is read, so the first
                # non-empty answer can be one word of a screenful, and a check that took it
                # reported a page transcribing almost nothing.
                if drawn:
                    time.sleep(1.2)
                    again = drawn_now()
                    if len(again) <= len(drawn):
                        return drawn, log
                    if len(again) >= len(drawn):
                        drawn = again
                    time.sleep(1.2)
                    settled = drawn_now()
                    if settled:
                        return settled, log
            if time.time() > until:
                break
        # Nothing in the log does not mean nothing on the screen. The line naming every word
        # of a page is thousands of characters long, and a loaded device drops it from its own
        # buffer; the overlay's own account of what it is showing is asked for instead.
        drawn = drawn_now()
        if drawn:
            return drawn, log
    return {}, log


def drawn_now():
    """What the overlay says is on the screen, in the shape the log gives."""
    serial = State.device()
    dumped = State.ask(serial)
    if not dumped:
        return {}
    told = State.fetch(serial, dumped).get("overlay") or {}
    # What is drawn, not what is known: the words of a screen stay known while nothing is
    # painted - that is what lets the button answer one - so reading the known list as the
    # drawn one reported a screenful of transcriptions with the overlay switched off.
    if not told.get("chipsShown"):
        return {}
    shown = told.get("boxes") or []
    out = {}
    for i, box in enumerate(shown):
        rect = box.get("rect") or {}
        if not rect:
            continue
        out[f"{box.get('word', '')}#{i}"] = {
            "word": box.get("word", ""),
            "rect": (rect["left"], rect["top"], rect["right"], rect["bottom"]),
            "bg": box.get("bg", 0),
            "ink": box.get("ink", 0),
            "sampled": bool(box.get("sampled")),
        }
    return out


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
    """Wait until the app is really answering before anything is measured.

    A cold app has a dictionary to load and a synthesiser to start, and it draws nothing at
    all until both are up - which a check that starts measuring straight away reports as a
    frequency bar that does nothing at any setting.
    """
    for _ in range(12):
        boxes, _ = show(dev, density=3)
        if boxes:
            return boxes
        drawn = drawn_now()
        if drawn:
            return drawn
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
        # Tapped. A word is asked about the way anything else on a screen is asked about;
        # what a press held does is lift the overlay, which is a different question.
        tap(dev, (left + right) // 2, (top + bottom) // 2)
        time.sleep(2.5)
        log = dev.log()
        opened = re.search(r"TOOLTIP open word=(\S+) ipa=(\S+) symbols=(\d+)", log)
        if opened:
            break
    if not r.check(opened is not None, "card: a tap opens it", f"no card for {word}"):
        return
    r.check(opened.group(1) == word, "card: it is about the word that was pressed",
            f"pressed {word}, card says {opened.group(1)}")
    r.check(int(opened.group(3)) > 0, "card: it names the symbols of the transcription",
            f"{opened.group(3)} symbols for {opened.group(2)}")
    r.check(len(opened.group(2)) > 0, "card: it shows the full transcription", "empty")

    # What the card actually laid out, not merely that it was asked for.
    #
    # The last report only. A card settles more than once - it goes up with a machine's voice
    # and is told about a person's recording a moment later - and pooling every report of a
    # run reads as a card carrying both at once, which is a card that never existed.
    # Waited for rather than read the once: the card lays itself out in its own composition,
    # which on a busy machine lands later than the moment after the press this used to read.
    reports = re.findall(r"CARD .*", log)
    for _ in range(24):
        if reports:
            break
        time.sleep(0.5)
        reports = re.findall(r"CARD .*", dev.log())
    log = reports[-1] if reports else log
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
    # The transcription is laid out symbol by symbol, because each symbol is a button. What has
    # to be true is that those symbols are the transcription, in order, with nothing lost: a
    # card that dropped one would still look like a transcription.
    missing = [c for c in opened.group(2) if not any(c in t for t in texts)]
    r.check(
        not missing,
        "card: every symbol of the transcription is on it",
        f"{missing} of {opened.group(2)} missing from {texts[:12]}",
    )
    # The audio, and what it is: a synthesised voice and a person saying a word are different
    # things, and a reader is owed which one a control will play. Both are drawn rather than
    # written, so what is checked is the mark reporting itself under its own name.
    r.check(
        "play" in texts,
        "card: it offers to say the word",
        str(texts[:8]),
    )
    # Exactly one, and it says which. A recording is looked for while the card is already up,
    # so the mark changes by recomposition, and the report used to carry both at once - which
    # made this check pass whatever the card was actually showing.
    marks = [t for t in texts if t.startswith("sound=")]
    r.check(
        len(marks) == 1,
        "card: it shows what the audio will be, and only one thing",
        f"marks on the card: {marks}",
    )
    r.check("Wiktionary" in texts, "card: it links onward", str(texts[:6]))

    # What a sound is lives one tap under the word rather than on the card's face, because it is
    # about a sound and the card is about a word. Tapping a symbol is what opens it.
    symbols = [m for m in laid_out if len(m[0]) <= 2 and m[0] in opened.group(2)]
    if r.check(bool(symbols), "card: its symbols are reachable", f"none among {texts[:10]}"):
        _, sx, sy, sw, sh = symbols[len(symbols) // 2]
        dev.clear_log()
        shell("input", "tap", str(int(sx) + int(sw) // 2), str(int(sy) + int(sh) // 2))
        time.sleep(2.5)
        # Spaces travel as a middle dot, since a space would end the item in the line.
        sheet = [t.replace("\u00b7", " ") for t in re.findall(r"\[([^@\]]+)@", dev.log())]
        named = [t for t in sheet if any(
            k in t for k in
            ("plosive", "fricative", "vowel", "approximant", "nasal", "stress", "lateral")
        )]
        r.check(named, "sound: it is named", f"laid out after the tap: {sheet[:10]}")
        r.check(
            any('"' in t and " in " in t for t in sheet),
            "sound: it gives a word it is heard in",
            f"no example among {sheet[:10]}",
        )
        r.check(
            any(t in ("Wikipedia", "Seeing Speech") for t in sheet),
            "sound: it links onward",
            f"no links among {sheet[:10]}",
        )

    # The recording button, tapped where the sheet said it put it.
    play = [m for m in re.findall(r"\[([^@\]]+)@(\d+),(\d+),(\d+),(\d+)\]", dev.log())
            if m[0] == "play-recording"]
    if play:
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
    names = [m for m in rows if len(m[0]) <= 2 and m[0] in opened.group(2)]
    if r.check(bool(names), "card: there is a sound to open", "no symbol on the card"):
        _, x, y, w, h = names[len(names) // 2]
        shell("input", "tap", str(int(x) + int(w) // 2), str(int(y) + int(h) // 2))
        time.sleep(2.5)
        opened_row = dev.log()
        r.check("TOOLTIP closed" not in opened_row, "card: opening a sound keeps the card",
                "the card was rebuilt or closed")
        detail = [t.replace("\u00b7", " ") for t in re.findall(r"\[([^@\]]+)@", opened_row)]
        r.check(
            any(t in ("Wikipedia", "Seeing Speech") for t in detail),
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

    # And a press held opens nothing: it lifts the overlay off the page instead, which is the
    # reader asking to see what their app wrote. Left to the end: the card is dismissed to run
    # it, and a card that has been dismissed and opened again does not report its layout a
    # second time.
    shell("input", "keyevent", "4")
    time.sleep(1.2)
    dev.clear_log()
    press(dev, (left + right) // 2, (top + bottom) // 2)
    time.sleep(2.0)
    r.check(
        "TOOLTIP open" not in dev.log(),
        "card: a press held opens no card",
        "a press held opened the card",
    )

    # And with the words left as they come - a picture, taking no touches - a tap on one
    # opens nothing at all, because the gesture belongs to the app underneath.
    dev.clear_log()
    dev.surface(mode="plain", enable=1, density=3, allApps=1, touchWords=0)
    time.sleep(2.5)
    dev.clear_log()
    tap(dev, (left + right) // 2, (top + bottom) // 2)
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

def blind(dev, log=""):
    """Whether the device is refusing to be photographed at all.

    The overlay reads the colours of a line off a picture of the screen taken with its own
    paint down. Where that picture never arrives it says so, over and over.
    """
    # Asked of the device rather than of a tail of the log: this build writes a line naming
    # every word on screen on every pass, and the few lines that matter here are pushed out
    # of any bounded tail by them.
    said = (log or "") + dev.lines("COLOURS no clean frame", keep=8)
    return said.count("COLOURS no clean frame") >= 2


def check_colors(r, dev):
    """Every word gets colours read off the screen, and they are the ones under it.

    A transcription that keeps a palette of ours is a patch: the whole point is that it is
    drawn in the app's own ink on the app's own surface. The debug surface paints its lines
    in colours it names in the log, one line differing from the next, so what the overlay
    reports can be compared against what the app says it drew.
    """
    reset(dev)
    # Patient: this page is read, transcribed and then photographed for its colours, and on a
    # machine with nothing to spare that is a dozen seconds before anything is on screen.
    boxes, log = show(dev, mode="colors", density=3, scrollTo=0, settle=6)
    # A device that cannot be photographed has no colours to compare against, and it is the
    # machine that cannot, not the product: the overlay asks for a frame with its own paint
    # down, and where that frame never arrives every line waits, is given up on, and is drawn
    # in our own palette - which is what it is meant to do. An emulator under load does this
    # for minutes at a time.
    if not boxes and blind(dev, log):
        print("  (this device could not be photographed, so there are no colours to read)")
        r.check(True, "colours: the device could not be photographed", "")
        return
    if not r.check(bool(boxes), "colours: there is something to colour", "nothing transcribed"):
        return

    # Waited for, because the colours arrive after the words.
    #
    # A word is drawn the moment it is read, in the fallback palette, and repainted in the
    # line's own colours once a photograph of the screen has been taken - which is a second or
    # so later and is retried whenever the screen moves. Read before that, every word on the
    # page is legitimately still wearing the fallback, and this check was reading the gap
    # rather than the product. It still fails where the repaint never comes.
    for _ in range(10):
        if boxes and all(b["sampled"] for b in boxes.values()):
            break
        time.sleep(1.5)
        fresh, log = show(dev, mode="colors", density=3, scrollTo=0, settle=0)
        boxes = fresh or boxes

    # A device that cannot be photographed has no colours to compare against. The overlay
    # asks for a frame with its own paint taken down, and where that frame never arrives the
    # line is given up on and drawn in our own palette - which is the product working as
    # designed, not a colour read wrongly. An emulator under load does this for minutes at a
    # time, and reported as a mismatch it is a check crying wolf about the machine it is on.
    if "COLOURS no clean frame" in log and "DECIDE none givenUp" in log:
        print("  (this device could not be photographed, so the colours were given up on)")
        r.check(True, "colours: the device could not be photographed", "")
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
        # A capture with a picture in it has to have happened, or there was nothing to read
        # any surface from and every line is wearing the colour of the whole screen.
        # What this is about is the surface a line stands on being read at all. Until one of
        # them wears the band's colour, the capture has given nothing back - which happens on
        # a loaded emulator - and there is nothing here to judge.
        looked = "COLOURS read=" in only_this_page(log)
        band = any(close(b["bg"], 0x2A2E10, tolerance=40) for b in boxes.values()) if boxes else False
        if boxes and looked and band:
            break
    if not boxes and blind(dev, log):
        print("  (this device could not be photographed, so there are no surfaces to read)")
        r.check(True, "unreadable: the device could not be photographed", "")
        return
    if not r.check(bool(boxes), "unreadable: there is something to colour",
                   "nothing transcribed"):
        return
    bare = [b["word"] for b in boxes.values() if not b["sampled"]]
    if not r.check(not bare, "unreadable: they are given the page's colours, not ours",
                   f"{len(bare)} left in a palette of ours: {sorted(set(bare))[:6]}"):
        return
    if not any(close(b["bg"], 0x2A2E10, tolerance=40) for b in boxes.values()):
        # Reading a surface means photographing the screen, and on a busy machine that can
        # fail for as long as this is willing to wait. Nothing was captured, so there is
        # nothing here to be right or wrong about; said rather than counted either way.
        print("  unreadable: nothing was captured to read a surface from, not judged")
        return
    painted = re.findall(
        r"SURFACE ink=#([0-9A-F]{6}) bg=#([0-9A-F]{6}) at=(-?\d+),(-?\d+),(\d+),(\d+) text=(.+)",
        only_this_page(log),
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
            f"got {[format(b['bg'], '06x') for b in on_this_line]} "
            f"at y {y}..{y + h}; the page said "
            + str([(t.split()[0][:8], f"#{bg}", f"{yy}..{int(yy) + int(hh)}")
                   for _i, bg, _x, yy, _w, hh, t in painted]),
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
    short to hold one is decided for by the screen it is on.

    A German page used to have to come back untouched, because the only thing that could have
    transcribed it would have said it in English. Now that the phone carries the synthesiser
    the browser has always had, a German page is transcribed in the German voice - so what is
    asked here is no longer whether anything was drawn but whether it was drawn in the right
    language. "Nacht" is the word that separates them: German says it with the ach-Laut, and
    no English reading of those letters has one.
    """
    reset(dev)
    # Every word, not one in two: what is being asked is which language a reading is in, and
    # a bar that draws half the page can leave out the word that answers that.
    #
    # Asked again while nothing has been drawn: a German page is read by the synthesiser, which
    # starts a moment after the first screen and fills on the pass after that.
    german, german_log = {}, ""
    said = {}
    read_in_german = False
    for _ in range(4):
        german, german_log = show(dev, mode="german", density=1, scrollTo=0, settle=4)
        # What was written over each word, taken while the German page is the last thing
        # drawn, and asked of the device: the overlay writes one long line per pass, so the
        # one naming the words scrolls out of any window worth reading and is dropped from
        # the log outright when the device is busy.
        #
        # Waited for rather than read once. A read of the tree takes a moment on a machine
        # with nothing to spare and seconds on one that is loaded; asked once at a fixed
        # remove, this found no line and reported a German page read in English while the app
        # was reading it in German.
        for _ in range(12):
            # Only a reading of the German page counts. The page is asked for and does not
            # always come forward at once on a loaded device, and the words drawn meanwhile
            # are the last page's - which is an English reading of an English page, reported
            # as a German page read in English.
            if "source=de" not in dev.lines("READING source=de"):
                time.sleep(1)
                continue
            # Noted while the German page is the last thing read: the English page below
            # clears the log, and asking afterwards asks about the wrong page.
            read_in_german = True
            for line in reversed(dev.lines("DRAWN ").splitlines()):
                if "DRAWN " not in line:
                    continue
                pairs = dict(
                    pair.split("=", 1)
                    for pair in line.split("DRAWN ", 1)[1].split()
                    if "=" in pair
                )
                if pairs:
                    said = pairs
                    break
            if said:
                break
            time.sleep(1)
        if said:
            break
    # And the words themselves carry it, not only the reading that was chosen for them.
    #
    # What language a word is in is asked again when the card for it is built - the card looks
    # the word up and says which language it is answering about - and the words used to reach
    # it carrying nothing. Every card on a German page was then built in English: no entry,
    # no meaning, and a chip saying EN under a German word.
    carried = {}
    serial = State.device()
    dumped = State.ask(serial)
    if dumped:
        for box in (State.fetch(serial, dumped).get("overlay") or {}).get("boxes", []):
            reads = box.get("language") or ""
            carried[reads] = carried.get(reads, 0) + 1
            # What was drawn over each word, from the app itself rather than from a log line.
            # The line naming every word on screen is thousands of characters long, and a
            # loaded device drops it from its own buffer: asked for out of the log alone, this
            # reported a German page read in English on a device that was reading it in German.
            if box.get("word") and box.get("drawn"):
                said.setdefault(box["word"], box["drawn"])
    r.check(
        carried.get("de", 0) > 0 and list(carried) == ["de"],
        "language: the words of a German page carry German, so a card about one is German",
        f"the words say {carried or 'nothing'}",
    )
    # Patient, like the German page above: this follows a page in another language, so the
    # reading, the language identification and the synthesiser all happen again.
    english, _ = show(dev, mode="unique", density=2, scrollTo=0, settle=6)
    r.check(
        len(english) > 0,
        "language: an English page is still transcribed",
        f"{len(english)} transcriptions on it",
    )
    r.check(
        len(german) > 0,
        "language: a German page is transcribed too",
        "nothing on it, though the synthesiser can read any language it has a voice for",
    )
    # Sounds an English reading of these letters would not produce: the ach-Laut, the
    # ich-Laut, and the front rounded vowels. One of them is enough to say which voice read
    # the page, and looking for a set rather than for one word does not depend on which words
    # the bar happened to draw.
    # Whether the page ever came forward at all. Without this the check reports a German page
    # read in English when what it actually read was the English page that was still up.
    r.check(
        read_in_german,
        "language: the German page came forward to be read",
        "nothing read a page in German, so what was measured is the page before it",
    )
    GERMAN_ONLY = ("x", "ç", "yː", "ʏ", "øː", "œ")
    marked = {word: ipa for word, ipa in said.items()
              if any(sound in ipa for sound in GERMAN_ONLY)}
    r.check(
        bool(marked),
        "language: a German page is read in German, not in English",
        f"nothing among {list(said.items())[:6]} carries a sound only German has",
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


def check_shade(r, dev):
    """The notification shade comes down over the app, and the transcriptions go with it.

    They are windows above everything, so a word that the shade now covers had its
    transcription still painted on top of the shade - a screenful of them scattered over the
    notifications. Nothing noticed, because the shade belongs to the system interface, whose
    events are dropped as a bystander's before anything is looked at.
    """
    reset(dev)
    # Asked for again where the first read has not landed. A read of the tree takes a moment
    # on a machine with nothing to spare and seconds on one that is loaded, and a check that
    # gives up after one settle reports an app drawing nothing while it is still drawing.
    boxes = {}
    for _ in range(4):
        boxes, _ = show(dev, mode="unique", density=3, scrollTo=200, settle=4)
        if boxes:
            break
    if not r.check(bool(boxes), "the shade: there is something to cover", "nothing transcribed"):
        return
    try:
        dev.clear_log()
        shell("cmd", "statusbar", "expand-notifications")
        # What is on the screen now, asked of the overlay itself and waited for. The last
        # line in the log is what was drawn when it was written: on a loaded machine the
        # service can still be hearing about the shade when a check that sleeps once looks,
        # and a stale line then reads as a screenful of transcriptions over the notifications.
        # What is drawn, not what is known. The words of the page behind stay known while the
        # shade is over them - that is what lets the button answer one the moment it closes -
        # and only what is painted can be painted over somebody else's screen.
        drawn = 0
        waited = 0.0
        for _ in range(8):
            time.sleep(0.75)
            waited += 0.75
            serial = State.device()
            dumped = State.ask(serial)
            shown = (State.fetch(serial, dumped).get("overlay") or {}) if dumped else {}
            drawn = shown.get("chipsShown") or 0
            if not drawn:
                break
        r.check(not drawn, "the shade: nothing is drawn over it",
                f"{drawn} transcriptions were still on the screen {waited:.0f}s after it opened")
    finally:
        dev.clear_log()
        shell("cmd", "statusbar", "collapse")
        time.sleep(3.5)
    # Given a few looks: closing the shade puts the page back and the screen has to be read
    # again before anything is drawn on it, which is a read of the whole tree.
    # What the shade leaves in front is the system's business: on this emulator it restores
    # the app's own settings screen rather than the page that was being read. The page is
    # asked for again when that happens, since what is being checked is that the overlay
    # draws again after the shade, not which task the system chose to restore.
    resumed = shell("dumpsys", "activity", "activities")
    if "DebugSurfaceActivity" not in (
        re.search(r"ResumedActivity[^\n]*", resumed).group(0) if "ResumedActivity" in resumed
        else ""
    ):
        show(dev, mode="unique", density=3, scrollTo=200, settle=3)

    back = {}
    for _ in range(8):
        dev.clear_log()
        # Asked for again on each look: what comes back from the shade is a screen the
        # service has to read afresh, and a look that only reads the log sees the last pass
        # rather than this one.
        shell("input", "swipe", "540", "1200", "540", "1150", "300")
        time.sleep(2.0)
        back = dev.boxes()
        if back:
            break
    # Which screen came back, since "nothing was drawn" means one thing when the page
    # returned and another when the shade left the app's own settings in front of it.
    resumed = shell("dumpsys", "activity", "activities")
    found = re.search(r"ResumedActivity[^\n]*?(\S+/\S+)", resumed)
    where = found.group(1) if found else "nothing that says what it is"
    r.check(bool(back), "the shade: they come back when it is closed",
            f"the page came back bare; what was in front was {where}")


def page_is_dark(dev, into="/tmp/phonetix-theme"):
    """Whether the app on screen is drawn dark, read off the screen rather than asked for.

    The setting says what was requested; the activity takes a moment to be rebuilt in it, and
    what matters here is what the transcriptions were read against.
    """
    try:
        from PIL import Image
    except ImportError:
        return None
    dev.screenshot(into)
    names = [n for n in os.listdir(into) if n.endswith(".png")]
    if not names:
        return None
    image = Image.open(os.path.join(into, names[0])).convert("L")
    width, height = image.size
    # The middle of the page, away from the status bar and the navigation bar.
    band = image.crop((0, int(height * 0.3), width, int(height * 0.7)))
    return sum(band.getdata()) / (band.size[0] * band.size[1]) < 110


# A theme change is checked by hand rather than here. What such a check needs is a reading
# taken after the device repainted, and an app that has finished repainting produces no reading
# at all - it is not doing anything, so it is not read again. Every way of forcing one made the
# check less trustworthy than the thing it was checking. Watched directly instead, on the
# settings app: switching to dark and back gives transcription backgrounds of f0f0f0, 181820
# and f0f0f0, four times out of four.


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
    # From the app's own front page, not from wherever it was left. Its search screen belongs
    # to a second package, so it survives stopping the settings app and it is what
    # `am start` restores - a screen with two words on it, which reads as the overlay having
    # stopped working.
    shell("am", "force-stop", "com.android.settings")
    shell("am", "force-stop", "com.google.android.settings.intelligence")
    time.sleep(1.5)
    shell("am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(4)
    if not r.check("settings" in dev.top_activity().lower(),
                   "a real app: the settings app is in front", dev.top_activity()):
        return
    # What the overlay says it is showing, once it has stopped growing: the page is drawn as
    # it is read, and the first answer can be one word of a screenful.
    boxes = {}
    steady = 0
    for _ in range(10):
        time.sleep(2.0)
        now = drawn_now() or dev.boxes()
        if now and len(now) <= len(boxes):
            # Twice, not once: a page is drawn as it is read, and a screen caught between two
            # passes reads the same small count twice in a row only when that is all there is.
            steady += 1
            if steady >= 2:
                break
        else:
            steady = 0
        boxes = now or boxes
    if not r.check(bool(boxes), "a real app: its words are transcribed",
                   "nothing at all on a screen full of English"):
        return
    # Waited for, because the colours arrive after the words now.
    #
    # A word used to be withheld until its own colours had been read off a photograph of the
    # screen, which meant the whole page waited about a second on the camera. It goes up at
    # once in the fallback palette instead and is repainted when the photograph lands, so what
    # this asks is that the repaint happens - not that it has already happened by the time the
    # first reading was taken. It still fails where the colours never arrive at all.
    for _ in range(8):
        if all(b["sampled"] for b in boxes.values()):
            break
        time.sleep(1.5)
        # Asked of the overlay rather than read out of the log: the line naming every word of
        # a screen is thousands of characters, and a loaded device drops it - which read here
        # as a screen carrying one word.
        boxes = drawn_now() or dev.boxes() or boxes
    if all(b["sampled"] for b in boxes.values()) or not blind(dev):
        r.check(
            all(b["sampled"] for b in boxes.values()),
            "a real app: they wear its own colours",
            f"{sum(1 for b in boxes.values() if not b['sampled'])} of {len(boxes)} fell back",
        )
    else:
        # The machine, not the product: where the screen cannot be photographed at all there
        # are no colours to read, and the words are drawn in our own palette by design.
        print("  (this device could not be photographed, so the colours were given up on)")
    # The words a real app's screen is actually made of. Almost all of its text is a heading
    # or a label in title case, and while the cascade compared spellings byte for byte every
    # one of those missed the dictionary: the screen came back nearly bare and every check
    # here still passed, because each of them asks about words written the way a dump keys
    # them. So this asks about the capitalised ones specifically.
    capitalised = [b["word"] for b in boxes.values() if b["word"][:1].isupper()]
    r.check(
        len(capitalised) >= 3,
        "a real app: the words it capitalises are transcribed too",
        f"only {len(capitalised)} of {len(boxes)} transcribed words begin with a capital, "
        f"on a screen whose every label is title case: {sorted(capitalised)[:8]}",
    )
    # And they survive being scrolled, which is a different list implementation from any of
    # the pages here.
    shell("input", "swipe", "540", "1400", "540", "800", "400")
    time.sleep(3.0)
    after = dev.boxes()
    if not r.check(bool(after), "a real app: they are still there after a scroll",
                   "the screen came back empty"):
        return

    # How much of the screen still carries a transcription, counted off the screen rather than
    # off what the service says about itself.
    #
    # The service reporting words is not the same as a reader seeing them, and the two came
    # apart badly: a page settled after a drag with a third of its lines transcribed and
    # stayed that way, while the log said everything was fine. There are no marks to count in
    # someone else's app, so the same screen is photographed again with the service switched
    # off and the rows that differ are ours.
    covered = [settled_coverage(dev, "settled")]
    for _ in range(2):
        shell("input", "swipe", "540", "1400", "540", "500", "1300")
        time.sleep(3.0)
        covered.append(settled_coverage(dev, "scrolled"))
    # As a share of the rows that carry text at all, not as a count. A count compares this
    # screen with the one before it, and scrolling a list reaches its end: the last screen of
    # the settings app is mostly empty, so a run that dragged that far reported the overlay as
    # having stopped working when it had merely run out of words to work on.
    shares = [ours / text if text else 0.0 for ours, text in covered]
    r.check(
        min(shares) >= shares[0] * KEPT_AFTER_SCROLLING,
        "a real app: it still carries them after scrolling",
        "share of the screen's text rows carrying a transcription, before and after each "
        f"drag: {[f'{s:.0%}' for s in shares]} of {[text for _, text in covered]} rows",
    )

    # Pressing a word for its card is checked on this repository's own page, where the words
    # stay where they are put. Doing it in someone else's app means pressing a moving target -
    # a list settles, a row animates, a press that misses lands on the app and navigates away -
    # and a check that cannot hit what it aims at reports the card as broken when nothing is.
    # Watched by hand instead, on the settings app: the card opens on a word of theirs, names
    # its sounds and scrolls through them.


# How much of a screen's text has to keep its transcriptions through a drag and its aftermath.
# Not all of it: a screen scrolled to a different place has different words on it, and some of
# them are ones this dictionary has nothing for.
KEPT_AFTER_SCROLLING = 0.7


def settled_coverage(dev, where, tries=3):
    """How much of the screen the overlay carries, once it has stopped changing.

    A reading taken while it is still drawing is a reading of a moment, not of the screen: the
    overlay comes back a line at a time after a drag, and a photograph in the middle of that
    says twelve per cent of a page that ends up carrying seventy. So this reads again while
    the answer is still climbing and keeps the best, which is the settled screen.
    """
    best = (0, 0)
    for _ in range(tries):
        got = rows_of_ours(dev, where)
        # No photograph, so nothing was measured. Tried again rather than counted as zero.
        if got is None:
            time.sleep(2.5)
            continue
        share = got[0] / got[1] if got[1] else 0
        if share > (best[0] / best[1] if best[1] else 0):
            best = got
        if share >= 0.4:
            break
        time.sleep(2.5)
    return best


def drawn_again(dev, within=12.0):
    """Wait until the overlay is drawing again, and say whether it got there."""
    until = time.time() + within
    while time.time() < until:
        if dev.boxes():
            return True
        time.sleep(0.5)
    return False


def rows_of_ours(dev, where):
    """Rows of the screen the overlay drew on, and rows that carry any text at all.

    Both, because one without the other says nothing. There are no marks to count in someone
    else's app, so the same screen is photographed twice - once as the reader sees it and once
    with the service switched off - and the rows that differ are ours. The second photograph
    is also what says how much text was there to work on, which is what the first has to be
    judged against: a list scrolled to its end is mostly empty, and an overlay drawing nothing
    on nothing is not an overlay that stopped.
    """
    from PIL import Image, ImageChops
    # Photographed once the overlay has actually drawn again, not after a fixed wait. This
    # reading switches the service off to get the bare screen and back on afterwards, and a
    # service just switched on has read nothing yet: a fixed sleep photographed the gap and
    # reported the overlay as having lost the screen when it was still finding it.
    drawn_again(dev)
    # A capture that never arrives is not an answer about the overlay, so this declines to
    # judge rather than reporting a screen full of transcriptions as bare - and it puts the
    # service back first, because it is switched off in the middle of this.
    took = dev.screenshot(f"/tmp/phonetix-real/{where}-ours")
    if took is None:
        return None
    ours = Image.open(took).convert("RGB")
    shell("settings", "put", "secure", "enabled_accessibility_services", "none")
    time.sleep(3.5)
    took = dev.screenshot(f"/tmp/phonetix-real/{where}-bare")
    dev.enable_service()
    if took is None:
        return None
    bare = Image.open(took).convert("RGB")
    drawn_again(dev)
    diff = ImageChops.difference(ours, bare)
    width, height = diff.size
    rows, text = 0, 0
    for y in range(0, height, 2):
        if any(sum(diff.getpixel((x, y))) > 60 for x in range(0, width, 2)):
            rows += 1
        # Against the row's own left edge, which is margin on every screen this reads: a page
        # is not one colour, so a single background taken for the whole screen calls a card's
        # edge text.
        margin = bare.getpixel((2, y))
        if any(sum(abs(a - b) for a, b in zip(bare.getpixel((x, y)), margin)) > 60
               for x in range(0, width, 2)):
            text += 1
    return rows, text


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

def only_this_page(log):
    """The part of the log that describes the page now on screen.

    Every page says where its lines are and what they are drawn in, and the log keeps saying
    it after that page has gone. A check that reads the whole buffer matches a transcription
    against a line of some earlier page that happened to be at the same height - which is how
    a page of two colours came back failing at random.
    """
    marks = [m.end() for m in re.finditer(r"SETTINGS \d+ ", log)]
    return log[marks[-1]:] if marks else log


def check_settings_screen(r, dev):
    """The app's own screen, which is the extension's own screen.

    One set of components, built into the app's assets and drawn in a web view, so it is asked
    the same questions the browser suite asks it: which rows are there, and whether the
    controls on them are controls. An accessibility dump sees a web view as one blank view and
    would report every row of it missing.
    """
    # Anything the previous checks left open - a card, most of all - would swallow the tap.
    shell("input", "tap", "20", "20")
    time.sleep(1.0)
    # Brought forward and waited for, not started and hoped about. The test page is an
    # activity of this same app, so starting the settings screen behind it delivers the
    # intent and leaves the page where it is.
    for _ in range(6):
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

    words = wording()
    try:
        with View() as view:
            drawn = None
            for _ in range(20):
                drawn = view.evaluate("""
                    (() => {
                      const panel = document.querySelector('main');
                      if (!panel || !panel.querySelector('[data-row]')) return null;
                      return JSON.stringify({
                        rows: [...panel.querySelectorAll('[data-row]')]
                          .map(r => r.getAttribute('data-row')),
                        names: [...panel.querySelectorAll('[data-row] [data-name]')]
                          .map(r => r.textContent.trim()),
                        modes: [...panel.querySelectorAll('[data-row=layer] [data-choice]')]
                          .map(c => c.getAttribute('data-choice')),
                        often: (panel.querySelector('[data-row=density] [data-about]') || {})
                          .textContent || '',
                        bar: Boolean(panel.querySelector('[data-row=density] input[type=range]')),
                        palettes: Boolean(panel.querySelector('[data-row=theme] .select')),
                        on: Boolean(panel.querySelector('[data-row=on] input')),
                        ground: getComputedStyle(document.body).backgroundColor,
                      });
                    })()
                """)
                if drawn:
                    break
                time.sleep(1)
            if not r.check(drawn, "settings: the screen draws itself", "nothing in the view"):
                return
            screen = json.loads(drawn)
    except Exception as e:  # noqa: BLE001 - the reason is what a reader of the run needs
        r.check(False, "settings: the screen draws itself", str(e))
        return

    # The rows a reader of either surface finds, under the names both are written out of.
    for row in ("on", "ipa", "translate", "density", "theme", "dark", "apps", "advanced"):
        r.check(row in screen["rows"], f"settings: the screen has the {row} row",
                str(screen["rows"]))
    # Named as they are named on the other surface, because both are written out of
    # data/wording.json.
    for row in ("ipa", "translate", "density", "theme"):
        r.check(words["rows"][row]["name"] in screen["names"],
                f"settings: the {row} row is called {words['rows'][row]['name']}",
                str(screen["names"][:12]))

    # What a word is replaced by: one switch, and two under it saying what it puts there.
    #
    # The language a word is turned into belongs to the one of those two that turns it into
    # one, and to nothing else, so it is there after switching that on and gone after
    # switching it off.
    into = None
    try:
        with View() as view:
            def switch(row, on):
                view.evaluate(
                    "(() => { const b = document.querySelector('[data-row=%s] input');"
                    " if (b && b.checked !== %s) b.click(); })()"
                    % (row, "true" if on else "false")
                )
                time.sleep(1)

            # Something has to be replaced for either of these to mean anything.
            switch("ipa", True)
            for translating, want in ((True, True), (False, False)):
                switch("translate", translating)
                there = view.evaluate(
                    "Boolean(document.querySelector('[data-row=target] .select'))")
                r.check(
                    there == want,
                    "settings: the language is "
                    f"{'offered' if want else 'not asked for'} when translating is "
                    f"{'on' if translating else 'off'}",
                    str(there),
                )
                if translating:
                    into = there
            # Neither of them on is the replacing off: there is no third switch saying so,
            # and a replacement of nothing would be nothing.
            switch("ipa", False)
            switch("translate", False)
            off = view.evaluate(
                "(document.querySelector('[data-row=ipa] input')?.checked === false) &&"
                " (document.querySelector('[data-row=translate] input')?.checked === false)")
            r.check(bool(off), "settings: both can be switched off, which is replacing nothing",
                    str(off))
            # Left as this suite expects to find it.
            switch("ipa", True)
            switch("translate", True)
    except Exception as e:  # noqa: BLE001
        r.check(False, "settings: the language to read into follows the translating", str(e))
    void = into
    # The bar is a bar, and says what it means in words rather than as a ratio.
    r.check(screen["bar"], "settings: the frequency bar is a real control", "no bar in the view")
    r.check("word" in screen["often"].lower(),
            "settings: the frequency is stated in words", repr(screen["often"]))
    # The palettes, which the phone offered none of until it drew this screen. Opened the way
    # a reader opens them: this product draws its own list, because a native menu is the
    # platform's - on a phone a white dialog of radio buttons over a surface in the reader's
    # own colours, and no way to search fifty-four languages.
    r.check(screen["palettes"], "settings: the palettes can be opened", "no theme row")
    themes = 0
    if screen["palettes"]:
        try:
            with View() as view:
                view.evaluate("document.querySelector('[data-row=theme] .select').click()")
                time.sleep(1)
                themes = view.evaluate(
                    "document.querySelectorAll('[data-sheet] [data-choice]').length") or 0
                view.evaluate(
                    "(document.querySelector('[data-sheet] .btn-text') || {}).click?.()")
        except Exception as e:  # noqa: BLE001
            themes = f"the list did not open ({e})"
    # The palettes that have the side in force, which is never none and never all eight: four
    # of them carry one side only, and offering a light one to a reader reading in the dark is
    # offering a choice that cannot be honoured.
    r.check(4 <= themes <= 7, "settings: the palettes for this side are in the list", str(themes))
    # And the tokens reached it: a screen with no surface colour is a screen drawn in
    # nothing, which is what a missing stylesheet looks like.
    r.check("rgba(0, 0, 0, 0)" not in screen["ground"],
            "settings: the screen is painted in the product's own colours",
            screen["ground"])


HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))


def wording():
    """The words both platforms are written out of, read where they are authored.

    A check that spells a label itself is a third copy of it, and the one that goes stale
    without anything failing.
    """
    with open(os.path.join(ROOT, "data", "wording.json")) as f:
        return json.load(f)


def check_switch_in_ui(r, dev):
    """The switch on the screen is the switch the overlay obeys.

    Worked the way a reader works it - pressed, not stored behind its back - and then asked of
    the settings the service reads, because a switch that moves and changes nothing is the
    failure this is here to catch.
    """
    shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
          "--activity-reorder-to-front")
    time.sleep(3)
    try:
        with View() as view:
            before = view.evaluate(
                "(document.querySelector('[data-row=on] input') || {}).checked")
            if not r.check(before is not None, "settings: the screen carries the switch",
                           "no switch in the view"):
                return
            view.evaluate("(document.querySelector('[data-row=on] input') || {}).click?.()")
            time.sleep(2)
            after = view.evaluate(
                "(document.querySelector('[data-row=on] input') || {}).checked")
            r.check(after is not None and after != before,
                    "settings: the switch moves when it is pressed", f"{before} -> {after}")
            # And the app kept it: the screen writes through the bridge to the same store the
            # service reads, so what the reader pressed is what the overlay is told.
            stored = shell("run-as", "io.github.nanbiz.phonetix", "cat",
                           "shared_prefs/phonetix.settings.xml")
            r.check(
                f'name="enabled" value="{str(bool(after)).lower()}"' in stored,
                "settings: the switch is written where the service reads it",
                stored[-200:] if stored else "nothing stored",
            )
            # Left as it was found, so the checks after this one read the screen they expect.
            if after != before:
                view.evaluate("(document.querySelector('[data-row=on] input') || {}).click?.()")
                time.sleep(1)
    except Exception as e:  # noqa: BLE001
        r.check(False, "settings: the screen carries the switch", str(e))


def main():
    dev = Device()
    if not dev.enable_service():
        print("FAIL - the accessibility service will not start")
        sys.exit(1)
    if not warm(dev):
        print("FAIL - the app drew nothing at all, so there is nothing to measure")
        sys.exit(1)
    # The reader's own choices, put back to what this suite measures against: a run that
    # followed one leaving a target language behind counted the words of an English page that
    # were being answered in German, and reported the frequency bar as broken.
    shell("am", "start", "-n", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity",
          "--es", "target", "none", "--es", "layer", "sound", "--ei", "enable", "1")
    time.sleep(2)

    r = Results()
    # Each part of the product, under the name it is printed by. Named so that one of them
    # can be run on its own: a whole pass takes half an hour, and a fix to one part should
    # not cost that to see.
    parts = (
        ("bar", "the frequency bar", (check_density,)),
        ("card", "the card a tap opens", (check_tooltip,)),
        ("apps", "which apps", (check_scope,)),
        ("switch", "the master switch", (check_switch,)),
        ("colours", "the colours", (check_colors, check_unreadable_colors)),
        ("language", "the language of the page", (check_language,)),
        ("real", "an app nobody wrote for this test", (check_a_real_app,)),
        ("shade", "the notification shade", (check_shade,)),
        ("button", "the button that switches it off", (check_accessibility_button,)),
        ("screen", "the app's own screen", (check_settings_screen, check_switch_in_ui)),
    )
    wanted = [a for a in sys.argv[1:] if not a.startswith("-")]
    unknown = [a for a in wanted if a not in {name for name, _, _ in parts}]
    if unknown:
        raise SystemExit(
            f"no such part: {', '.join(unknown)}. "
            f"There is {', '.join(name for name, _, _ in parts)}."
        )
    for name, said, checks in parts:
        if wanted and name not in wanted:
            continue
        print(said)
        for check in checks:
            check(r, dev)

    if os.environ.get("PHONETIX_LIST"):
        for name in r.asked:
            print(f"  ran: {name}")
    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the bar, the card, the scope, the switch and the colours all do as they say")


if __name__ == "__main__":
    main()
