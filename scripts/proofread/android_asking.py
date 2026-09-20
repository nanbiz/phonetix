#!/usr/bin/env python3
"""The panel answers with the word, and answers it at once.

The panel over the page used to put every question to the machine on the phone - somebody
else's app, in another process, which fetches a model of tens of megabytes the first time a
pair is asked for. A reader watching "…" has no way of telling that from a panel that is
broken, and it is the wrong engine anyway: the one this app already holds the models for is
in this process and answers in milliseconds.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_asking.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import android_says as Says
from android_harness import Device, adb, shell, SERIAL
import state as State

# How long the reader may wait for the word, when this phone holds the pair. The engine is
# already open for the reading direction and has to be turned round, which is the only part
# of this that costs anything.
ANSWER_WITHIN_S = 6.0


def believed():
    try:
        name = State.ask(SERIAL)
        return State.fetch(SERIAL, name) if name else {}
    except Exception:
        return {}


def main():
    Says.fetch_models()
    Says.build_packs()
    Says.serve()
    base = f"http://10.0.2.2:{Says.PORT}"
    dev = Device()
    failures = []

    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    adb("push", os.path.join(Says.WORK, "es.pack"), "/data/local/tmp/lex-es.pack", timeout=180)
    adb("shell", "run-as io.github.nanbiz.phonetix sh -c "
                 "'cat /data/local/tmp/lex-es.pack > files/lex-es.pack'", timeout=180)
    Says.push_models()

    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    # A reader reading Spanish into English, asking for the Spanish word for an English one.
    shell("input", "keyevent", "3")
    time.sleep(2)
    dev.surface(mode="spanish", packHost=base, target="en", learning="es",
                enable=1, density=1, layer="both")
    time.sleep(8)

    at = ((believed().get("mark") or {}).get("markAt") or {})
    if not at:
        print("FAIL - the button is not on screen, so the panel cannot be opened")
        sys.exit(1)
    dev.clear_log()
    shell("input", "tap", str(at["x"] + 52), str(at["y"] + 52))
    time.sleep(3)
    if not (believed().get("ask") or {}).get("panelUp"):
        print("FAIL - tapping the button did not open the panel")
        sys.exit(1)

    # Typed and asked for the way a reader does it.
    shell("input", "text", "dog")
    time.sleep(1)
    dev.clear_log()
    began = time.time()
    shell("input", "keyevent", "66")
    answered = None
    while time.time() - began < 30:
        told = (believed().get("ask") or {})
        if (told.get("stage") or "").startswith(("answered", "nothing came back")):
            answered = told
            break
        time.sleep(0.5)
    took = time.time() - began
    said = re.findall(r"ASKED \S+: (.*)", dev.log())
    print(f"  asked for the Spanish for 'dog': {answered and answered.get('stage')} in "
          f"{took:.1f}s {said[-1:] or ''}")
    if answered is None:
        failures.append(f"nothing came back within {took:.0f}s")
    elif (answered.get("stage") or "").startswith("nothing"):
        failures.append("the panel could not answer at all")
    elif took > ANSWER_WITHIN_S:
        failures.append(f"the word took {took:.1f}s, which is a wait a reader notices")
    # And from the engine on this phone, not from the machine in another app: that is what
    # makes it milliseconds rather than a download.
    if said and "here=" not in said[-1]:
        failures.append(f"the phone holds this pair and the question went elsewhere: {said[-1]}")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the panel answers with the word, from the engine this phone already holds")


if __name__ == "__main__":
    main()
