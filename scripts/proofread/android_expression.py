#!/usr/bin/env python3
"""The phone's card for an expression, and for a word of a language on none of the lists.

"Me gusta" is "I like": held over "gusta" on a Spanish screen, the card says so and names each
word of it. And on an English screen quoting "¿Cómo salió todo?", a reader with no Spanish on
their lists gets "salió" said the Spanish way, and the screen is counted as one Spanish was met
on, which the settings offer once it is three.

The dictionaries are the published ones (PHONETIX_ES_PACK, PHONETIX_EN_PACK, default
.cache/release).

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_expression.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, adb, card_while_held, shell  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PACKS = {
    "es": os.environ.get("PHONETIX_ES_PACK", os.path.join(ROOT, ".cache", "release", "es.pack")),
    "en": os.environ.get("PHONETIX_EN_PACK", os.path.join(ROOT, ".cache", "release", "en.pack")),
}
PKG = "io.github.nanbiz.phonetix"


def main():
    dev = Device()
    failures = []
    shell("am", "force-stop", PKG)
    for lang, path in PACKS.items():
        adb("push", path, f"/data/local/tmp/lex-{lang}.pack", timeout=300)
        adb("shell", f"run-as {PKG} sh -c 'cat /data/local/tmp/lex-{lang}.pack > files/lex-{lang}.pack'",
            timeout=300)
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)

    dev.surface(mode="expression", target="en", known="none", learn="none", enable=1, density=1)
    time.sleep(10)
    texts, _ = card_while_held(dev, "gusta")
    print(f"  gusta: {' | '.join(texts)}")
    if "I like" not in texts:
        failures.append(f"the card over 'gusta' after 'Me' does not say I like: {texts}")
    if not any("to me" in text and "pleases" in text for text in texts):
        failures.append(f"the card does not name each word of the expression: {texts}")

    # Spanish on none of the lists: the reader reads English and learns nothing.
    dev.surface(mode="quoted", target="en", known="none", learn="none", enable=1, density=1)
    time.sleep(6)
    texts, _ = card_while_held(dev, "salió")
    print(f"  salió: {' | '.join(texts)}")
    if not texts or any("æ" in text for text in texts):
        failures.append(f"'salió' is not said the Spanish way: {texts}")
    prefs = adb("shell", f"run-as {PKG} cat shared_prefs/phonetix.settings.xml", timeout=30)
    met = [line for line in prefs.splitlines() if 'name="met"' in line]
    print(f"  met: {met}")
    if not met or "es" not in met[0]:
        failures.append(f"the screen was not counted as one Spanish was met on: {met}")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - an expression's card says what it means as one and names its words, and a "
          "word of a language on none of the lists is said in it and counted")


if __name__ == "__main__":
    main()
