#!/usr/bin/env python3
"""The accent the reader chose, on the words and on the card.

An accent is two things: a pack of words a dictionary tagged for one region, and a rule that
holds across a whole vocabulary. Which of them answers a word is the cascade's decision, made
once, so that everything drawn from it agrees. That is the part worth checking on a device:
the overlay and the card ask for the same word by different routes, and for a while the card
asked without the accent at all, so a reader who chose Rioplatense saw it on the page and
lost it the moment they tapped.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run scripts/proofread/android_accent.py
"""
import http.server
import json
import os
import re
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, adb, shell, drawn_pairs

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-accent")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8926"))

# The word the accent changes, and how each says it. Rioplatense is the one accent of the
# three whose shift this fixture's own transcription can show: the standard entry is already
# [ʝ], which Latin America says the same way and Argentina does not.
WORD = "silla"
STANDARD = "ʝ"
RIOPLATENSE = "ʃ"


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900, **kw)


def build_packs():
    os.makedirs(WORK, exist_ok=True)
    for lang in ("es", "de"):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source,
                   os.path.join(WORK, f"{lang}.pack")], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[-300:]}")


def manifest():
    rows = []
    for lang in ("es", "de"):
        path = os.path.join(WORK, f"{lang}.pack")
        rows.append({
            "id": f"lex-{lang}", "lang": lang, "built": 0, "entries": 12, "keys": 14,
            "glosses": 12, "bytes": os.path.getsize(path), "sha256": "",
        })
    return json.dumps(rows).encode()


def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path == "/packs.json":
                body, kind = manifest(), "application/json"
            elif self.path.endswith(".pack"):
                path = os.path.join(WORK, os.path.basename(self.path))
                if not os.path.exists(path):
                    self.send_error(404)
                    return
                body, kind = open(path, "rb").read(), "application/octet-stream"
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def drawn(dev):
    """What is written over each word, from the last pass the service made."""
    lines = re.findall(r"DRAWN (.*)", dev.log())
    return dict(drawn_pairs(lines[-1])) if lines else {}


def on_the_card(dev, word):
    """Open the card for a word and give back every piece of text it laid out.

    A tap: that is what asks about a word, and a press held lifts the overlay instead. Where
    the word is is read again immediately before each try, since a screen that settled once
    more has moved the words under a position read a moment ago.
    """
    for _ in range(3):
        box = next((b for b in dev.annotated(seconds=20).values() if b["word"] == word), None)
        if not box:
            return None, "the word was not on the screen to press"
        left, top, right, bottom = box["rect"]
        x, y = (left + right) // 2, (top + bottom) // 2
        dev.clear_log()
        shell("input", "tap", str(x), str(y))
        time.sleep(3)
        cards = re.findall(r"CARD (.*)", dev.log())
        if cards:
            return re.findall(r"\[([^@\]]+)@", cards[-1]), None
    return None, "no card opened"


def main():
    build_packs()
    serve()
    base = f"http://10.0.2.2:{PORT}"
    dev = Device()
    failures = []

    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    for lang in ("es", "de"):
        adb("push", os.path.join(WORK, f"{lang}.pack"), f"/data/local/tmp/lex-{lang}.pack",
            timeout=120)
        adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                     f"'cat /data/local/tmp/lex-{lang}.pack > files/lex-{lang}.pack'",
            timeout=120)
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)

    # The standard reading first, so what the accent changed is a difference and not a guess.
    dev.clear_log()
    dev.surface(mode="spanish", packHost=base, target="de", layer="sound", accent="none",
                enable=1, density=1, touchWords=1)
    time.sleep(8)
    standard = drawn(dev).get(WORD)
    print(f"  standard: {WORD} said {standard!r}")
    if not standard:
        print(f"FAIL - {WORD} was not annotated at all, so there is nothing to accent")
        sys.exit(1)
    if STANDARD not in standard:
        failures.append(f"the standard reading of {WORD} is {standard!r}, "
                        f"which has no {STANDARD} for an accent to change")

    # And in the accent, which is a rule rather than a pack of its own.
    dev.clear_log()
    dev.surface(mode="spanish", packHost=base, target="de", layer="sound", accent="es-ar",
                enable=1, density=1, touchWords=1)
    time.sleep(8)
    said = drawn(dev).get(WORD)
    print(f"  es-ar:    {WORD} said {said!r}")
    if not said:
        failures.append(f"{WORD} was not annotated once an accent was chosen")
    elif RIOPLATENSE not in said:
        failures.append(f"the page says {said!r} for {WORD}, which is not the accent's")

    # The card is the other route to the same word, and it has to agree. It asked without the
    # accent for a while, so a reader saw the accent on the page and lost it on the tap.
    texts, why = on_the_card(dev, WORD)
    if why:
        failures.append(f"the card: {why}")
    else:
        carrying = [t for t in texts if RIOPLATENSE in t or STANDARD in t]
        print(f"  the card says: {carrying}")
        if not any(RIOPLATENSE in t for t in carrying):
            failures.append(f"the card shows {carrying}, none of it in the accent")
        if any(STANDARD in t for t in carrying):
            failures.append(f"the card still shows the standard reading: {carrying}")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the accent reaches the page and the card alike")


if __name__ == "__main__":
    main()
