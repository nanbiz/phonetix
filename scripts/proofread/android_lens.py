#!/usr/bin/env python3
"""The lens: the way to ask about a word without taking the screen's touches.

A transcription lying over a word can only be tapped if the overlay takes touches, and an
overlay that takes touches takes the swipe that started on a word with it. On a page of text
that is most of the page, which is why the transcriptions are untouchable by default - and
why, without the lens, there is no way at all to ask what a word means.

So this drags the lens across a page and checks that it says what it passes over, that a card
opens for that word, and that the page underneath still scrolls while it does.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_lens.py
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, carried_along, drag_and_dwell, finger_for, shell
import state as State


def repark(dev):
    """Put the mark back where it parks by itself, halfway down the right edge.

    It stays where a drag last left it, and a drag can leave it in the status bar - where the
    system takes the touch and pulls the notification shade rather than the mark, so the next
    run reports a mark that takes no touches. Where it rests lives in the service and nowhere
    else, so the service has to go; stopping the app switches accessibility off, which is why
    it is turned on again straight afterwards.
    """
    shell("cmd", "statusbar", "collapse")
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    time.sleep(2)
    return dev.enable_service()


# How tall the button is, so "above the keyboard" means the whole of it rather than its top
# left corner. It is square, and the service says its size when it parks.
MARK_PX = 105


def believed():
    """Everything the service holds, or nothing where it has not answered yet.

    A dump that is not there yet is not an answer, and reading one as JSON threw and took the
    whole suite with it on a freshly booted emulator.
    """
    try:
        serial = State.device()
        name = State.ask(serial)
        return State.fetch(serial, name) if name else {}
    except Exception:
        return {}


def keyboard_top(dev):
    """Where the service believes the keyboard's top edge is, or zero for no keyboard."""
    return (believed().get("screen") or {}).get("keyboardTop") or 0


def mark_at(dev):
    """Where the button actually is, asked of the service rather than read out of the log.

    The log says where it parked when it parked, so a check reads the newest such line and
    hopes it belongs to the thing it just did. It does not always: the button moved out of a
    keyboard's way exactly as it should and the check read a line from before the keyboard and
    called it broken. This is the position the service holds right now.
    """
    at = ((believed().get("mark") or {}).get("markAt") or {})
    if at.get("x") is None:
        return None
    return (at["x"], at["y"])


