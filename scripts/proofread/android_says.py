#!/usr/bin/env python3
"""The word for something a reader wants to say, on the phone.

The other direction, and Taplex's. Everything else the phone does answers a word somebody else
wrote; this answers a word the reader is looking for, and answers it with the same card, so a
machine's answer is judged rather than taken: how it is said, what it means back, what sounds
are in it.

The engine holds the reverse pair open beside the reading direction, so the app opens it, asks,
and leaves the reading direction as it was. That is what this drives, from the panel a tap on
the side button opens, the way a reader reaches it. What the panel shows under the word - how
it is said, what it means back - is drawn in a window nothing reading the screen can see, so
what is asked of it is the word it answered with, which the service reports.

  PHONETIX_ANDROID_SERIAL=emulator-5556 uv run scripts/proofread/android_says.py
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
from android_harness import Device, adb, shell, SERIAL
import state as State

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-says-phone")
MODELS = os.environ.get("PHONETIX_MODELS", "/tmp/phonetix-models")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8934"))

SANDBOX = "https://storage.googleapis.com/bergamot-models-sandbox/0.3.3"
# Both directions: reading runs one way and saying runs the other.
PAIRS = {
    ("es", "en"): {
        "model": "model.esen.intgemm.alphas.bin",
        "lex": "lex.50.50.esen.s2t.bin",
        "vocab": "vocab.esen.spm",
    },
    ("en", "es"): {
        "model": "model.enes.intgemm.alphas.bin",
        "lex": "lex.50.50.enes.s2t.bin",
        # One vocabulary for the pair, which is how it is published.
        "vocab": "vocab.esen.spm",
    },
}

WANTED = "bench"
EXPECTED = "banco"

# With no translation model anywhere - none on the phone, none at the host - the dictionaries
# answer by themselves: "bench" is found through the gloss "bench" of the Spanish "banco".
#   PHONETIX_NO_MODELS=1 ... android_says.py
NO_MODELS = os.environ.get("PHONETIX_NO_MODELS") == "1"


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=1800, **kw)


def fetch_models():
    os.makedirs(MODELS, exist_ok=True)
    for (source, target), files in PAIRS.items():
        for name in files.values():
            path = os.path.join(MODELS, name)
            if os.path.exists(path) and os.path.getsize(path) > 1000:
                continue
            got = run(["curl", "-sL", f"{SANDBOX}/{source}{target}/{name}", "-o", path])
            if got.returncode != 0 or os.path.getsize(path) < 1000:
                raise SystemExit(f"the {source}-{target} model could not be fetched ({name})")


def build_packs():
    os.makedirs(WORK, exist_ok=True)
    source = os.path.join(CORE, "packbuild", "fixtures", "es.jsonl")
    got = run(["cargo", "run", "-q", "-p", "packbuild", "--", "es", source,
               os.path.join(WORK, "es.pack")], cwd=CORE)
    if got.returncode != 0:
        raise SystemExit(f"packbuild failed: {got.stderr[:300]}")


def serve():
    packs = json.dumps([{
        "id": "lex-es", "lang": "es", "built": 0, "entries": 12, "keys": 14, "glosses": 12,
        "bytes": os.path.getsize(os.path.join(WORK, "es.pack")), "sha256": "",
    }]).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path == "/packs.json":
                body, kind = packs, "application/json"
            elif self.path.endswith(".pack"):
                path = os.path.join(WORK, os.path.basename(self.path))
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
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def push_models():
    for (source, target), files in PAIRS.items():
        into = f"files/models/{source}-{target}"
        adb("shell", f"run-as io.github.nanbiz.phonetix mkdir -p {into}", timeout=120)
        for name in files.values():
            adb("push", os.path.join(MODELS, name), f"/data/local/tmp/{name}", timeout=900)
            adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                         f"'cat \"/data/local/tmp/{name}\" > \"{into}/{name}\"'", timeout=900)
            # Checked, since a push that failed leaves the copy an empty file, and an empty
            # model is one the app must never be handed.
            size = shell("run-as", "io.github.nanbiz.phonetix", "stat", "-c", "%s", f"{into}/{name}")
            if size.strip() != str(os.path.getsize(os.path.join(MODELS, name))):
                raise SystemExit(f"{name} reached the phone as {size.strip()!r} bytes")


def main():
    fetch_models()
    build_packs()
    serve()
    base = f"http://10.0.2.2:{PORT}"
    dev = Device()
    failures = []

    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    adb("push", os.path.join(WORK, "es.pack"), "/data/local/tmp/lex-es.pack", timeout=180)
    adb("shell", "run-as io.github.nanbiz.phonetix sh -c "
                 "'cat /data/local/tmp/lex-es.pack > files/lex-es.pack'", timeout=180)
    if NO_MODELS:
        adb("shell", "run-as io.github.nanbiz.phonetix rm -rf files/models", timeout=120)
    else:
        push_models()

    if not dev.enable_service():
        raise SystemExit("the service would not start")
    # Which language the reader reads into, the one asked in, and where their dictionaries
    # come from: the question only means anything once all three are set.
    shell("input", "keyevent", "3")
    time.sleep(2)
    dev.surface(mode="spanish", packHost=base, target="en", learning="es", enable=1, density=1)
    time.sleep(6)

    def believed():
        try:
            name = State.ask(SERIAL)
            return State.fetch(SERIAL, name) if name else {}
        except Exception:
            return {}

    at = ((believed().get("mark") or {}).get("markAt") or {})
    if not at:
        print("FAIL - the button is not on screen, so the panel cannot be opened")
        sys.exit(1)
    shell("input", "tap", str(at["x"] + 52), str(at["y"] + 52))
    time.sleep(3)
    if not (believed().get("ask") or {}).get("panelUp"):
        print("FAIL - tapping the button did not open the panel")
        sys.exit(1)

    # Typed and asked for the way a reader does it. Asked again rather than watched: the engine
    # opens a model of seventeen megabytes for a direction nobody has been reading in, and the
    # first ask can be put to an engine that is not up yet.
    said = []
    stage = ""
    for attempt in range(4):
        dev.clear_log()
        if attempt == 0:
            shell("input", "text", WANTED)
            time.sleep(1)
        shell("input", "keyevent", "66")
        until = time.time() + 30
        while time.time() < until:
            stage = (believed().get("ask") or {}).get("stage") or ""
            if stage.startswith(("answered", "nothing came back")):
                break
            time.sleep(0.5)
        said = re.findall(r"ASKED \S+ \S+->\S+: (.*)", dev.lines("ASKED "))
        if any(EXPECTED in line for line in said):
            break
    print(f"  asked for {WANTED!r}: {stage!r}, {said[-1:] or 'nothing said'}")
    # Put away, so the next check starts from a page.
    shell("input", "keyevent", "4")
    shell("input", "keyevent", "4")

    if not any(EXPECTED in line for line in said):
        failures.append(f"nothing on the phone answered {WANTED!r} with {EXPECTED!r}")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the phone answers the word for something a reader wants to say")


if __name__ == "__main__":
    main()
