#!/usr/bin/env python3
"""The accent the reader chose, on the card the side button shows.

An accent is two things: a pack of words a dictionary tagged for one region, and a rule that
holds across a whole vocabulary. Which of them answers a word is the cascade's decision, made
once, so that everything drawn from it agrees. On the phone a word is answered by the card the
side button shows while it is held over the word, and that card asks the cascade for itself:
for a while it asked without the accent at all, so a reader who chose Rioplatense never saw it.

So the card is read twice for the same word, once with no accent chosen and once in the
accent, and the two have to differ the way the accent says.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run scripts/proofread/android_accent.py
"""
import http.server
import json
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import Device, adb, shell, card_while_held

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

    def said(accent):
        """What the card says of the word with this accent chosen: the pieces that carry a
        transcription, or None where no card came up."""
        dev.surface(mode="spanish", packHost=base, target="de", known="none", accent=accent,
                    enable=1, density=1)
        time.sleep(4)
        texts, closed = card_while_held(dev, WORD)
        if not texts:
            return None, closed
        return [t for t in texts if t.startswith("/")], closed

    # The standard reading first, so what the accent changed is a difference and not a guess.
    standard, _ = said("none")
    print(f"  standard: the card says {standard!r} for {WORD}")
    if standard is None:
        print(f"FAIL - no card came up for {WORD}, so there is nothing to accent")
        sys.exit(1)
    if not any(STANDARD in t for t in standard):
        failures.append(f"the standard reading of {WORD} on the card is {standard!r}, "
                        f"which has no {STANDARD} for an accent to change")

    # And in the accent, which is a rule rather than a pack of its own.
    accented, closed = said("es-ar")
    print(f"  es-ar:    the card says {accented!r} for {WORD}, closed after release: {closed}")
    if accented is None:
        failures.append(f"no card came up for {WORD} once an accent was chosen")
    else:
        if not any(RIOPLATENSE in t for t in accented):
            failures.append(f"the card says {accented!r} for {WORD}, none of it in the accent")
        if any(STANDARD in t for t in accented):
            failures.append(f"the card still shows the standard reading: {accented!r}")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the accent the reader chose is what the card says")


if __name__ == "__main__":
    main()
