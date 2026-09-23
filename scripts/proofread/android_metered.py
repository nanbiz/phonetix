#!/usr/bin/env python3
"""A dictionary too big for a metered connection, fetched once the phone is on one that is not.

What a page needs is fetched by itself, up to a size that depends on what the connection costs:
64 MB on a metered one, more on one that is not. English is about a hundred, so a reader on
mobile data reading an English page is answered without it - and used to stay without it until
they read some other language and came back, because nothing else asked again.

This starts on cellular alone with nothing held, reads an English page into German, checks the
English dictionary is left alone, then turns Wi-Fi on and waits for it to arrive by itself.
It downloads the published English dictionary, a hundred megabytes, every run.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_metered.py
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, shell

PKG = "io.github.nanbiz.phonetix"
ARRIVES_WITHIN_S = 300


def held():
    return shell("run-as", PKG, "ls", "files").split()


def fetched(dev):
    return re.findall(r"FETCHED pack en ok=(\w+)", dev.lines("FETCHED pack en"))


def main():
    failures = []
    shell("pm", "clear", PKG)
    shell("svc", "wifi", "disable")
    time.sleep(8)
    try:
        dev = Device()
        if not dev.enable_service():
            raise SystemExit("the service would not start")
        dev.set_enabled(True)
        dev.clear_log()
        dev.surface(mode="plain", packHost="none", target="de", layer="meaning", enable=1,
                    density=1)
        time.sleep(45)
        print(f"  on cellular: asked {fetched(dev)}, holding {[f for f in held() if 'lex' in f]}")
        if "lex-en.pack" in held():
            failures.append("the English dictionary was fetched on a metered connection")

        shell("svc", "wifi", "enable")
        began = time.time()
        while time.time() - began < ARRIVES_WITHIN_S and "true" not in fetched(dev):
            time.sleep(5)
        took = time.time() - began
        print(f"  on Wi-Fi: asked {fetched(dev)} after {took:.0f}s, "
              f"holding {[f for f in held() if 'lex' in f]}")
        if "true" not in fetched(dev) or "lex-en.pack" not in held():
            failures.append("the English dictionary never arrived once the phone was on Wi-Fi")
    finally:
        shell("svc", "wifi", "enable")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a dictionary held back on a metered connection arrives on one that is not")


if __name__ == "__main__":
    main()
