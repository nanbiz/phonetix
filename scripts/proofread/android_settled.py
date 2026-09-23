#!/usr/bin/env python3
"""Which word a spelling is, where the line it is on says, on a phone.

French "est" is "east" and it is "is"; "court" is "short" and it is "runs". Nothing on the page
decides which, and the core asks the host for the line translated: the engine reads the whole
line, and each reading's meaning is looked for in what it wrote. The phone used to answer that
request by translating the word on its own, which drew "est" as whatever a machine made of one
bare word and marked it a guess.

This reads the published French dictionary and Firefox's own French model on the emulator,
over two lines that ask the same two words the two different ways.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_settled.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, drawn_pairs, shell

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PACK = os.environ.get("PHONETIX_FR_PACK", os.path.join(ROOT, ".cache/release/fr.pack"))

# The page, and what each undecided word on it has to be drawn as.
PAGES = {
    "french": {"est": "to be", "court": "to run"},
    "frenchShort": {"est": "to be", "court": "short"},
}

# How long the model may take to arrive the first time, from Mozilla's own servers.
ARRIVES_WITHIN_S = 240


def main():
    if not os.path.exists(PACK):
        raise SystemExit(f"no French pack at {PACK}: tools/build_packs.sh writes it there")
    dev = Device()
    failures = []
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    dev.give(PACK, "lex-fr.pack", "files")
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)

    # The model is fetched by the app from the published listing, as a reader's phone does:
    # no host set ("none" clears it), the page in French, English to read into.
    dev.clear_log()
    dev.surface(mode="french", packHost="none", target="en", layer="meaning", enable=1, density=1)
    began = time.time()
    while time.time() - began < ARRIVES_WITHIN_S:
        if "fr-en" in shell("run-as", "io.github.nanbiz.phonetix", "ls", "files/models"):
            if "TRANSLATOR fr-en open=true" in dev.lines("TRANSLATOR"):
                break
        time.sleep(5)
    else:
        print("FAIL - the French model never arrived or never opened")
        sys.exit(1)

    for page, wanted in PAGES.items():
        dev.clear_log()
        dev.surface(mode=page, packHost="none", target="en", layer="meaning", enable=1, density=1)
        said = {}
        until = time.time() + 60
        while time.time() < until:
            for line in reversed(dev.lines("DRAWN ").splitlines()):
                pairs = dict(drawn_pairs(line))
                if all(word in pairs for word in wanted):
                    said = pairs
                    break
            if said and all(said.get(word) == meant for word, meant in wanted.items()):
                break
            time.sleep(2)
        settled = re.findall(r"SETTLED (\d+) words", dev.lines("SETTLED"))
        print(f"  {page}: {[(word, said.get(word)) for word in wanted]}, "
              f"settled {settled[-1] if settled else 'nothing'} by the line")
        for word, meant in wanted.items():
            if said.get(word) != meant:
                failures.append(f"on {page} {word!r} is drawn {said.get(word)!r}, not {meant!r}")
        if not settled:
            failures.append(f"on {page} no line was translated to decide anything")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the line translated decides which word a spelling is")


if __name__ == "__main__":
    main()
