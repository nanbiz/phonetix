#!/usr/bin/env python3
"""What the app believes, taken off the device and printed.

A bug in what the overlay draws is a bug in what it believes, and none of that can be seen
from outside: an overlay window is invisible to anything reading the screen, so a screenshot
shows a card without saying which word it is about, and a log line carries one pass's worth of
one decision. This asks the service for the whole of it - the settings in force, the screen it
thinks it is on, every word it believes is there and where, which one the mark is over, and
what the card that is up is actually about - and pulls the file off the device.

Asked for at the moment the thing is going wrong: hold the mark over a word, leave it there,
and run this from the machine.

  uv run python scripts/proofread/state.py                    # the phone, found by adb-phone
  PHONETIX_ANDROID_SERIAL=emulator-5556 uv run python scripts/proofread/state.py
  uv run python scripts/proofread/state.py --raw              # the whole JSON, not a summary
  uv run python scripts/proofread/state.py --keep out.json    # and keep it here

It grows: when a dump turns out to be missing what an investigation needed, the field goes
into StateDump rather than into a one-off log line.
"""
import argparse
import json
import os
import subprocess
import sys
import time

PKG = "io.github.nanbiz.phonetix"
ACTION = f"{PKG}.DUMP"


def device():
    """Which device to ask. The named one, the only one, or the phone."""
    named = os.environ.get("PHONETIX_ANDROID_SERIAL")
    if named:
        return named
    found = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    alive = [line.split("\t")[0] for line in found.splitlines()[1:]
             if line.strip().endswith("\tdevice")]
    if len(alive) == 1:
        return alive[0]
    phone = subprocess.run(["adb-phone"], capture_output=True, text=True).stdout.strip()
    if phone:
        return phone.splitlines()[0]
    raise SystemExit(f"which device? {alive or 'none attached'} "
                     f"(set PHONETIX_ANDROID_SERIAL)")


def shell(serial, *args, timeout=60):
    return subprocess.run(["adb", "-s", serial, "shell", *args],
                          capture_output=True, text=True, timeout=timeout).stdout


def ask(serial):
    """Ask for a dump and answer with what the service says it wrote."""
    before = shell(serial, f"run-as {PKG} ls files/state 2>/dev/null").split()
    shell(serial, "am", "broadcast", "-a", ACTION, "-p", PKG)
    for _ in range(20):
        time.sleep(0.4)
        now = shell(serial, f"run-as {PKG} ls files/state 2>/dev/null").split()
        fresh = [name for name in now if name not in before]
        if fresh:
            return sorted(fresh)[-1]
        if now and not before:
            return sorted(now)[-1]
    return None


def fetch(serial, name):
    # Through run-as, because the app's own directory is not readable otherwise and this has
    # to work on a phone that is not rooted.
    out = subprocess.run(
        ["adb", "-s", serial, "exec-out", f"run-as {PKG} cat files/state/{name}"],
        capture_output=True, timeout=60)
    return json.loads(out.stdout.decode("utf-8", "replace"))


def summarise(state):
    settings = state.get("settings", {})
    screen = state.get("screen", {})
    overlay = state.get("overlay") or {}
    mark = state.get("mark") or {}
    card = state.get("card") or {}
    print(f"  app       {state.get('app', {}).get('version')} "
          f"{'debug' if state.get('app', {}).get('debug') else 'release'}")
    print(f"  settings  on={settings.get('on')} layer={settings.get('layer')} "
          f"density={settings.get('density')} into={settings.get('into')!r} "
          f"learning={settings.get('learning')!r} lens={settings.get('lens')}")
    print(f"  screen    {screen.get('package')} in {screen.get('language')} "
          f"lines={screen.get('lines')} replaced={screen.get('pageReplaced')} "
          f"following={screen.get('following')}")
    print(f"  overlay   {overlay.get('words')} words believed, "
          f"{overlay.get('chipsShown')}/{overlay.get('chips')} drawn, "
          f"silent={overlay.get('silent')} moving={overlay.get('inMotion')}")
    looking = mark.get("lookingAt", {})
    hovered = mark.get("hovered")
    print(f"  mark      at {mark.get('markAt')} dragging={mark.get('dragging')} "
          f"looking at ({looking.get('x')},{looking.get('y')}) "
          f"over {hovered.get('word') if hovered else None!r} "
          f"sweeping={mark.get('sweeping')}")
    ask = state.get("ask") or {}
    if ask.get("panelUp") or ask.get("asked"):
        print(f"  ask       panel={ask.get('panelUp')} {ask.get('asked')!r} into "
              f"{ask.get('into')!r}: {ask.get('stage')} ({ask.get('waitingMs')}ms ago)")
        if ask.get("machineTrouble"):
            print(f"  ENGINE    {ask['machineTrouble']}")
    about = card.get("about")
    print(f"  card      up={card.get('up')} about "
          f"{about.get('word') if about else None!r} at {card.get('card')}")
    # The one comparison this exists for: a card about a word the mark is not over.
    if card.get("up") and hovered and about and hovered.get("word") != about.get("word"):
        print(f"  MISMATCH  the mark is over {hovered.get('word')!r} "
              f"and the card is about {about.get('word')!r}")
    for near in mark.get("nearest", [])[:3]:
        print(f"  near      {near['word']!r} {near['pastLeftOrRight']}px to the side, "
              f"{near['aboveOrBelow']}px above or below"
              f"{' (the circle is inside it)' if near['inside'] else ''}")
    if hovered and looking.get("x", -1) >= 0:
        r = hovered.get("rect", {})
        inside = (r.get("left", 0) <= looking.get("x", -1) <= r.get("right", 0)
                  and r.get("top", 0) <= looking.get("y", -1) <= r.get("bottom", 0))
        if not inside:
            print(f"  REACHED   the circle is not on the word it took: "
                  f"({looking.get('x')},{looking.get('y')}) against {r}")


def main():
    parsed = argparse.ArgumentParser(description=__doc__)
    parsed.add_argument("--raw", action="store_true", help="print the whole JSON")
    parsed.add_argument("--keep", help="write the JSON to this path as well")
    parsed.add_argument("--words", type=int, default=0,
                        help="print this many of the words the overlay believes in")
    args = parsed.parse_args()

    serial = device()
    # Whether the thing being asked is running at all. Installing the app switches its
    # accessibility service off, so the commonest reason for silence is that nobody has
    # turned it back on - which is worth saying rather than leaving as "no dump".
    bound = shell(serial, "dumpsys", "accessibility")
    if "Phonetix transcriptions" not in bound.split("Bound services:", 1)[-1].split("\n", 1)[0]:
        raise SystemExit(
            f"{serial}: the accessibility service is not running, so there is no state to "
            f"ask for. Switch Phonetix on again (installing the app switches it off).")
    name = ask(serial)
    if not name:
        raise SystemExit(
            f"{serial} wrote no dump: is this a build that carries StateDump?")
    state = fetch(serial, name)
    if args.keep:
        with open(args.keep, "w") as f:
            json.dump(state, f, indent=2)
    print(f"{serial}: {name}")
    if args.raw:
        print(json.dumps(state, indent=2))
        return
    summarise(state)
    if args.words:
        for box in (state.get("overlay") or {}).get("boxes", [])[:args.words]:
            print(f"    {box['word']!r} -> {box['drawn']!r} at {box['rect']}")


if __name__ == "__main__":
    main()
