#!/usr/bin/env python3
"""A word no dictionary holds, translated anyway, and marked as a machine's answer.

The cascade answers what a dictionary can and reports the rest as misses. Until the engine
existed the host dropped every one of those on the floor: a word with no entry got no meaning
at all, on a product whose whole point is that a reader is told what a word means. Taplex had
this and called it a guess; the merge lost it.

So this serves a real translation model the way the reader's own host would, points the
extension at it, and asks for a sentence with a word the fixture packs do not hold. What
matters is not only that something comes back, but that it comes back marked: a machine's
answer wearing a dictionary's authority is what the whole cascade is shaped to avoid.

The model is fetched once into PHONETIX_MODELS and reused. Without it, this says so and stops
rather than passing quietly.

  uv run scripts/proofread/translates.py
"""
import http.server
import json
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-translates")
MODELS = os.environ.get("PHONETIX_MODELS", "/tmp/phonetix-models")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8927"))

# What the model has to be, and where it is published. Fetched rather than committed: it is
# seventeen megabytes of somebody else's weights.
BASE = "https://storage.googleapis.com/bergamot-models-sandbox/0.3.3/esen"
FILES = {
    "model": "model.esen.intgemm.alphas.bin",
    "lex": "lex.50.50.esen.s2t.bin",
    "vocab": "vocab.esen.spm",
}

# A Spanish sentence whose words the fixture packs do not hold, so nothing but the engine can
# answer it. "murciélago" is the word the other checks use for exactly this: a real word, and
# a miss.
SENTENCE = "El murciélago vuela sobre la montaña."
WORD = "murciélago"


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
            raise SystemExit(
                f"the translation model could not be fetched ({name}). "
                f"Put it in {MODELS} or set PHONETIX_MODELS.")


def build_packs():
    os.makedirs(WORK, exist_ok=True)
    for lang in ("es", "de"):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source,
                   os.path.join(WORK, f"{lang}.pack")], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[:400]}")


SERVED = []


def serve():
    """The reader's own host: the packs, and the translation models beside them."""
    registry = json.dumps([{
        "from": "es",
        "to": "en",
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
            elif self.path.startswith("/packs/"):
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

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def ask(cdp, session, message, tries=1, gap=3):
    expression = (
        "chrome.runtime.sendMessage(" + json.dumps(message) + ")"
        ".then(r => JSON.stringify(r)).catch(e => JSON.stringify({failed: String(e)}))"
    )
    last = None
    for _ in range(tries):
        got = cdp.send("Runtime.evaluate", {
            "expression": expression, "awaitPromise": True, "returnByValue": True,
        }, session=session, timeout=180)
        last = got.get("result", {}).get("value")
        if last and '"failed"' not in last:
            return json.loads(last)
        time.sleep(gap)
    return json.loads(last) if last else {"failed": "no answer"}


def main():
    fetch_model()
    build_packs()
    serve()
    base = f"http://127.0.0.1:{PORT}"

    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    failures = []
    try:
        target = cdp.send("Target.createTarget",
                          {"url": f"chrome-extension://{extid}/viewbook.html"})
        session = cdp.send(
            "Target.attachToTarget", {"targetId": target["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=session)
        time.sleep(3)

        cdp.send("Runtime.evaluate", {
            "expression": f"chrome.storage.local.set({{packBaseUrl:'{base}',"
                          f"targetLanguage:'en',selectedLanguage:'es'}})",
            "awaitPromise": True, "returnByValue": True,
        }, session=session)

        opened = ask(cdp, session, {"phonetix": "openPack", "data": {"lang": "es"}}, tries=6)
        if opened.get("ok") != "es":
            failures.append(f"the Spanish pack did not open ({opened})")

        # The word first, on its own: the dictionary has nothing for it, which is what makes
        # the rest of this check about the engine.
        alone = ask(cdp, session, {
            "phonetix": "lookUp",
            "data": {"word": WORD, "source": "es", "target": "en"},
        })
        state = (alone.get("ok") or {}).get("state")
        print(f"  the dictionary on {WORD}: {state}")
        if state != "None":
            failures.append(
                f"{WORD} is answered by a pack ({state}), so this proves nothing about the engine")

        # The page, annotated. The engine is loaded and the model fetched on the first ask, so
        # this is given room to do it.
        batch = ask(cdp, session, {
            "phonetix": "annotate",
            "data": {
                "runs": [{"id": 1, "text": SENTENCE}],
                "source": "es", "target": "en",
                "options": {"mode": "gloss", "density": 1},
            },
        }, tries=4, gap=10)
        tokens = (batch.get("ok") or {}).get("tokens") or []
        if not tokens:
            print(f"FAIL - nothing was annotated ({batch})")
            sys.exit(1)

        answered = [(t["spelling"], t["gloss"], t.get("provenance")) for t in tokens if t["gloss"]]
        print(f"  {len(answered)} of {len(tokens)} words carry a meaning")
        for spelling, gloss, _ in answered[:6]:
            print(f"    {spelling:14} {gloss}")

        got = next((t for t in tokens if t["spelling"] == WORD), None)
        if not got or not got.get("gloss"):
            failures.append(
                f"{WORD} came back with no meaning, so the engine filled nothing ({got})")
        else:
            # What it says. The model is a real one, so this is the word's actual translation.
            if "bat" not in got["gloss"].lower():
                failures.append(f"{WORD} was translated {got['gloss']!r}")
            # And that a reader is told a machine said it.
            kind = (got.get("provenance") or {}).get("kind")
            engine = (got.get("provenance") or {}).get("engine")
            print(f"  {WORD} -> {got['gloss']!r}, from {kind} {engine or ''}")
            if kind != "guess":
                failures.append(
                    f"a machine's answer is marked {kind!r}, not as a guess")
            if got.get("state") != "Guess":
                failures.append(f"the token's state is {got.get('state')!r}, not Guess")

        # Several words at once, which is a gesture of its own and only an engine can answer.
        # Asked of the host the way the page asks it after a drag.
        said = ask(cdp, session, {
            "phonetix": "phrase",
            "data": {"text": "El murciélago vuela sobre la montaña",
                     "source": "es", "target": "en"},
        }, tries=3, gap=6)
        clause = said.get("ok") or {}
        print(f"  the phrase: {clause.get('says')} ({clause.get('state')})")
        if clause.get("state") != "Phrase":
            failures.append(f"a selection came back as {clause.get('state')!r}, not a phrase")
        if not (clause.get("says") or []):
            failures.append("a selection came back with no translation at all")
        elif "bat" not in clause["says"][0].lower():
            failures.append(f"the phrase was translated {clause['says'][0]!r}")
        if (clause.get("provenance") or {}).get("kind") != "guess":
            failures.append("a phrase is not marked as a machine's answer")
        if clause.get("ipa"):
            failures.append("a phrase carries a transcription, which is nobody's question")

        # The models came from the reader's own host and nowhere else.
        asked = [p for p in SERVED if p.startswith("/models")]
        print(f"  asked of the host: {sorted(set(asked))}")
        if not asked:
            failures.append("no model was fetched from the host at all")
    finally:
        cdp.close()

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a word no dictionary holds is translated, and marked as a guess")


if __name__ == "__main__":
    main()
