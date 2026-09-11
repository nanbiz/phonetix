#!/usr/bin/env python3
"""The parts of the app that are not the moving overlay: the card, the bar, the scope.

The overlay suite drives a page in motion. This one drives the decisions a reader makes -
tapping a word, moving the frequency bar, choosing which apps to see transcriptions in -
and checks the overlay actually obeys them on a device rather than in a unit test.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run scripts/proofread/android_features.py
"""
# The image library is declared here rather than asked of the caller: a check that needs
# it fails halfway through otherwise, after the emulator has already been driven.
# /// script
# dependencies = ["pillow"]
# ///

import os
import re
import sys
import time

from android_harness import Device, shell


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
    #
    # The last report only. A card settles more than once - it goes up with a machine's voice
    # and is told about a person's recording a moment later - and pooling every report of a
    # run reads as a card carrying both at once, which is a card that never existed.
    reports = re.findall(r"CARD .*", log)
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
        # A capture with a picture in it has to have happened, or there was nothing to read
        # any surface from and every line is wearing the colour of the whole screen.
        # What this is about is the surface a line stands on being read at all. Until one of
        # them wears the band's colour, the capture has given nothing back - which happens on
        # a loaded emulator - and there is nothing here to judge.
        looked = "COLOURS read=" in only_this_page(log)
        band = any(close(b["bg"], 0x2A2E10, tolerance=40) for b in boxes.values()) if boxes else False
        if boxes and looked and band:
            break
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
    for _ in range(4):
        german, german_log = show(dev, mode="german", density=1, scrollTo=0, settle=4)
        if any("DRAWN " in line and len(line.split("DRAWN ", 1)[1]) > 3
               for line in german_log.splitlines()):
            break
    english, _ = show(dev, mode="unique", density=2, scrollTo=0, settle=4)
    r.check(
        len(english) > 0,
        "language: an English page is still transcribed",
        f"{len(english)} transcriptions on it",
    )
    # What was written over each word, which the geometry line does not carry.
    # The last report that drew anything, rather than the last report: a pass that read the
    # screen and drew nothing is an ordinary moment between two that did, and taking it leaves
    # this asking its question of an empty set.
    said = {}
    for line in reversed(german_log.splitlines()):
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
    r.check(
        len(german) > 0,
        "language: a German page is transcribed too",
        "nothing on it, though the synthesiser can read any language it has a voice for",
    )
    # Sounds an English reading of these letters would not produce: the ach-Laut, the
    # ich-Laut, and the front rounded vowels. One of them is enough to say which voice read
    # the page, and looking for a set rather than for one word does not depend on which words
    # the bar happened to draw.
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
    boxes, _ = show(dev, mode="unique", density=3, scrollTo=200, settle=4)
    if not r.check(bool(boxes), "the shade: there is something to cover", "nothing transcribed"):
        return
    try:
        dev.clear_log()
        shell("cmd", "statusbar", "expand-notifications")
        time.sleep(3.5)
        under = dev.boxes()
        r.check(not under, "the shade: nothing is drawn over it",
                f"{len(under)} transcriptions were still on the screen")
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
    ours = Image.open(dev.screenshot(f"/tmp/phonetix-real/{where}-ours")).convert("RGB")
    shell("settings", "put", "secure", "enabled_accessibility_services", "none")
    time.sleep(3.5)
    bare = Image.open(dev.screenshot(f"/tmp/phonetix-real/{where}-bare")).convert("RGB")
    dev.enable_service()
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
    # Enough steps to reach the end of it: the screen has grown - transcriptions, accents -
    # and a sweep that stops halfway reports the sections below as missing.
    for _ in range(26):
        shell("input", "swipe", "540", "1300", "540", "950", "400")
        time.sleep(1.2)
        more, more_dump = ui_text(dev)
        texts += more
        dump += more_dump

    # The screen sets its section titles in capitals, so the comparison is on the words
    # rather than on their case.
    seen = {t.lower() for t in texts}
    for wanted in ("Phonetix", "How often", "Preview", "Apps"):
        r.check(wanted.lower() in seen, f"settings: the screen shows {wanted}", str(texts[:12]))

    # The frequency reads as one word in so many, and the preview shows what that does.
    r.check(
        any(re.match(r"1 in \d+", t) for t in texts),
        "settings: the frequency is stated as one word in so many",
        str([t for t in texts if "in" in t][:5]),
    )
    # In the words both platforms are written out of, whatever case the screen sets them in.
    r.check(
        any(t.lower() == "every word" for t in texts),
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
    # The reader's own choices, put back to what this suite measures against: a run that
    # followed one leaving a target language behind counted the words of an English page that
    # were being answered in German, and reported the frequency bar as broken.
    shell("am", "start", "-n", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity",
          "--es", "target", "none", "--es", "layer", "ipa", "--ei", "enable", "1")
    time.sleep(2)

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
    print("the notification shade")
    check_shade(r, dev)
    print("the button that switches it off")
    check_accessibility_button(r, dev)
    print("the app's own screen")
    check_settings_screen(r, dev)
    check_switch_in_ui(r, dev)

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
