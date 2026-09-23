#!/usr/bin/env python3
"""Which word a spelling is, where the line it is on says, on a phone.

French "est" is "east" and it is "is"; "court" is "short" and it is "runs". Nothing on the page
decides which, and the core asks the host for the line translated: the engine reads the whole
line, and each reading's meaning is looked for in what it wrote. The phone used to answer that
request by translating the word on its own, which drew "est" as whatever a machine made of one
bare word and marked it a guess.

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
from android_harness import Device, drawn_pairs, shell

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PACKS = os.environ.get("PHONETIX_PACKS", os.path.join(ROOT, ".cache/release"))

# The page, the language it is in, and what words on it have to be drawn as. On the French
# pages the words are undecided between readings; on the Spanish one "banco" is decided - the
# noun - and the line picks which of the noun's senses, a bank or a bench, is drawn.
PAGES = {
    "french": ("fr", {"est": "to be", "court": "to run"}),
    "frenchShort": ("fr", {"est": "to be", "court": "short"}),
    "bench": ("es", {"banco": "bench", "siento": "to sit down"}),
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
        dev.surface(mode=page, packHost="none", target="en", layer="meaning", enable=1,
                    density=1)
        began = time.time()
        while time.time() - began < ARRIVES_WITHIN_S:
            if f"{lang}-en" in shell("run-as", "io.github.nanbiz.phonetix", "ls", "files/models"):
                # Opened, or opened already before this log began: a line settled by it says
                # the same.
                if (f"TRANSLATOR {lang}-en open=true" in dev.lines("TRANSLATOR")
                        or "SETTLED" in dev.lines("SETTLED")):
                    break
            time.sleep(5)
        else:
            print(f"FAIL - the {lang} model never arrived or never opened")
            sys.exit(1)

    for page, (_, wanted) in PAGES.items():
        # An empty page first: a page already on screen, asked for again, is the same screen,
        # and the service rightly draws it from the read it already made rather than reading it.
        dev.surface(mode="bare", enable=1)
        time.sleep(3)
        dev.clear_log()
        dev.surface(mode=page, packHost="none", target="en", layer="meaning", enable=1, density=1)
        said = {}
        # Longer than the read itself: a word's sense is drawn once its line has been
        # translated behind the screen, a moment after the screen was first drawn.
        until = time.time() + 60
        settled = []
        nudged = False
        while time.time() < until:
            # A page already in front, asked for again, is not always read again at once;
            # asked once more it is.
            if not nudged and time.time() > until - 30 and not settled:
                nudged = True
                dev.surface(mode=page, packHost="none", target="en", layer="meaning",
                            enable=1, density=1)
            for line in reversed(dev.lines("DRAWN ").splitlines()):
                pairs = dict(drawn_pairs(line))
                if all(word in pairs for word in wanted):
                    said = pairs
                    break
            # A pass that only follows the page draws without reading it, so what the read
            # decided is waited for as well as what is drawn.
            settled = re.findall(r"SETTLED (\d+) words", dev.lines("SETTLED"))
            if settled and said and all(said.get(w) == m for w, m in wanted.items()):
                break
            time.sleep(2)
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
