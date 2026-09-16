#!/usr/bin/env python3
"""The whole screen in the reader's own language, on a press of the mark.

A word at a time answers "what is that". A page in a language somebody is still learning is a
different question, and a reader who has to ask it word by word has stopped reading. Taplex
answered it by laying the page's own lines over it, translated, on a tap of the mark, and
taking them away on the next tap; the merge dropped it.

So this presses the mark on a Spanish page and asks three things: that the page comes back in
the reader's language where its own lines were, that our transcriptions are not painted on top
of the replacement, and that a second press gives the page back.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run scripts/proofread/android_page.py
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
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-page")
MODELS = os.environ.get("PHONETIX_MODELS", "/tmp/phonetix-models")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8931"))
SHOTS = os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-page-shots")

BASE = "https://storage.googleapis.com/bergamot-models-sandbox/0.3.3/esen"
FILES = {
    "model": "model.esen.intgemm.alphas.bin",
    "lex": "lex.50.50.esen.s2t.bin",
    "vocab": "vocab.esen.spm",
}

# What a reader of English would have to see somewhere on a replaced Spanish page. Not a
# particular sentence: a model's exact wording is its own business and changes with the
# model. These are the words any reading of this page has to contain.
ENGLISH = ("dog", "road", "bench", "park")


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
    for lang in ("es",):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source,
                   os.path.join(WORK, f"{lang}.pack")], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[:300]}")


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
                if not os.path.exists(path):
                    self.send_error(404)
                    return
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


def drew(log):
    """The last set of lines the replaced page actually put on screen."""
    for line in reversed(log.splitlines()):
        if "PAGEDREW " not in line:
            continue
        rest = line.split("PAGEDREW ", 1)[1]
        count, _, body = rest.partition(" ")
        said = [part.split("=", 1)[1] for part in body.split("|") if "=" in part]
        if said:
            return said
    return []


def push(name, into):
    adb("push", os.path.join(MODELS, name), f"/data/local/tmp/{name}", timeout=900)
    adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                 f"'cat \"/data/local/tmp/{name}\" > \"{into}/{name}\"'", timeout=900)


def main():
    fetch_model()
    build_packs()
    serve()
    base = f"http://10.0.2.2:{PORT}"
    dev = Device()
    failures = []

    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    adb("push", os.path.join(WORK, "es.pack"), "/data/local/tmp/lex-es.pack", timeout=180)
    adb("shell", "run-as io.github.nanbiz.phonetix sh -c "
                 "'cat /data/local/tmp/lex-es.pack > files/lex-es.pack'", timeout=180)
    adb("shell", "run-as io.github.nanbiz.phonetix mkdir -p files/models/es-en", timeout=120)
    for name in FILES.values():
        push(name, "files/models/es-en")

    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    dev.clear_log()
    # The page, with the mark on it and the engine pointed at English.
    # A page long enough to scroll: what is being checked below is that the replacement
    # follows the words it replaced, and five lines on one screen cannot move.
    dev.surface(mode="spanish", packHost=base, target="en", layer="meaning",
                enable=1, density=1, touchWords=0, lens=0, repeat=6)
    time.sleep(5)
    # Asked for again so the mark says where it parked in a log this run can see, and so the
    # read happens with the engine already open: it loads seventeen megabytes the first time a
    # word needs it, and the first pass is a screen still getting ready.
    dev.surface(mode="spanish", packHost=base, target="en", layer="meaning", enable=1, lens=1,
                repeat=6)
    time.sleep(8)

    # Waited for rather than read once: the dictionary and the engine both load in their own
    # time, and on a machine with nothing to spare a page that is annotated a second later
    # reads here as a page that was never annotated at all.
    drawn = {}
    for _ in range(10):
        drawn = dev.annotated()
        if drawn:
            break
        time.sleep(2)
    if not drawn:
        print("FAIL - nothing was annotated, so there is no page to replace")
        sys.exit(1)
    # Asked of the device rather than read out of a tail: the overlay writes one long line
    # per pass, and where the mark parked scrolls out of that window while a check waits.
    where = re.findall(r"LENSPARKED (\d+),(\d+),(\d+),(\d+)", dev.lines("LENSPARKED"))
    if not where:
        print("FAIL - the mark is not on screen, so there is nothing to press")
        sys.exit(1)
    x, y, w, h = (int(v) for v in where[-1])
    mark = (x + w // 2, y + h // 2)

    chips_before = len(dev.boxes())
    dev.clear_log()
    shell("input", "tap", str(mark[0]), str(mark[1]))
    time.sleep(10)
    log = dev.log()
    up = re.findall(r"PAGE up with (\d+) lines", log)
    said = drew(log)
    print(f"  the page went up: {up[-1] + ' lines' if up else 'no'}")
    print(f"  what it says: {said[:4]}")
    shot = dev.screenshot(SHOTS)
    print(f"  screenshot: {shot}")

    if not up:
        failures.append("the mark was pressed and the page was not replaced")
    if not said:
        failures.append("the replaced page drew nothing, so the screen is covered by nothing")
    else:
        joined = " ".join(said).lower()
        missing = [word for word in ENGLISH if word not in joined]
        if len(missing) > len(ENGLISH) // 2:
            failures.append(
                f"the page is not in the reader's language: {missing} nowhere in {said[:4]}")
    # And nothing of ours is painted on top of it: the words under a replaced line are not the
    # words on screen any more, so a transcription over them is over the wrong text.
    since = log.split("PAGE up with", 1)[1] if "PAGE up with" in log else ""
    painted = [int(n) for n in re.findall(r"SHOWING (\d+)", since)]
    print(f"  transcriptions painted while it was up: {painted}")
    if any(n > 0 for n in painted):
        failures.append(
            f"transcriptions were painted over the replaced page: {painted}")

    # And it follows the page. A translation pinned where a line used to be is a translation
    # over the wrong words, so what is checked is that the lines move with what they replaced.
    was = drew(log)
    where_was = re.findall(r"PAGEDREW \d+ (\d+),(\d+)", log)
    dev.clear_log()
    shell("input", "swipe", str(dev.width // 2), str(int(dev.height * 0.7)),
          str(dev.width // 2), str(int(dev.height * 0.35)), "400")
    time.sleep(6)
    scrolled = dev.log()
    where_now = re.findall(r"PAGEDREW \d+ (\d+),(\d+)", scrolled)
    moved = bool(where_was and where_now and where_was[-1] != where_now[-1])
    print(f"  the lines were at {where_was[-1:]} and are at {where_now[-1:]}")
    if not where_now:
        failures.append("the replaced page stopped drawing when the page was scrolled")
    elif not moved:
        failures.append(
            f"the replaced page did not follow the scroll: still at {where_now[-1]}")
    still = drew(scrolled)
    if still and was and set(still) & set(was) == set() and not moved:
        failures.append("the replaced page lost its translations on a scroll")

    # The second press, which gives the page back. Read at once and again after: the service
    # writes a line naming every line it has replaced on every pass, so the press itself is out
    # of the tail of the log within seconds, and what comes later is the transcriptions
    # returning.
    dev.clear_log()
    shell("input", "tap", str(mark[0]), str(mark[1]))
    time.sleep(1)
    pressed = dev.log()
    time.sleep(8)
    log = pressed + dev.log()
    print(f"  the page came down: {'yes' if 'PAGE down' in log else 'no'}")
    if "PAGE down" not in log:
        failures.append("a second press did not give the page back")
    chips_after = len(dev.boxes(dev.log()))
    print(f"  transcriptions before the press: {chips_before}, after it came down: {chips_after}")
    if chips_before and not chips_after:
        failures.append("the transcriptions never came back after the page came down")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a press of the mark reads the whole screen, and the next gives it back")


if __name__ == "__main__":
    main()
