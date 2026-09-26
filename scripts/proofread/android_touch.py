#!/usr/bin/env python3
"""A tap asks about a word; a press held takes the overlay off the page.

The two used to be the other way round, and neither was what a reader reached for: a tap put
the word back for a moment - a thing nobody asked for - and the card, which is the whole
answer, was behind a gesture nobody guesses. And with the overlay off there was nothing to
touch at all, so a reader who wanted to be asked rather than answered over could not ask.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_touch.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell, SERIAL
import state as State


def believed():
    try:
        name = State.ask(SERIAL)
        return State.fetch(SERIAL, name) if name else {}
    except Exception:
        return {}


def words(least=1, tries=12):
    """What the overlay knows about the screen, waited for."""
    for _ in range(tries):
        told = (believed().get("overlay") or {})
        boxes = told.get("boxes") or []
        if len(boxes) >= least:
            return told, boxes
        time.sleep(2)
    return (believed().get("overlay") or {}), []


def fresh(dev, **extras):
    """Put the page up as a reader arrives at it, from somewhere else.

    Asked for while it is already in front, the page is reordered rather than opened, the
    system reports no change, and nothing is read - so the rows that take touches, which went
    down when the last screen did, never come back. That is the check driving the fixture
    wrongly, not the product: a reader arrives at a screen from another one.
    """
    shell("input", "keyevent", "3")
    time.sleep(2)
    # Picked up, whatever the run before left behind: put down, the app draws nothing and
    # takes no touches, which is the thing two of these checks are about proving.
    dev.surface(mode="plain", enable=1, density=1, target="none", touchWords=1, paused=0,
                **extras)
    time.sleep(6)


def middle(box):
    r = box["rect"]
    return (r["left"] + r["right"]) // 2, (r["top"] + r["bottom"]) // 2


def main():
    dev = Device()
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    failures = []

    # With the overlay on: a tap answers the word it landed on.
    fresh(dev, layer="sound")
    shown, boxes = words()
    if not boxes:
        print("FAIL - nothing was transcribed, so there is no word to touch")
        sys.exit(1)
    print(f"  the overlay is on: {shown.get('chipsShown')} words drawn")
    dev.clear_log()
    at = middle(boxes[0])
    asked = boxes[0]["word"]
    shell("input", "tap", str(at[0]), str(at[1]))
    time.sleep(3)
    opened = re.findall(r"TOOLTIP open word=(\S+)", dev.log())
    print(f"  tapped {asked!r}: the card is about {opened[-1:] or 'nothing'}")
    if not opened:
        failures.append("tapping a word opened no card")
    elif opened[-1] != boxes[0]["word"]:
        failures.append(f"tapped {boxes[0]['word']!r} and the card was about {opened[-1]!r}")

    # A press held takes every transcription off the page, so the page can be read as its own
    # app wrote it - not one word lifted from under the fingertip that is covering it.
    fresh(dev, layer="sound")
    shown, boxes = words()
    before = shown.get("chipsShown") or 0
    at = middle(boxes[0]) if boxes else at
    shell("input", "swipe", str(at[0]), str(at[1]), str(at[0]), str(at[1]), "800")
    time.sleep(1)
    lifted = ((believed().get("overlay") or {}).get("chipsShown")) or 0
    print(f"  held on a word: {before} drawn before, {lifted} after")
    if before and lifted:
        failures.append(f"a press held left {lifted} of {before} transcriptions on the page")

    # And with the overlay off a word can still be asked about: nothing is drawn, and a tap
    # still answers.
    fresh(dev, layer="off")
    shown, boxes = words()
    drawn = shown.get("chipsShown") or 0
    print(f"  the overlay is off: {drawn} drawn, {len(boxes)} words known, "
          f"{shown.get('touchable')} lines taking touches")
    if drawn:
        failures.append(f"the overlay is off and {drawn} transcriptions are on the page")
    if not boxes:
        failures.append("the overlay is off and no word is known, so none can be asked about")
    else:
        # A word this run has not asked about yet: a card open on a word closes when that same
        # word is tapped again, which is the card working and would read here as a tap that
        # did nothing.
        word = next((b for b in boxes if b["word"] != asked), boxes[0])
        dev.clear_log()
        at = middle(word)
        shell("input", "tap", str(at[0]), str(at[1]))
        time.sleep(3)
        opened = re.findall(r"TOOLTIP open word=(\S+)", dev.log())
        print(f"  tapped {word['word']!r} with nothing drawn: "
              f"the card is about {opened[-1:] or 'nothing'}")
        if not opened:
            failures.append("with the overlay off, tapping a word opened no card")

    # Paused is not the same as having nothing to draw. Held down, the app is out of the way
    # altogether: nothing is painted and nothing takes a touch, because a word that answers
    # when it is tapped is not out of the way.
    fresh(dev, layer="sound")
    shown, boxes = words()
    at = middle(boxes[0]) if boxes else None
    if at:
        shell("input", "swipe", str(at[0]), str(at[1]), str(at[0]), str(at[1]), "50")
    mark = ((believed().get("mark") or {}).get("markAt") or {})
    if not mark:
        failures.append("the button is not on screen, so the overlay cannot be put down")
    else:
        # A press held on the button, which is what puts it down.
        shell("input", "swipe", str(mark["x"] + 52), str(mark["y"] + 52),
              str(mark["x"] + 52), str(mark["y"] + 52), "900")
        time.sleep(4)
        told = believed()
        paused = (told.get("settings") or {}).get("paused")
        after = (told.get("overlay") or {})
        print(f"  the button was held: paused={paused}, {after.get('chipsShown')} drawn, "
              f"{after.get('touchable')} lines taking touches")
        if not paused:
            failures.append("a press held on the button did not put the overlay down")
        if after.get("chipsShown"):
            failures.append(f"{after.get('chipsShown')} transcriptions are still drawn")
        if after.get("touchable"):
            failures.append(
                f"{after.get('touchable')} lines still take touches while it is put down")
        if boxes:
            word = boxes[0]
            dev.clear_log()
            at = middle(word)
            shell("input", "tap", str(at[0]), str(at[1]))
            time.sleep(3)
            if re.findall(r"TOOLTIP open word=(\S+)", dev.log()):
                failures.append("the overlay is put down and tapping a word still answered")
        # And picked up again, so the next run starts where this one found things.
        shell("input", "swipe", str(mark["x"] + 52), str(mark["y"] + 52),
              str(mark["x"] + 52), str(mark["y"] + 52), "900")
        time.sleep(3)

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a tap asks about a word, a press held lifts the overlay, and both work "
          "with the overlay off")


if __name__ == "__main__":
    main()
