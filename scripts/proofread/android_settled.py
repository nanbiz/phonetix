#!/usr/bin/env python3
"""Which word a spelling is, where the line it is on says, on a phone.

French "est" is "east" and it is "is"; "court" is "short" and it is "runs". Nothing on the page
decides which, and the core asks the host for the line translated: the engine reads the whole
line, and each reading's meaning is looked for in what it wrote. The phone used to answer that
request by translating the word on its own, which drew "est" as whatever a machine made of one
bare word and marked it a guess. The card the side button shows asks the same: it comes up with
what the word says alone and is drawn again once its line has been translated.

This reads the published French dictionary and Firefox's own French model on the emulator,
over two lines that ask the same two words the two different ways, and the published Spanish
dictionary over a line about a park bench, where "banco" has to be the bench and not the bank
its dictionary lists first.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_settled.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, card_while_held, shell

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PACKS = os.environ.get("PHONETIX_PACKS", os.path.join(ROOT, ".cache/release"))

# The page, the language it is in, and what the card has to lead with for words on it. On the
# French pages the words are undecided between readings; on the Spanish one "banco" is decided
# - the noun - and the line picks which of the noun's senses, a bank or a bench, it is.
PAGES = {
    "french": ("fr", {"est": "he is", "court": "he runs"}),
    "frenchShort": ("fr", {"est": "he is", "court": "short"}),
    "bench": ("es", {"banco": "bench", "siento": "I sit down"}),
}

# How long the model may take to arrive the first time, from Mozilla's own servers.
ARRIVES_WITHIN_S = 240


def main():
    dev = Device()
    failures = []
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    for lang in sorted({lang for lang, _ in PAGES.values()}):
        pack = os.path.join(PACKS, f"{lang}.pack")
        if not os.path.exists(pack):
            raise SystemExit(f"no pack at {pack}: tools/build_packs.sh writes it there")
        dev.give(pack, f"lex-{lang}.pack", "files")
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)

    # The models are fetched by the app from the published listing, as a reader's phone does:
    # no host set ("none" clears it), the page in French, English to read into.
    for lang in sorted({lang for lang, _ in PAGES.values()}):
        page = next(name for name, (of, _) in PAGES.items() if of == lang)
        dev.clear_log()
        dev.surface(mode=page, packHost="none", target="en", known="none", enable=1,
                    density=1)
        began = time.time()
        while time.time() - began < ARRIVES_WITHIN_S:
            if f"{lang}-en" in shell("run-as", "io.github.nanbiz.phonetix", "ls", "files/models"):
                # Opened, or opened already before this log began: a line settled by it says
                # the same.
                if f"TRANSLATOR {lang}-en open=true" in dev.lines("TRANSLATOR"):
                    break
            time.sleep(5)
        else:
            print(f"FAIL - the {lang} model never arrived or never opened")
            sys.exit(1)

    for page, (_, wanted) in PAGES.items():
        dev.surface(mode=page, packHost="none", target="en", known="none", enable=1, density=1)
        time.sleep(4)
        for word, meant in wanted.items():
            # Read as it stood when the finger lifted: the card comes up with what the word
            # alone says and is drawn again once its line has been translated behind it.
            texts, _ = card_while_held(dev, word, newest=True)
            lead = next((t for t in texts if t != word and not t.startswith("/")), None)
            line = re.findall(r"TOOLTIP line (?!says )(.*)", dev.lines("TOOLTIP line"))
            print(f"  {page}: the card for {word!r} leads with {lead!r} "
                  f"({texts or 'no card'}), the line said {line[-1:] or 'nothing'}")
            if not texts:
                failures.append(f"on {page} no card came up for {word!r}")
            elif lead != meant:
                failures.append(f"on {page} the card for {word!r} leads with {lead!r}, "
                                f"not {meant!r}")
            if not line:
                failures.append(f"on {page} the line under {word!r} was never translated")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the line translated decides which word a spelling is, on the card")


if __name__ == "__main__":
    main()
