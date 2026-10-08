#!/usr/bin/env python3
"""The phone's card at the detail the reader asked for.

The app's screen offers broad and narrow transcriptions, and nothing on the phone read the
setting: a card said /ˈtɛpɪç/ whichever was chosen, though the dictionary gives German
"Teppich" a narrow transcription as well, [ˈtʰɛ.pʰɪç]. This holds the side button on that word
with each setting in turn and reads the card while it is held.

The dictionary is built here from the German fixture, which carries the word as Wiktionary
writes it, both transcriptions and their delimiters.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_narrow.py
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, adb, card_while_held, shell  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PACK = "/tmp/phonetix-narrow-de.pack"

WANTED = {0: "/ˈtɛpɪç/", 1: "[ˈtʰɛ.pʰɪç]"}


def main():
    built = subprocess.run(
        ["cargo", "run", "-q", "-p", "packbuild", "--", "de",
         os.path.join(ROOT, "core", "packbuild", "fixtures", "de.jsonl"), PACK],
        cwd=os.path.join(ROOT, "core"), capture_output=True, text=True)
    if built.returncode != 0:
        raise SystemExit(f"packbuild failed: {built.stderr[:400]}")
    dev = Device()
    failures = []
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    adb("push", PACK, "/data/local/tmp/lex-de.pack", timeout=120)
    adb("shell", "run-as io.github.nanbiz.phonetix sh -c "
                 "'cat /data/local/tmp/lex-de.pack > files/lex-de.pack'", timeout=120)
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    for narrow, wanted in WANTED.items():
        dev.surface(mode="german", target="en", known="none", enable=1, density=1, narrow=narrow)
        time.sleep(5)
        texts, _ = card_while_held(dev, "Teppich")
        print(f"  {'narrow' if narrow else 'broad'}: {' | '.join(texts)}")
        if not texts:
            failures.append(f"no card came up for Teppich with narrow={narrow}")
        elif wanted not in texts:
            failures.append(f"with narrow={narrow} the card does not say {wanted}: {texts}")
    dev.surface(mode="german", narrow=0)
    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the card says the broad transcription, and the narrow one when it is asked for")


if __name__ == "__main__":
    main()
