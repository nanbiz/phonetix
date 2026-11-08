#!/usr/bin/env python3
"""What the card would do with a word, offered round the finger.

Dragged onto a word and kept there a second, the side button offers play and the word's page
round the finger. Lifted beside them nothing happens; slid onto the page and lifted, the word's
Wiktionary page opens in the browser; slid onto play and lifted, the word is said.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_actions.py
"""
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, drag_and_dwell, finger_for, shell
from android_lens import repark


def offered(dev):
    """The word the actions were offered for and where each is, newest, or nothing."""
    found = re.findall(r"LENSACTIONS (\S+) (\d+),(\d+) (\d+),(\d+) finger (\d+),(\d+) leash (-?\d+)",
                       dev.lines("LENSACTIONS"))
    if not found:
        return None
    word, px, py, ax, ay, fx, fy, leash = found[-1]
    return {"word": word, "play": (int(px), int(py)), "article": (int(ax), int(ay)),
            "finger": (int(fx), int(fy)), "leash": int(leash)}


def front():
    """The package of the activity in front."""
    out = shell("dumpsys", "activity", "activities")
    found = re.search(r"(?:topResumedActivity|mResumedActivity)[^\n]*? ([\w.]+)/", out)
    return found.group(1) if found else ""


def main():
    dev = Device()
    if not dev.enable_service() or not repark(dev):
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    failures = []

    def ready():
        """The page in front with its words drawn, and the button parked where it starts."""
        repark(dev)
        dev.set_enabled(True)
        dev.surface(mode="spanish", enable=1, density=1, touchWords=0, lens=1)
        time.sleep(4)
        boxes = dev.annotated()
        where = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
        if not boxes or not where:
            raise SystemExit("FAIL - no words drawn or no button parked")
        x, y, w, h = (int(v) for v in where[-1])
        return boxes, (x + w // 2, y + h // 2)

    boxes, parked = ready()
    dpi = int(re.search(r"(\d+)", shell("wm", "density")).group(1))
    # The widest word in the middle of the page, which a finger resting on it stays on.
    middling = [b for b in boxes.values()
                if dev.height * 0.25 < (b["rect"][1] + b["rect"][3]) / 2 < dev.height * 0.7]
    word = max(middling, key=lambda b: b["rect"][2] - b["rect"][0])
    left, top, right, bottom = word["rect"]
    onto = finger_for(((left + right) / 2, (top + bottom) / 2), parked, dpi, dev.width, dev.height)

    # Kept on the word, then lifted where the finger is: the actions open, and nothing is done.
    dev.clear_log()
    drag_and_dwell(parked, onto, dwell=2.4)
    time.sleep(2)
    first = offered(dev)
    acted = re.findall(r"ACTED (\S+)", dev.lines("ACTED"))
    print(f"  kept on {word['word']!r}: offered {first}, done {acted}")
    if not first:
        failures.append(f"keeping the button on {word['word']!r} offered nothing")
    else:
        for name in ("play", "article"):
            x, y = first[name]
            off = ((x - onto[0]) ** 2 + (y - onto[1]) ** 2) ** 0.5
            if abs(off - 76 * dpi / 160) > 8:
                failures.append(f"the {name} action is {off:.0f}px from the finger")
            if not (0 <= x <= dev.width and 0 <= y <= dev.height):
                failures.append(f"the {name} action is off the screen at {x},{y}")
        # One either side of the line the circle is carried along, the same angle off it, so
        # neither sits on the line the eye follows to the word.
        fx, fy = first["finger"]
        turns = [(math.degrees(math.atan2(first[n][1] - fy, first[n][0] - fx)) - first["leash"]
                  + 540) % 360 - 180 for n in ("play", "article")]
        print(f"  the actions off the leash: {[round(t) for t in turns]}")
        if abs(turns[0] + turns[1]) > 8 or min(abs(t) for t in turns) < 45:
            failures.append(f"the actions are not either side of the leash: {turns}")
    if acted:
        failures.append(f"lifting beside the actions did {acted}")

    # Taken far past the actions and held there: they close and the button is moving again.
    if first:
        boxes, parked = ready()
        dev.clear_log()
        away = (dev.width // 5 if onto[0] > dev.width // 2 else dev.width * 4 // 5, onto[1])
        drag_and_dwell(parked, onto, dwell=2.4, then=[away])
        time.sleep(2)
        left = dev.lines("LENSLEFT")
        acted = re.findall(r"ACTED (\S+)", dev.lines("ACTED"))
        print(f"  taken far past the actions: {'closed' if left else 'still open'}, done {acted}")
        if not left:
            failures.append("taking the finger far past the actions left them open")
        if acted:
            failures.append(f"leaving the actions did {acted}")

    for action in ("article", "play"):
        if not first:
            break
        boxes, parked = ready()
        dev.clear_log()
        drag_and_dwell(parked, onto, dwell=2.4, then=[first[action]])
        time.sleep(3)
        acted = re.findall(r"ACTED (\S+) (\S+)", dev.lines("ACTED"))
        top_app = front()
        print(f"  slid onto {action} and lifted: done {acted}, in front {top_app!r}")
        if not acted or acted[-1][0] != action:
            failures.append(f"lifting on {action} did {acted}")
        if action == "article" and top_app in ("", "io.github.nanbiz.phonetix"):
            failures.append(f"lifting on the article left {top_app!r} in front, no browser")
        if action == "article":
            shell("input", "keyevent", "KEYCODE_HOME")
            time.sleep(1)

    if failures:
        print("\nFAIL")
        for f in failures:
            print(f"  {f}")
        sys.exit(1)
    print("\nPASS - kept on a word, play and its page open round the finger, and lifting on one does it")


if __name__ == "__main__":
    main()
