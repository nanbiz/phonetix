#!/usr/bin/env python3
"""The word for something a reader wants to say, on the phone.

The other direction, and Taplex's. Everything else the phone does answers a word somebody else
wrote; this answers a word the reader is looking for, and answers it with the same card, so a
machine's answer is judged rather than taken: how it is said, what it means back, what sounds
are in it.

The engine holds one direction open at a time, so the app opens the reverse pair, asks, and
puts the reading direction back. That is what this drives, from the app's own screen, the way
a reader reaches it.

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
from android_harness import Device, adb, shell

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-says-phone")
MODELS = os.environ.get("PHONETIX_MODELS", "/tmp/phonetix-models")
SHOTS = os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-says-phone-shots")
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
            elif self.path.startswith("/packs/"):
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


def screen():
    """What the app's own screen says, as uiautomator sees it."""
    adb("shell", "uiautomator", "dump", "/sdcard/win.xml", timeout=120)
    return adb("shell", "cat", "/sdcard/win.xml", timeout=120)


def where(dump, text):
    """The middle of the first node whose text or hint is this, or None."""
    for node in re.finditer(r'<node[^>]*>', dump):
        tag = node.group(0)
        if f'text="{text}"' not in tag and f'content-desc="{text}"' not in tag:
            continue
        box = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not box:
            continue
        left, top, right, bottom = (int(v) for v in box.groups())
        return ((left + right) // 2, (top + bottom) // 2)
    return None


def push_models():
    for (source, target), files in PAIRS.items():
        into = f"files/models/{source}-{target}"
        adb("shell", f"run-as io.github.nanbiz.phonetix mkdir -p {into}", timeout=120)
        for name in files.values():
            adb("push", os.path.join(MODELS, name), f"/data/local/tmp/{name}", timeout=900)
            adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                         f"'cat \"/data/local/tmp/{name}\" > \"{into}/{name}\"'", timeout=900)


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
    push_models()

    if not dev.enable_service():
        raise SystemExit("the service would not start")
    # Which language the reader reads into, and where their dictionaries come from: the
    # question only means anything once both are set.
    dev.surface(mode="spanish", packHost=base, target="en", enable=1, density=1)
    time.sleep(6)
    dev.clear_log()
    shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity")
    time.sleep(4)

    dump = screen()
    field = where(dump, "what you want to say")
    if not field:
        # The screen scrolls; the say card is below the fold on a short device.
        for _ in range(6):
            shell("input", "swipe", "540", "1600", "540", "700", "300")
            time.sleep(1)
            dump = screen()
            field = where(dump, "what you want to say")
            if field:
                break
    if not field:
        print("FAIL - the app has no field to ask in")
        sys.exit(1)

    shell("input", "tap", str(field[0]), str(field[1]))
    time.sleep(1)
    shell("input", "text", WANTED)
    time.sleep(1)
    # The IME's own action, which is what the field listens for.
    shell("input", "keyevent", "66")

    # The engine opens a model of seventeen megabytes for a direction nobody has been reading
    # in, so the answer is given room to arrive.
    said = ""
    for _ in range(20):
        time.sleep(5)
        dump = screen()
        if EXPECTED in dump:
            said = dump
            break
    os.makedirs(SHOTS, exist_ok=True)
    for name in os.listdir(SHOTS):
        os.remove(os.path.join(SHOTS, name))
    adb("emu", "screenrecord", "screenshot", SHOTS)
    time.sleep(2)
    shot = [f for f in os.listdir(SHOTS) if f.endswith(".png")]
    print(f"  screenshot: {os.path.join(SHOTS, shot[0]) if shot else 'none'}")

    words = re.findall(r'text="([^"]{1,40})"', said or dump)
    print(f"  the screen says: {[w for w in words if w.strip()][-12:]}")

    if not said:
        failures.append(f"nothing on the phone answered {WANTED!r} with {EXPECTED!r}")
    else:
        # The entry under the word, which is what makes a machine's answer judgeable.
        if not re.search(r'text="[^"]*[ˈˌbaŋko][^"]*"', said):
            failures.append("the word came back without its pronunciation")
        if "bench" not in said:
            failures.append("the word came back without a meaning in the reader's language")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the phone answers the word for something a reader wants to say")


if __name__ == "__main__":
    main()
