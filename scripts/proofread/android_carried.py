#!/usr/bin/env python3
"""A phone with nothing configured, reading a language it was never told about.

Every language the product knows how to pronounce travels with it, as the gzipped map it has
always shipped as, and a language becomes a pack the first time a screen is read in it. Before
that, the app carried one pack - English, built at compile time - and a reader of anything else
met a screen it could not answer and a dictionary host to go and find.

Nothing is configured here and nothing is served: no host, no language to read into, no
dictionary pushed to the device, and no network at all. A phone with nothing configured fetches
a page's dictionary from the published release by itself, and a fetched dictionary answering
the card would say nothing about what the app carries. The side button is held over Spanish words and the card it
shows has to say how each is said, out of what the app carries.

  PHONETIX_ANDROID_SERIAL=emulator-5556 uv run scripts/proofread/android_carried.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, card_while_held, shell

# Spanish, because the page the surface draws is Spanish and the app is English: a language
# nobody told it about, answered out of what it carries. Compared without the stress marks and
# syllable breaks, which the card shows and the map's own spelling of a word does not decide.
SOUNDS = {"perro": "pero", "camino": "kamino", "silla": "siʎa"}


def bare(ipa):
    return re.sub(r"[/ˈˌ.\s]", "", ipa)


def main():
    dev = Device()
    failures = []

    # A reader who has just installed it: no settings, no dictionaries, no host, and no
    # connection to fetch one over.
    shell("svc", "wifi", "disable")
    shell("svc", "data", "disable")
    try:
        check(dev, failures)
    finally:
        shell("svc", "wifi", "enable")
        shell("svc", "data", "enable")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a phone with nothing configured says how the words of a language it carries sound")


def check(dev, failures):
    shell("pm", "clear", "io.github.nanbiz.phonetix")
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.clear_log()
    dev.surface(mode="spanish", enable=1, density=1)

    # Building a pack out of a carried dictionary is seconds for a big language, and Spanish is
    # one of the big ones. Waited for by the line that says it opened, asked of the device
    # rather than read out of a tail that the screen's own reading pushes it out of.
    built = []
    for _ in range(40):
        built = re.findall(r"CARRIED (\S+) opened as (\S+)", dev.lines("CARRIED "))
        if any(lang == "es" for lang, _ in built):
            break
        time.sleep(3)
    print(f"  what it built: {built[:3]}")
    if not any(lang == "es" for lang, _ in built):
        failures.append(f"the Spanish dictionary it carries was never opened ({built})")

    # Built out of what it carries rather than fetched. The dictionary of what Spanish words
    # mean is fetched from the published packs by itself now, which is the point of that; how
    # they are said has to be here before anything arrives.
    held = shell("run-as", "io.github.nanbiz.phonetix", "ls", "files").split()
    if "ipa-es.pack" not in held:
        failures.append(f"no pronunciation pack was built from what the app carries: {held}")

    for word, sound in SOUNDS.items():
        texts, _ = card_while_held(dev, word)
        said = [t for t in texts if t.startswith("/")]
        print(f"  the card for {word} says {said or texts or 'nothing'}")
        if not texts:
            failures.append(f"no card came up for {word}")
        elif not any(bare(t) == sound for t in said):
            failures.append(f"the card says {word} is {said!r} rather than /{sound}/")


if __name__ == "__main__":
    main()