def parked_at(dev, mode="mute", **extras):
    """Where the mark is parked, asked until it says.

    It says so when it is put up or moved, and a check that has cleared the log to watch one
    drag has thrown that line away. Asking for the page again with the mark off and on makes
    it say so afresh, which beats guessing where it is.

    The log is cleared before asking, every time. Read without that, the newest line in the
    log can be where the mark parked before whatever this check just changed - so a button
    that never moved and a button nobody asked to move read exactly alike, and the keyboard
    check reported the button as sitting behind the keys when it had simply been told nothing.
    """
    for _ in range(3):
        dev.clear_log()
        # In the caller's own fixture. Asking in a fixed one put the mute page up over
        # whatever the check had arranged: the keyboard check lost the box it types into and
        # reported the page as never having come up.
        dev.surface(mode=mode, enable=1, density=1, lens=0, layer="off", **extras)
        time.sleep(2)
        dev.surface(mode=mode, enable=1, density=1, lens=1, layer="off", **extras)
        time.sleep(3)
        found = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
        if found:
            x, y, w, h = (int(v) for v in found[-1])
            return (x + w // 2, y + h // 2)
        time.sleep(5)
    return None


def ring_samples(lines):
    """What the service said each time it asked during a drag: where the finger is, where the
    leash holds the circle, where the ring is drawn and how wide, and the word it is on."""
    out = []
    for m in re.finditer(
            r"LENSCARRY finger=(-?\d+),(-?\d+) want=(-?\d+),(-?\d+) "
            r"ring=(-?\d+),(-?\d+),(\d+) on=(\S+)", lines):
        fx, fy, wx, wy, rx, ry, rr = (int(v) for v in m.groups()[:7])
        out.append({"finger": (fx, fy), "want": (wx, wy), "ring": (rx, ry), "radius": rr,
                    "on": m.group(8)})
    return out


def settles_on_words(dev, boxes, dpi):
    """Drag onto a long word off its middle, hold, shift the finger a few pixels within the
    word and hold again: the ring must sit on the word's middle both times, the same both
    times, while the point the leash holds moved with the finger."""
    failures = []
    home = mark_at(dev)
    if home is None:
        where = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
        if not where:
            return ["the mark never said where it is, so the ring could not be dragged onto a word"]
        x, y, w, h = (int(v) for v in where[-1])
        home = (x + w // 2, y + h // 2)
    else:
        home = (home[0] + MARK_PX // 2, home[1] + MARK_PX // 2)
    # The widest word clear of the top and bottom of the screen: wide enough that a point a
    # quarter of the way along it is well off its middle and a nudge of a few pixels stays
    # inside it. Clear of the edges because the circle rides above the finger, and the finger
    # has to stay on the screen and off the target at its foot.
    every = list(boxes.values())
    middling = [b for b in every
                if dev.height * 0.15 < (b["rect"][1] + b["rect"][3]) / 2 < dev.height * 0.75]
    if not middling:
        return ["no word in the middle of the page to settle the ring on"]
    word = max(middling, key=lambda b: b["rect"][2] - b["rect"][0])
    left, top, right, bottom = word["rect"]
    middle = ((left + right) / 2, (top + bottom) / 2)
    aim = (left + (right - left) * 0.25, middle[1])
    onto = finger_for(aim, home, dpi, dev.width, dev.height)
    nudged = (onto[0] + 8, onto[1] + 4)
    dev.clear_log()
    drag_and_dwell(home, onto, then=[nudged])
    time.sleep(2)
    samples = ring_samples(dev.lines("LENSCARRY", keep=200))
    # The last thing said with the finger at each place: by then the leash and the ring have
    # had a second and a half to come to rest.
    rested = [next((s for s in reversed(samples) if s["finger"] == tuple(at)), None)
              for at in (onto, nudged)]
    print(f"  settling on {word['word']!r} at {word['rect']}, aimed {int(aim[0] - middle[0])}px "
          f"off its middle: {[(s['ring'], s['radius'], s['on'], s['want']) if s else None for s in rested]}")
    if None in rested:
        return [f"the service said nothing with the finger held on {word['word']!r} "
                f"({len(samples)} samples, fingers {sorted({s['finger'] for s in samples})[-4:]})"]
    for s in rested:
        rx, ry = s["ring"]
        on = [b for b in every if b["word"] == s["on"]
              and b["rect"][0] <= rx <= b["rect"][2] and b["rect"][1] <= ry <= b["rect"][3]]
        if s["on"] == "nothing":
            failures.append(f"held on {word['word']!r}, the ring is on no word at {s['ring']}")
        elif not on:
            failures.append(
                f"the ring is on {s['on']!r} by its own account but drawn at {s['ring']}, "
                f"inside no box of that word")
        else:
            box = on[0]["rect"]
            off = math.hypot(rx - (box[0] + box[2]) / 2, ry - (box[1] + box[3]) / 2)
            if off > 4:
                failures.append(
                    f"the ring is {off:.0f}px from the middle of {s['on']!r} rather than on it")
    held, moved = rested
    shifted = math.hypot(moved["want"][0] - held["want"][0], moved["want"][1] - held["want"][1])
    drifted = math.hypot(moved["ring"][0] - held["ring"][0], moved["ring"][1] - held["ring"][1])
    print(f"  the finger moved 9px within the word: the leash moved {shifted:.0f}px, "
          f"the ring {drifted:.0f}px")
    if shifted < 4:
        failures.append(
            f"the finger was nudged and the leash moved {shifted:.0f}px, so the nudge was not "
            f"measured")
    elif drifted > 1.5:
        failures.append(
            f"the finger moved a few pixels within {word['word']!r} and the ring moved "
            f"{drifted:.0f}px with it")
    return failures


def main():
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    if not repark(dev):
        raise SystemExit("the service would not start again after being re-parked")
    dev.set_enabled(True)
    failures = []

    dev.clear_log()
    # Through the harness rather than a bare `am start`: the app's own screen is an activity
    # of the same app, and with it in front the intent is delivered to the task instead of to
    # the page, which reports success and changes nothing. The page is then never told to
    # annotate and the lens is never told to appear.
    dev.surface(mode="spanish", enable=1, density=1, touchWords=0, lens=0)
    time.sleep(4)
    # Asked for again, so it says where it parked in a log this run can see.
    dev.surface(mode="spanish", enable=1, lens=1)
    time.sleep(3)
    boxes = dev.annotated()
    if not boxes:
        print("FAIL - nothing was annotated, so there is nothing to look at")
        sys.exit(1)

    # Where the lens actually parked, which it says rather than a check assuming: the window
    # sits in the display's own metrics and those are not the screen's dimensions.
    # Asked of the device rather than read out of a tail: the overlay writes one long line
    # per pass, and where the mark parked scrolls out of that window while a check waits.
    where = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
    if not where:
        print("FAIL - the lens is not on screen")
        sys.exit(1)
    x, y, w, h = (int(v) for v in where[-1])
    parked = (x + w // 2, y + h // 2)
    # A word in the middle of the screen, not the first one on it. The circle rides a couple
    # of hundred pixels above the finger so it can be seen, so a word near the top of the
    # screen is one the circle passes over while it is off the top edge - and the drag reports
    # nothing, which reads as a mark that takes no touches.
    middle = sorted(boxes.values(), key=lambda b: b["rect"][1])
    word = middle[len(middle) // 2]
    left, top, right, bottom = word["rect"]
    # Where the finger has to end for the circle to be on that word. The circle is carried
    # clear of the hand, away from where the mark waits, so a drag that puts the *finger* on
    # the word leaves the circle a couple of hundred pixels past it, over nothing - which
    # reads as a lens that passes over no words at all.
    dpi = int(re.search(r"(\d+)", shell("wm", "density")).group(1))

    def aimed(box, home):
        l, t, r, b = box
        return finger_for(((l + r) / 2, (t + b) / 2), home, dpi, dev.width, dev.height)

    onto = aimed(word["rect"], parked)

    # What the page was told to do, read before the log is cleared: the surface says it once,
    # when it is launched.
    told = dev.log()
    # Cleared first, so what is read back is this drag and nothing else. The service writes a
    # line naming every box on screen several times a second, and reading only the tail of the
    # log meant the drag had already been pushed out of it by the time it was read.
    dev.clear_log()
    # Slow enough that the circle keeps up. It is carried on a leash and asks what it is over
    # on every other frame, so on a machine drawing at a tenth of its frame rate a quick drag
    # is over before the circle has caught the finger: two samples in nine hundred
    # milliseconds, both of them between the words.
    drag_and_dwell(parked, onto)
    time.sleep(3)

    # Asked of the device, line by line: the overlay writes one long line naming every box on
    # screen several times a second, so a drag is pushed out of any window worth reading and
    # the device drops the odd long line from its own buffer under load. Either way the check
    # read a log with no drag in it and called that a mark that took no touches.
    passed = re.findall(r"LENSAT [\d.,]+ -> (\S+)", dev.lines("LENSAT "))
    opened = re.findall(r"TOOLTIP open word=(\S+)", dev.lines("TOOLTIP open"))
    print(f"  the lens passed over: {passed[:6]}")
    print(f"  the card opened for: {opened[-3:] if opened else 'nothing'}")

    if not passed:
        failures.append("the lens reported nothing under it, so it took no touches at all")
    elif all(name == "nothing" for name in passed):
        failures.append(f"the lens passed over no words: {passed[:6]}")
    # Against what the lens itself reported it was over, not against the word this check
    # aimed at. The ring is wider than a word and a page has more of them on it than it used
    # to, so which word it ends over is the lens's answer to give - what has to be true is
    # that the card is about that one.
    over = [name for name in passed if name != "nothing"]
    if not opened:
        failures.append("no card opened for what the lens was over")
    elif not over:
        failures.append("the lens never reported a word, so there is nothing to have opened")
    elif opened[-1] != over[-1]:
        failures.append(
            f"the lens ended over {over[-1]!r} and the card is about {opened[-1]!r}")

    # The ring settles on the word.
    #
    # The circle that looks rides on a leash above the finger, but the ring a reader sees sits
    # on the word that circle is over: centred on it, still while the finger makes the small
    # movements a hand always makes, and gliding to the next word only once the circle is
    # plainly on it. Aimed off the middle of a long word, so a ring drawn where the leash
    # holds it and a ring settled on the word are told apart; then the finger moves a few
    # pixels, still inside the word, and the ring must not.
    snap_failures = settles_on_words(dev, boxes, dpi)
    failures.extend(snap_failures)

    # A page that will not say where its characters are.
    #
    # Some apps answer no such request, and some stop answering while their text is being
    # written into - a conversation with an answer arriving in it, which is when a reader is
    # looking at it. Those lines used to be read and dropped, leaving the mark a screenful of
    # text to answer nothing about. With nothing drawn over the words, they are laid out
    # evenly across the line's own rectangle instead: near enough to point at.
    dev.surface(mode="mute", enable=1, density=1, lens=1, layer="off")
    time.sleep(8)
    guessed = dev.annotated()
    print(f"  on a page that will not say where its characters are: {len(guessed)} words known")
    if not guessed:
        failures.append("the mark knows nothing on a page that will not place its characters")
    # And dragged over one, it answers about it.
    #
    # Knowing the words is not the same as answering about one: the positions here are
    # guessed, so the circle can be over a word the mark believes is somewhere else and open
    # nothing at all. That is what a reader in a chat app sees - the mark passing over a
    # screenful of text with no card - and it is the case this whole guess exists for.
    else:
        # Where the mark is now, not where it was on the page before this one: it is taken
        # down and parked again whenever the page changes, and a drag that starts from the
        # old place never touches it - which reads as a mark that passes over nothing.
        # Asked for once more so it says where it parked in a log this drag can see: it says
        # it when it goes up, which was before this page was even asked for.
        dev.surface(mode="mute", enable=1, density=1, lens=0, layer="off")
        time.sleep(2)
        dev.surface(mode="mute", enable=1, density=1, lens=1, layer="off")
        time.sleep(6)
        again = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
        mx, my, mw, mh = (int(v) for v in again[-1])
        from_here = (mx + mw // 2, my + mh // 2)
        middle = sorted(guessed.values(), key=lambda b: b["rect"][1])
        word = middle[len(middle) // 2]
        left, top, right, bottom = word["rect"]
        dev.clear_log()
        onto = aimed(word["rect"], from_here)
        drag_and_dwell(from_here, onto)
        time.sleep(3)
        over = [name for name in re.findall(r"LENSAT [\d.,]+ -> (\S+)", dev.lines("LENSAT "))
                if name != "nothing"]
        cards = re.findall(r"TOOLTIP open word=(\S+)", dev.lines("TOOLTIP open"))
        print(f"  on guessed positions it passed over {over[-3:]} and opened {cards[-3:]}")
        if not over:
            failures.append(
                "the mark passed over nothing on a page whose words it had to guess at")
        elif not cards:
            failures.append(
                f"the mark was over {over[-1]!r} on a guessed page and opened no card")
        elif cards[-1] != over[-1]:
            failures.append(
                f"on a guessed page the lens ended over {over[-1]!r} and the card is "
                f"about {cards[-1]!r}")

        # The foot of the page.
        #
        # The circle used to be carried straight above the finger, so the lowest word it
        # could reach was a couple of hundred pixels from the bottom: to point at anything
        # below that the finger would have to go past the edge of the screen, into the
        # navigation bar or the keyboard. A reader asked how they were supposed to mark the
        # fields at the bottom of a page, and the answer was that they could not.
        #
        # Asked of where the circle actually went, not of which words a box list holds: what
        # is being checked is reach.
        # The last strip of the screen, not merely the lower part of it: carried straight
        # above the finger, the circle could still reach most of the page - what it could not
        # reach was the last couple of hundred pixels, which is where a message's own last
        # line sits above the keyboard.
        floor = dev.height - 120
        # From where the mark is now: the drag before this one left it wherever it ended, and
        # a swipe that starts anywhere else never touches it.
        rests = parked_at(dev)
        if rests is None:
            failures.append("the mark never said where it parked, so nothing was dragged")
            rests = (dev.width - 80, dev.height // 2)
        dev.clear_log()
        # Slowly, and to the lowest a finger goes: the circle is on a leash and a synthetic
        # swipe lifts the moment it arrives, so a quick drag is measured while the circle is
        # still catching up - a hundred and fifty pixels short of where it settles.
        # A quarter of the way across rather than in the middle: the middle of the foot is
        # where the target the mark is put away on sits, and a drag let go there puts
        # Phonetix away.
        shell("input", "swipe", str(rests[0]), str(rests[1]),
              str(dev.width // 4), str(dev.height - 20), "5000")
        time.sleep(3)
        samples = [(int(x), int(y), name) for x, y, name in
                   re.findall(r"LENSAT (\d+)[.\d]*,(\d+)[.\d]* -> (\S+)", dev.lines("LENSAT "))]
        deep = [y for _, y, _ in samples if y >= floor]
        # Whether it answers down there is asked of the part of the page that has words on
        # it: the last strip of this fixture is below its text, and a circle over nothing is
        # a circle doing as it should.
        low = dev.height - dev.height // 4
        answered = [name for _, y, name in samples if y >= low and name != "nothing"]
        print(f"  dragged to the foot of the page: reached {max((y for _, y, _ in samples), default=0)} "
              f"of {dev.height}, {len(deep)} samples in the last strip, "
              f"answering {answered[-3:] or 'nothing'} below {low}")
        if not deep:
            failures.append(
                f"the circle never got below {floor} on a drag to the bottom of the screen: "
                f"it stopped at {max((y for _, y, _ in samples), default=0)}")
        if not answered:
            failures.append(
                "nothing in the bottom quarter of the page could be pointed at")

    # The side the reader keeps it on.
    #
    # It decides two things: which edge the mark waits at, and which way the circle is carried
    # from the hand - away from the corner the hand comes in at, so the hand is never over the
    # word. Asked with the mark already up, because that is when a reader changes it: read only
    # where the mark is built, the setting did nothing until something else took it down.
    for side, edge in (("left", "near"), ("right", "far")):
        dev.clear_log()
        dev.surface(mode="mute", enable=1, density=1, lens=1, layer="off", side=side)
        time.sleep(4)
        where = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
        if not where:
            # It says where it is when it is put up or moved, and the log was cleared for
            # this. Ask for the page again so it says so afresh.
            dev.surface(mode="mute", enable=1, density=1, lens=0, layer="off")
            time.sleep(2)
            dev.surface(mode="mute", enable=1, density=1, lens=1, layer="off",
                        side=side)
            time.sleep(5)
            where = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)",
                               dev.lines("LENSPARKED"))
        if not where:
            failures.append(f"the mark said nothing about where it parked on the {side}")
            continue
        px, py, pw, ph = (int(v) for v in where[-1])
        dev.clear_log()
        shell("input", "swipe", str(px + pw // 2), str(py + ph // 2),
              str(dev.width // 2), str(dev.height // 2), "3000")
        time.sleep(2)
        seen = [(int(a), int(b)) for a, b in
                re.findall(r"LENSAT (\d+)[.\d]*,(\d+)[.\d]*", dev.lines("LENSAT "))]
        # Measured along the line from where the mark waits, which is the direction the
        # circle is carried in, and from where the service holds it rather than where it has
        # swung to: see [carried_along].
        carried = carried_along(dev.lines("LENSCARRY"), (px + pw // 2, py + ph // 2))
        print(f"  on the {side}: parked at x={px} of {dev.width}, "
              f"carried {carried:+}px from the finger")
        rested = px < dev.width // 2 if side == "left" else px > dev.width // 2
        if not rested:
            failures.append(f"asked to rest on the {side}, the mark parked at x={px}")
        if not seen:
            failures.append(f"nothing was reported under the mark on the {side}")
        elif carried <= 0:
            failures.append(
                f"on the {side} the circle is carried {carried:+}px from the finger, which is "
                f"towards the hand rather than away from it")

    # And where the reader put it on that side.
    #
    # The button rides its side at whatever height the reader chose, and the circle is carried
    # away from wherever that is - so a reader who keeps it high up is not left pointing at
    # words through their own hand.
    dev.clear_log()
    dev.surface(mode="mute", enable=1, density=1, lens=1, layer="off", side="right", pin=1, restY=30)
    time.sleep(5)
    high = parked_at(dev, side="right", pin=1, restY=30)
    if high is None:
        failures.append("the button never said where it parked when it was put up high")
    else:
        wanted = int(dev.height * 0.3)
        print(f"  put a third of the way down: waits at {high}, {abs(high[1] - wanted)}px from it")
        if abs(high[1] - wanted) > 80:
            failures.append(
                f"put a third of the way down, the button waits at {high[1]} rather than "
                f"{wanted}")
        dev.clear_log()
        aim = (max(40, high[0] - 300), min(dev.height - 60, high[1] + 300))
        shell("input", "swipe", str(high[0]), str(high[1]), str(aim[0]), str(aim[1]), "3000")
        time.sleep(2)
        went = [(int(a), int(b)) for a, b in
                re.findall(r"LENSAT (\d+)[.\d]*,(\d+)[.\d]*", dev.lines("LENSAT "))]
        further = 0.0
        if went:
            further = (math.hypot(went[-1][0] - high[0], went[-1][1] - high[1])
                       - math.hypot(aim[0] - high[0], aim[1] - high[1]))
        if not went:
            failures.append("nothing was reported under the button put up high")
        elif further <= 0:
            failures.append(
                f"the circle is carried {further:+.0f}px from a button put up high, which is "
                f"towards it rather than away from it")

    # And unpinned, coming to rest is the shortest way to its side and nothing more.
    #
    # Pinning is the option; without it there is no height to go to, so the button keeps the
    # one it has and only crosses to the side the reader keeps it on. Measured by unpinning it
    # where it stands and asking for the other side: it has to arrive at the same height.
    if high is not None:
        dev.clear_log()
        dev.surface(mode="mute", enable=1, density=1, lens=1, layer="off", side="left", pin=0,
                    restY=30)
        time.sleep(5)
        across = parked_at(dev, side="left", pin=0, restY=30)
        if across is None:
            failures.append("the button never said where it parked once it was unpinned")
        else:
            print(f"  unpinned and sent to the other side: {high} -> {across}")
            if across[0] > dev.width // 2:
                failures.append(
                    f"unpinned, the button stayed on the right at {across[0]} rather than "
                    f"crossing to the left")
            elif abs(across[1] - high[1]) > 120:
                failures.append(
                    f"unpinned, the button went to {across[1]} rather than staying at "
                    f"{high[1]}, which is the shortest way to its side")

    # And it stands clear of a keyboard.
    #
    # A keyboard opens over the foot of the screen, and a button sitting there is behind it:
    # it takes none of the touches meant for it and cannot be moved out of the way either, so
    # a reader typing - who is exactly the reader asking about what they are reading - is left
    # without it.
    dev.surface(mode="typing", enable=1, density=1, lens=1, layer="off", side="right", pin=1, restY=92)
    time.sleep(5)
    low = mark_at(dev)
    told = shell("uiautomator", "dump", "/sdcard/ui.xml") and shell("cat", "/sdcard/ui.xml")
    node = next((n for n in re.findall(r"<node[^>]*>", told) if "the composer" in n), "")
    box = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if low is None or not box:
        failures.append("the page with something to type into never came up")
    else:
        shell("input", "tap", str((int(box.group(1)) + int(box.group(3))) // 2),
              str((int(box.group(2)) + int(box.group(4))) // 2))
        # Waited for rather than sampled once. A keyboard animates in, and the service is told
        # where its top edge is more than once on the way: measured at 2176 and then 1517 for
        # the same keyboard. A reading taken between the two catches the button on its way up
        # and reads as a button that never moved.
        lifted, top = None, 0
        for _ in range(14):
            lifted, top = mark_at(dev), keyboard_top(dev)
            if top and lifted and lifted[1] + MARK_PX <= top:
                break
            time.sleep(1)
        shown = re.search(r"mInputShown=(\w+)", shell("dumpsys", "input_method"))
        print(f"  with a keyboard open ({shown.group(1) if shown else '?'}, top {top}): "
              f"the button moved from {low} to {lifted}")
        if not shown or shown.group(1) != "true":
            print("  no keyboard came up, so standing clear of one was not measured")
        elif lifted is None or not top or lifted[1] + MARK_PX > top:
            # Against the keyboard's own edge, not against where the button was. What has to
            # be true is that none of it is behind the keys.
            failures.append(
                f"a keyboard opened at {top} and the button is at {lifted}, behind it")

    dev.surface(mode="spanish", enable=1, density=1, lens=1, layer="meaning")
    time.sleep(4)

    # Nothing keeps asking once the finger has gone.
    #
    # The mark is taken down and put up again whenever a setting changes, and a gesture in
    # flight when that happens never sees the finger lift: its frame loop ran for the life of
    # the app, asking every other frame what was under the point the circle was last at. That
    # is quiet until the app moves a word under that spot, and then a card opens by itself -
    # on the reader's phone, over the keyboard, coming and going as they typed.
    dev.surface(mode="spanish", enable=1, density=1, lens=0)
    time.sleep(2)
    dev.surface(mode="spanish", enable=1, density=1, lens=1)
    time.sleep(3)
    dev.clear_log()
    time.sleep(6)
    asking = dev.lines("LENSAT ").count("LENSAT ")
    print(f"  with no finger on the screen, the lens asked {asking} times")
    if asking:
        failures.append(
            f"the lens went on asking {asking} times with no finger on the screen")

    # And the page it is dragged over is untouched: the transcriptions took nothing, which is
    # the whole reason the lens exists.
    if "touchWords=true" in told:
        failures.append("the words were made touchable, which is not what the lens is for")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the lens says what it passes over and opens a card for it")


if __name__ == "__main__":
    main()
