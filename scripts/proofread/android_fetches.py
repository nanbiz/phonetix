#!/usr/bin/env python3
"""What a page needs arrives by itself.

A reader who opens a page in Spanish, having chosen to read into English, has said everything
the product needs to know: the dictionary of what Spanish words mean, and the model that
translates Spanish into English. Both used to wait behind a list three screens deep with a
button per dictionary, and until the reader found it the page was answered with nothing.

This serves a host the way the published one is laid out - a listing of packs, a listing of
models naming each file with its checksum - starts from a phone holding neither, and asks that
reading a Spanish page is enough for both to arrive and be opened, and for the card the side
button shows over a word to say what it means.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_fetches.py
"""
import hashlib
import http.server
import json
import os
import re
import socket
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import android_says as Says
from android_harness import Device, card_while_held, shell

PKG = "io.github.nanbiz.phonetix"
ARRIVES_WITHIN_S = 120
# A word on the page, and what the served dictionary says it means.
MEANT = ("perro", "dog")


def free_port():
    with socket.socket() as probe:
        probe.bind(("0.0.0.0", 0))
        return probe.getsockname()[1]


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def serve(port):
    pack = os.path.join(Says.WORK, "es.pack")
    packs = json.dumps([{
        "id": "lex-es", "lang": "es", "built": 0, "entries": 3, "keys": 4, "glosses": 3,
        "bytes": os.path.getsize(pack), "sha256": sha(pack),
    }]).encode()
    files = Says.PAIRS[("es", "en")]
    models = json.dumps([{
        "from": "es", "to": "en", "version": "1.0",
        "files": {
            kind: {"name": name, "sha256": sha(os.path.join(Says.MODELS, name))}
            for kind, name in files.items()
        },
    }]).encode()
    asked = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            asked.append(self.path)
            if self.path == "/packs.json":
                body = packs
            elif self.path == "/models.json":
                body = models
            elif self.path == "/es.pack":
                body = open(pack, "rb").read()
            elif self.path.startswith("/models/"):
                path = os.path.join(Says.MODELS, os.path.basename(self.path))
                if not os.path.exists(path):
                    self.send_error(404)
                    return
                body = open(path, "rb").read()
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return asked


def main():
    Says.fetch_models()
    Says.build_packs()
    port = free_port()
    asked = serve(port)
    dev = Device()
    failures = []

    # A phone that holds neither.
    shell("run-as", PKG, "rm", "-rf", "files/models/es-en", "files/lex-es.pack")
    shell("am", "force-stop", PKG)
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    dev.clear_log()
    shell("input", "keyevent", "3")
    time.sleep(2)
    dev.surface(mode="spanish", enable=1, density=1, target="en", known="none",
                packHost=f"http://10.0.2.2:{port}")

    began = time.time()
    opened = False
    while time.time() - began < ARRIVES_WITHIN_S:
        time.sleep(3)
        log = dev.log()
        if "TRANSLATOR es-en open=true" in log and "FETCHED pack es ok=true" in log:
            opened = True
            break
    took = time.time() - began
    held = shell("run-as", PKG, "ls", "files", "files/models")
    fetched = re.findall(r"FETCHED (.*)", dev.log())
    print(f"  after {took:.0f}s: {fetched[-4:]}")
    if "lex-es.pack" not in held:
        failures.append("the dictionary of the page's language never arrived")
    if "es-en" not in held:
        failures.append("the model for reading Spanish into English never arrived")
    if not opened:
        failures.append("what arrived was not opened and used")

    # And used: the card the side button shows over a word says what it means, out of the
    # dictionary that arrived.
    texts, _ = card_while_held(dev, MEANT[0])
    print(f"  the card for {MEANT[0]}: {texts or 'nothing came up'}")
    if not texts:
        failures.append(f"no card came up for {MEANT[0]}")
    elif MEANT[1] not in " ".join(texts):
        failures.append(f"the card for {MEANT[0]} does not say {MEANT[1]!r}, though the "
                        f"dictionary arrived: {texts}")

    # Asked for once, not once per screen.
    pack_asks = sum(1 for path in asked if path == "/es.pack")
    if pack_asks > 1:
        failures.append(f"the dictionary was downloaded {pack_asks} times")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a page's dictionary and its translation arrive without being asked for")


if __name__ == "__main__":
    main()
