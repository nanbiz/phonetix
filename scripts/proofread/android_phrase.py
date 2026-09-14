#!/usr/bin/env python3
"""Asking about several words at once, on a phone.

The browser has this: a reader drags across a clause and the card answers the clause, marked
as a machine's guess. The phone had nothing, because a reader cannot select an app's own text -
the words belong to the app and our overlay takes no touches at all.

So the run is swept with the mark: held down first, then dragged, and every word the circle
passes over joins it. This drives that gesture with real motion events, because the hold has
to come before the movement and `input swipe` starts moving at once.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run scripts/proofread/android_phrase.py
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
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-phrase")
MODELS = os.environ.get("PHONETIX_MODELS", "/tmp/phonetix-models")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8932"))

BASE = "https://storage.googleapis.com/bergamot-models-sandbox/0.3.3/esen"
FILES = {
    "model": "model.esen.intgemm.alphas.bin",
    "lex": "lex.50.50.esen.s2t.bin",
    "vocab": "vocab.esen.spm",
}


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=1800, **kw)


def fetch_model():
    os.makedirs(MODELS, exist_ok=True)
    for name in FILES.values():
        path = os.path.join(MODELS, name)
        if os.path.exists(path) and os.path.getsize(path) > 1000:
            continue
        got = run(["curl", "-sL", f"{BASE}/{name}", "-o", path])
        if got.returncode != 0 or os.path.getsize(path) < 1000:
            raise SystemExit(f"the translation model could not be fetched ({name})")


def build_packs():
    os.makedirs(WORK, exist_ok=True)
    source = os.path.join(CORE, "packbuild", "fixtures", "es.jsonl")
    got = run(["cargo", "run", "-q", "-p", "packbuild", "--", "es", source,
               os.path.join(WORK, "es.pack")], cwd=CORE)
    if got.returncode != 0:
        raise SystemExit(f"packbuild failed: {got.stderr[:300]}")


def serve():
    registry = json.dumps([{
        "from": "es", "to": "en",
        "files": {key: {"name": name} for key, name in FILES.items()},
    }]).encode()
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
            elif self.path == "/models.json":
                body, kind = registry, "application/json"
            elif self.path.startswith("/packs/"):
                path = os.path.join(WORK, os.path.basename(self.path))
                body, kind = open(path, "rb").read(), "application/octet-stream"
            elif self.path.startswith("/models/"):
                path = os.path.join(MODELS, os.path.basename(self.path))
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
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def lift(dpi):
    """How far above the finger the circle rides, in pixels.

    The controller's own arithmetic: half what a fingertip covers, plus the mark's radius,
    plus a clear width more. A motion event driven from here reports no contact patch, so the
    fallback - a finger pad of 0.43 inches - is what it uses.
    """
    density = dpi / 160.0
    size = 40 * density
    return 0.43 * dpi / 2 + size / 2 + size * 1.1


def main():
    fetch_model()
    build_packs()
    serve()
    base = f"http://10.0.2.2:{PORT}"
    dev = Device()
    failures = []

    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    dev.give(os.path.join(WORK, "es.pack"), "lex-es.pack", "files")
    adb("shell", "run-as io.github.nanbiz.phonetix mkdir -p files/models/es-en", timeout=120)
    for name in FILES.values():
        dev.give(os.path.join(MODELS, name), name, "files/models/es-en")

    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    dev.clear_log()
    dev.surface(mode="spanish", packHost=base, target="en", layer="meaning",
                enable=1, density=1, touchWords=0, lens=0)
    time.sleep(5)
    # Cleared again here, so everything read below is small: pushing twenty megabytes of model
    # to the device fills the log, and a read that has to wade through all of it takes minutes
    # on this emulator - longer than the read's own patience.
    dev.clear_log()
    dev.surface(mode="spanish", packHost=base, target="en", layer="meaning", enable=1, lens=1)
    time.sleep(8)

    boxes = dev.annotated()
    if not boxes:
        print("FAIL - nothing was annotated, so there is nothing to sweep over")
        sys.exit(1)
    where = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.log())
    if not where:
        print("FAIL - the mark never said where it is, so there is nothing to sweep with")
        sys.exit(1)
    x, y, w, h = (int(v) for v in where[-1])
    mark = (x + w // 2, y + h // 2)

    dpi = int(re.search(r"(\d+)", shell("wm", "density")).group(1))
    up = lift(dpi)

    # Two words of one line, taken as the page laid them out, so the sweep runs along a line
    # the way a reader would read it.
    lines = {}
    for box in boxes.values():
        left, top, right, bottom = box["rect"]
        # By the middle of the line rather than its top: two words of one line differ by a
        # pixel or two where their letters reach, and bucketing on that splits the line.
        lines.setdefault(round((top + bottom) / 2 / 20), []).append(
            (left, top, right, bottom, box["word"]))
    run_of = max(lines.values(), key=len)
    run_of.sort()
    if len(run_of) < 2:
        print(f"FAIL - no line has two words on it: {lines}")
        sys.exit(1)
    over = run_of[:3]
    print(f"  sweeping over: {[w for *_, w in over]}")

    dev.clear_log()
    # Real motion events, not `input swipe`: the hold has to come before any movement, and a
    # swipe starts moving on its first frame.
    shell("input", "motionevent", "DOWN", str(mark[0]), str(mark[1]))
    time.sleep(1.0)
    for left, top, right, bottom, word in over:
        at = ((left + right) // 2, int((top + bottom) / 2 + up))
        # A few steps to each word: the circle is on a leash and has to be given time to
        # arrive, and what it reports is read from where it actually is.
        for _ in range(4):
            shell("input", "motionevent", "MOVE", str(at[0]), str(at[1]))
            time.sleep(0.12)
        time.sleep(0.3)
    shell("input", "motionevent", "UP", str(over[-1][0]), str(int(over[-1][1] + up)))
    # Read at once, while the sweep is still the newest thing in the log: the service keeps
    # writing a line per box per pass, so the sweep's own lines are gone from the tail within
    # seconds. Then wait for the card, which is a pass through the translation model, and read
    # again; both halves together are what the run did.
    time.sleep(1)
    swept_log = dev.log()
    time.sleep(8)
    log = swept_log + dev.log()

    shot = dev.screenshot(os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-phrase-shots"))
    print(f"  screenshot: {shot}")

    swept = re.findall(r"LENSAT [\d.,]+ -> (\S+)", log)
    asked = re.findall(r"TOOLTIP phrase=(.*?) says=(.*)", log)
    unanswered = re.findall(r"PHRASE unanswered: (.*)", log)
    print(f"  the sweep was armed: {'yes' if 'LENSSWEEP on' in log else 'no'}")

    print(f"  it passed over: {[w for w in swept if w != 'nothing'][:6]}")
    print(f"  the card asked about: {asked[-1] if asked else (unanswered[-1] if unanswered else 'nothing')}")

    if "LENSSWEEP on" not in log:
        failures.append("holding the mark down did not arm a sweep")
    words = [w for w in swept if w != "nothing"]
    if len(set(words)) < 2:
        failures.append(f"the sweep took in fewer than two words: {words}")
    if not asked:
        if unanswered:
            failures.append(
                f"the run was swept and nothing answered it: {unanswered[-1]!r}")
        else:
            failures.append("no card opened for the run that was swept")
    else:
        text, says = asked[-1]
        if len(text.split()) < 2:
            failures.append(f"the card is about one word, not a run: {text!r}")
        if not says.strip("[] "):
            failures.append(f"the card carries no translation for {text!r}")
    # And a card about one word never opened during the sweep: a run is one question.
    during = swept_log.split("LENSSWEEP on", 1)[1].split("TOOLTIP phrase", 1)[0] if asked else ""
    if "TOOLTIP open word=" in during:
        failures.append("a card opened for a single word in the middle of a sweep")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - holding the mark and sweeping asks about the run as one thing")


if __name__ == "__main__":
    main()
