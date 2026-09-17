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
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell


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
    # clear above the hand so the word can be seen, so a drag that puts the *finger* on the
    # word leaves the circle a couple of hundred pixels above it, over nothing - which reads
    # as a lens that passes over no words at all.
    lift = int(dev.height * 0.09)
    onto = ((left + right) // 2, (top + bottom) // 2 + lift)

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
    shell("input", "swipe", str(parked[0]), str(parked[1]), str(onto[0]), str(onto[1]), "2500")
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
        shell("input", "swipe", str(from_here[0]), str(from_here[1]),
              str((left + right) // 2), str((top + bottom) // 2 + lift), "2500")
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
        dev.surface(mode="mute", enable=1, density=1, lens=0, layer="off")
        time.sleep(2)
        dev.surface(mode="mute", enable=1, density=1, lens=1, layer="off")
        time.sleep(6)
        rest = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
        rx, ry, rw, rh = (int(v) for v in rest[-1])
        dev.clear_log()
        # Slowly, and to the lowest a finger goes: the circle is on a leash and a synthetic
        # swipe lifts the moment it arrives, so a quick drag is measured while the circle is
        # still catching up - a hundred and fifty pixels short of where it settles.
        shell("input", "swipe", str(rx + rw // 2), str(ry + rh // 2),
              str(dev.width // 2), str(dev.height - 20), "5000")
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
            dev.surface(mode="mute", enable=1, density=1, lens=1, layer="off", side=side)
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
        carried = (seen[-1][0] - dev.width // 2) if seen else 0
        print(f"  on the {side}: parked at x={px} of {dev.width}, "
              f"carried {carried:+}px from the finger")
        rested = px < dev.width // 2 if side == "left" else px > dev.width // 2
        if not rested:
            failures.append(f"asked to rest on the {side}, the mark parked at x={px}")
        if not seen:
            failures.append(f"nothing was reported under the mark on the {side}")
        elif (carried > 0) != (side == "left"):
            failures.append(
                f"on the {side} the circle is carried {carried:+}px from the finger, which is "
                f"towards the hand rather than away from it")

    # And never where a word is drawn over: there, a guessed position is a transcription on
    # the wrong word.
    dev.surface(mode="mute", enable=1, density=1, lens=1, layer="sound", target="none")
    time.sleep(8)
    drawn = dev.annotated()
    if drawn:
        failures.append(
            f"{len(drawn)} words were drawn from guessed positions, which puts a "
            f"transcription on the wrong word")
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
