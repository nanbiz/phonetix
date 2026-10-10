#!/usr/bin/env python3
"""The phone's card for a word that is a form of another, read from the real Spanish dictionary.

A card over "corre" used to lead with "to run", the meaning of "correr", over a word that means
"he runs". It now says the meaning in the form the word has, names the form in grammar's own
terms and which word it is a form of ("present · indicative · 3rd singular of correr"), and
sets the word's own ending apart, with no stress marks while they are off. This holds the side button on such words of the Spanish page
and reads the card while it is held.

The dictionary is the real one, as the release publishes it: fixture packs are written for the
words a check asks about and hid every mistake real tables make. PHONETIX_ES_PACK names it
(default .cache/release/es.pack, which tools/build_packs.sh writes).

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_form.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, adb, card_while_held, shell  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PACK = os.environ.get("PHONETIX_ES_PACK", os.path.join(ROOT, ".cache", "release", "es.pack"))

# Words of the Spanish page that are forms of another word, what the card should say they mean,
# and how it should name the form.
WANTED = {
    "corre": ("he runs", "present · indicative · 3rd singular of correr"),
    "descansa": ("he rests", "present · indicative · 3rd singular of descansar"),
}


def main():
    if not os.path.exists(PACK):
        raise SystemExit(f"no Spanish dictionary at {PACK}: run tools/build_packs.sh")
    dev = Device()
    failures = []
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    adb("push", PACK, "/data/local/tmp/lex-es.pack", timeout=300)
    adb("shell", "run-as io.github.nanbiz.phonetix sh -c "
                 "'cat /data/local/tmp/lex-es.pack > files/lex-es.pack'", timeout=300)
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    dev.surface(mode="spanish", target="en", known="none", enable=1, density=1)
    time.sleep(6)
    def plain(text):
        """Text as the card's report gives it, which drops the dots between terms."""
        return " ".join(text.replace("·", " ").split())

    for word, (meaning, form) in WANTED.items():
        texts, _ = card_while_held(dev, word)
        joined = " | ".join(texts)
        print(f"  {word}: {joined}")
        if not texts:
            failures.append(f"no card came up for {word}")
            continue
        if meaning not in texts:
            failures.append(f"the card for {word} does not say {meaning!r}: {texts}")
        if plain(form) not in [plain(text) for text in texts]:
            failures.append(f"the card for {word} does not name its form as {form!r}: {texts}")
        # Stress marks are off unless the reader turns them on, on the card as over the page.
        if any(mark in joined for mark in "ˈˌ"):
            failures.append(f"the card for {word} shows stress marks with them off: {texts}")
    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a form's card says what it means in that form and which form of which word it is")


if __name__ == "__main__":
    main()
