#!/usr/bin/env python3
"""The phone translating a word no dictionary holds, and saying a machine did it.

The browser has had this since the engine went in; the phone had nothing, so a word with no
entry got no meaning at all there - on a product whose point is telling a reader what a word
means. This is the same engine built native, and what this asks is the same question the
browser's check asks: that the word comes back translated, and comes back marked.

The model is served the way the reader's own host would serve it, and fetched once into
PHONETIX_MODELS.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run scripts/proofread/android_guesses.py
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
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-guesses")
MODELS = os.environ.get("PHONETIX_MODELS", "/tmp/phonetix-models")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8928"))

BASE = "https://storage.googleapis.com/bergamot-models-sandbox/0.3.3/esen"
FILES = {
    "model": "model.esen.intgemm.alphas.bin",
    "lex": "lex.50.50.esen.s2t.bin",
    "vocab": "vocab.esen.spm",
}

# Words on the Spanish page that the fixture pack does not hold, so only the engine can
# answer them. What is asserted is not a particular translation but that a word the dictionary
# missed comes back with a meaning and comes back marked; a specific string would be asserting
# what a model happens to say this month.
MISSES = ("corre", "descansa", "parque", "libre", "nueva")


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
    for lang in ("es", "en"):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        if not os.path.exists(source):
            continue
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source,
                   os.path.join(WORK, f"{lang}.pack")], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[:300]}")


SERVED = []


def serve():
    registry = json.dumps([{
        "from": "es", "to": "en",
        "files": {key: {"name": name} for key, name in FILES.items()},
    }]).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            SERVED.append(self.path)
            if self.path == "/models.json":
                body, kind = registry, "application/json"
            elif self.path.startswith("/models/"):
                path = os.path.join(MODELS, os.path.basename(self.path))
                if not os.path.exists(path):
                    self.send_error(404)
                    return
                body, kind = open(path, "rb").read(), "application/octet-stream"
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
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def drawn_words(log):
    """What was written over each word, from the last pass that drew anything."""
    for line in reversed(log.splitlines()):
        if "DRAWN " not in line:
            continue
        pairs = dict(drawn_pairs(line))
        if pairs:
            return pairs
    return {}


def main():
    fetch_model()
    build_packs()
    serve()
    base = f"http://10.0.2.2:{PORT}"
    dev = Device()
    failures = []

    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    # The dictionary, where a reader's own fetch would leave it.
    for lang in ("es",):
        adb("push", os.path.join(WORK, f"{lang}.pack"), f"/data/local/tmp/lex-{lang}.pack",
            timeout=180)
        adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                     f"'cat /data/local/tmp/lex-{lang}.pack > files/lex-{lang}.pack'",
            timeout=180)
    # And the model, the same way: what is being checked is the engine, not the download.
    adb("shell", "run-as io.github.nanbiz.phonetix rm -rf files/models/es-en", timeout=120)
    adb("shell", "run-as io.github.nanbiz.phonetix mkdir -p files/models/es-en", timeout=120)
    # Under the names they are published with: the engine reads what kind of model it is from
    # the file name, so a tidier name is a model it will not open.
    for name in FILES.values():
        adb("push", os.path.join(MODELS, name), f"/data/local/tmp/{name}", timeout=900)
        adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                     f"'cat \"/data/local/tmp/{name}\" > \"files/models/es-en/{name}\"'",
            timeout=900)

    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    # Asked for more than once. Enabling the service restarts the app, and the engine opens a
    # model of seventeen megabytes the first time a word needs it, so the first look is at a
    # screen that is still getting ready.
    log = ""
    over = {}
    # The engine opens a model of seventeen megabytes the first time a direction is needed,
    # which is after the screen has already been read once. So the page is asked for again
    # afterwards: what is being checked is that the words get their meaning, not how many
    # passes it took to load a model.
    for _ in range(6):
        dev.clear_log()
        dev.surface(mode="spanish", packHost=base, target="en", layer="meaning",
                    enable=1, density=1)
        time.sleep(6)
        # Again, so a read happens with the engine already open.
        dev.surface(mode="spanish", packHost=base, target="en", layer="meaning",
                    enable=1, density=1, nudge=1)
        time.sleep(8)
        log = dev.log()
        over = drawn_words(log)
        if any(over.get(word) for word in MISSES):
            break

    started = re.findall(r"TRANSLATOR (\S+) open=(\S+)", log)
    print(f"  the engine: {started[-1] if started else 'never opened'}")
    if not started or started[-1][1] != "true":
        failures.append("the translation engine did not open")

    print(f"  {len(over)} words annotated: {list(over.items())[:5]}")
    guessed = {word: over[word] for word in MISSES if over.get(word)}
    print(f"  words no dictionary holds: {guessed}")
    if not guessed:
        failures.append(
            "no word the dictionary missed came back with a meaning, so the engine filled "
            f"nothing (drawn: {list(over)[:8]})")

    # And that a reader is told a machine said it. Read off a token the service reported
    # rather than off every word: it prints the first token of a batch, which is enough to
    # show what the core stamps on what an engine filled.
    marked = re.findall(r'"state":"(\w+)","gloss":"[^"]+".*?"provenance":(\{[^}]*\})', log)
    guesses = [(state, where) for state, where in marked if '"kind":"guess"' in where]
    print(f"  what the core stamped: {guesses[-1] if guesses else 'nothing'}")
    if not guesses:
        failures.append(
            f"nothing the engine filled is marked as a machine's answer (states: "
            f"{[s for s, _ in marked][-4:]})")
    elif guesses[-1][0] != "Guess":
        failures.append(f"a machine's answer is in state {guesses[-1][0]!r}, not Guess")
    elif '"engine":"bergamot"' not in guesses[-1][1]:
        failures.append(f"a machine's answer does not say which machine: {guesses[-1][1]}")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the phone translates a word no dictionary holds, and says a machine did")


if __name__ == "__main__":
    main()
