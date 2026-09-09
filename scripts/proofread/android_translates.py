#!/usr/bin/env python3
"""The phone showing what a word means, which is what the merge was for.

The app read a screen and drew pronunciations over it. The whole point of putting the two
products together was that it should draw what a word means, in the reader's own language,
with the pronunciation beside it. That is only true if a real dictionary reaches a real
screen, so this serves the fixture packs the way the release host would, tells the app where
they are and which language the reader reads into, and looks at what ends up over the words.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_translates.py
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
from android_harness import Device, adb, shell

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-translates")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8925"))

SENTENCE = "El perro corre por el camino y descansa en el banco del parque."


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
                body = manifest()
                kind = "application/json"
            elif self.path.startswith("/packs/"):
                path = os.path.join(WORK, os.path.basename(self.path))
                if not os.path.exists(path):
                    self.send_error(404)
                    return
                body = open(path, "rb").read()
                kind = "application/octet-stream"
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
    # The emulator reaches this machine at the address its own network gives it.
    base = f"http://10.0.2.2:{PORT}"
    dev = Device()
    failures = []

    # The dictionaries first, pushed where a reader's own fetch would leave them. Stopping the
    # app comes before the service is bound, since stopping it unbinds the service and a run
    # against an unbound service measures a screen nothing is annotating.
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    for lang in ("es", "de"):
        adb("push", os.path.join(WORK, f"{lang}.pack"), f"/data/local/tmp/lex-{lang}.pack",
            timeout=120)
        adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                     f"'cat /data/local/tmp/lex-{lang}.pack > files/lex-{lang}.pack'",
            timeout=120)
    # An install clears the accessibility permission, so it is granted rather than assumed.
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    dev.clear_log()
    shell("am", "start", "-n", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity",
          "--es", "packHost", base, "--es", "target", "de", "--es", "layer", "gloss+ipa",
          "--es", "mode", "spanish", "--ei", "enable", "1", "--ei", "density", "1")
    time.sleep(8)
    # What is written over each word, which is the whole question here.
    drawn = re.findall(r"DRAWN (.*)", dev.log())
    over = {}
    for pair in (drawn[-1].split() if drawn else []):
        if "=" in pair:
            word, said = pair.split("=", 1)
            over[word] = said
    print(f"  {len(over)} words annotated: {list(over.items())[:6]}")

    if not over:
        failures.append("nothing was drawn on a Spanish screen")
    else:
        # What a reader is owed: the meaning, in their language, over the word it belongs to.
        if over.get("perro") != "Hund":
            failures.append(f"perro carries {over.get('perro')!r} rather than its meaning")
        if over.get("camino") != "Weg":
            failures.append(f"camino carries {over.get('camino')!r} rather than its meaning")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the phone shows what a word means, not only how it sounds")


if __name__ == "__main__":
    main()
