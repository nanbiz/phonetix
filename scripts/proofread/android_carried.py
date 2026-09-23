#!/usr/bin/env python3
"""A phone with nothing configured, reading a language it was never told about.

Every language the product knows how to pronounce travels with it, as the gzipped map it has
always shipped as, and a language becomes a pack the first time a screen is read in it. Before
that, the app carried one pack - English, built at compile time - and a reader of anything else
met a screen it could not answer and a dictionary host to go and find.

Nothing is configured here and nothing is served: no host, no language to read into, no
dictionary pushed to the device. What the screen gets is what the app carries.

  PHONETIX_ANDROID_SERIAL=emulator-5556 uv run scripts/proofread/android_carried.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, adb, shell, drawn_pairs

# Spanish, because the page the surface draws is Spanish and the app is English: a language
# nobody told it about, answered out of what it carries.
SOUNDS = {"perro": "pero", "camino": "kamino", "silla": "siʎa"}


def main():
    dev = Device()
    failures = []

    # A reader who has just installed it: no settings, no dictionaries, no host.
    shell("pm", "clear", "io.github.nanbiz.phonetix")
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    dev.clear_log()
    dev.surface(mode="spanish", enable=1, density=1)

    # Building a pack out of a carried dictionary is seconds for a big language, and Spanish is
    # one of the big ones.
    boxes = dev.annotated(seconds=120)
    log = dev.log()
    # What was drawn, asked of the device rather than read out of a tail: waiting two minutes
    # for a pack to be built writes thousands of lines, and the answer had scrolled out of the
    # window this used to read - which reads exactly like a screen that was never annotated.
    #
    # Waited for, too. The first passes over a screen in a language the app has never built a
    # pack for draw nothing while it is being built, and a busy device drops the odd long line
    # from its own log, so the pass that says what it drew is not always the one that drew.
    said = {}
    for _ in range(20):
        for line in reversed(dev.lines("DRAWN ").splitlines()):
            if "DRAWN " not in line:
                continue
            said = dict(drawn_pairs(line))
            if said:
                break
        if said:
            break
        time.sleep(3)

    built = re.findall(r"CARRIED (\S+) opened as (\S+)", log)
    print(f"  what it built: {built[:3]}")
    print(f"  {len(boxes)} words annotated: {list(said.items())[:5]}")

    if not any(lang == "es" for lang, _ in built):
        failures.append(f"the Spanish dictionary it carries was never opened ({built})")
    if not boxes:
        failures.append("nothing was annotated on a screen in a language it carries")
    for word, sound in SOUNDS.items():
        if said.get(word) != sound:
            failures.append(f"{word} came back as {said.get(word)!r} rather than {sound!r}")

    # And nothing was fetched to do it: the point of carrying them.
    host = shell("run-as", "io.github.nanbiz.phonetix", "ls", "files")
    if "lex-" in host:
        failures.append(f"a dictionary was fetched from somewhere: {host.split()}")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a phone with nothing configured answers a language it carries")


if __name__ == "__main__":
    main()
