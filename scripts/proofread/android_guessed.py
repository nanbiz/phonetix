#!/usr/bin/env python3
"""Where the words are on a page that will not say where its characters are.

An app answers `refreshWithExtraData` with the rectangle of every character of a line, or it
refuses. The reader's own chat app refuses, and a line nobody will place has no words: the
mark passes over a screenful of text with nothing to answer about, which is what "the lens
does not work" looks like from the outside.

So the layout is guessed from the shape of the block and the number of characters in it. A
guess is only worth having if it lands on the word the reader is pointing at, and that cannot
be judged from a screenshot - both fixtures here hold the same words in the same place, and
one of them answers the request:

  mute    the page refusing, so every box on it is a guess
  spoken  the same page answering, so every box on it is the truth

The service is force-stopped between them, because a layout it measured once is remembered and
would be handed back instead of guessed.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_guessed.py

Measured on the build this was written for: 149 of 160 words within half a word of where they
really are, median 34px sideways and 5px down. Before it, with each block's own box deciding
how tall its rows were, 87 of 160 and 281px sideways - whole paragraphs on their first row.
"""
import os
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell
import state as State

PKG = "io.github.nanbiz.phonetix"
# How many of the words must land on the word they are about. A guess will never be all of
# them: a row that breaks one word early moves everything after it, and nothing here knows
# the widths of the glyphs the app actually drew.
ENOUGH = 0.85


def boxes(device, serial, mode):
    """What the overlay believes about the words of this page, word by word."""
    # The app is stopped rather than only switched pages: a line measured once is remembered
    # against its text, so the guess would never run on words the truth pass has just placed.
    shell("am", "force-stop", PKG)
    time.sleep(1)
    device.enable_service()
    # Nothing drawn over the words, which is the only state a guess is made in: with the
    # overlay painting, a guessed position would put a transcription on the wrong word, so
    # the app does not guess at all. Left to whatever the last check set, this measured a
    # page that was never guessed about.
    device.surface(mode, enable=1, density=1, lens=1, layer="off", target="none")
    time.sleep(1)
    device.annotated(seconds=40)
    time.sleep(2)
    name = State.ask(serial)
    if not name:
        raise SystemExit(f"{mode}: the service wrote no dump")
    dump = State.fetch(serial, name)
    found = {}
    for box in (dump.get("overlay") or {}).get("boxes", []):
        found.setdefault(box["word"].lower(), box["rect"])
    refused = (dump.get("screen") or {}).get("linesWithoutCharacters") or 0
    print(f"  {mode}: {len(found)} words, {refused} lines the page would not place")
    return found, refused


def main():
    serial = State.device()
    os.environ.setdefault("PHONETIX_ANDROID_SERIAL", serial)
    device = Device()
    truth, answered = boxes(device, serial, "spoken")
    guess, refused = boxes(device, serial, "mute")
    if answered:
        print("\nFAIL - the 'spoken' page refused to place its own lines, so there is no "
              "truth to measure against")
        sys.exit(1)
    if not refused:
        print("\nFAIL - the 'mute' page placed its lines, so nothing here was guessed at all")
        sys.exit(1)

    shared = sorted(set(truth) & set(guess))
    if len(shared) < 100:
        print(f"\nFAIL - only {len(shared)} words appear on both pages")
        sys.exit(1)
    side, down, hit = [], [], 0
    for word in shared:
        t, g = truth[word], guess[word]
        side.append(abs(t["left"] - g["left"]))
        down.append(abs(t["top"] - g["top"]))
        # On the word: within half its own width and half its own height, which is what
        # deciding between one word and its neighbour comes down to.
        if (side[-1] < (t["right"] - t["left"]) / 2
                and down[-1] < (t["bottom"] - t["top"]) / 2):
            hit += 1
    print(f"  {hit} of {len(shared)} words within half a word, "
          f"median {statistics.median(side):.0f}px sideways, "
          f"{statistics.median(down):.0f}px down")
    worst = max(shared, key=lambda w: abs(truth[w]["left"] - guess[w]["left"]))
    print(f"  furthest out: {worst!r} really at "
          f"({truth[worst]['left']}, {truth[worst]['top']}), guessed at "
          f"({guess[worst]['left']}, {guess[worst]['top']})")
    if hit < len(shared) * ENOUGH:
        print(f"\nFAIL - {hit} of {len(shared)} words land on themselves, "
              f"fewer than the {ENOUGH:.0%} this asks for")
        sys.exit(1)
    print("\nPASS - a page that will not place its words is guessed close enough to point at")


if __name__ == "__main__":
    main()
