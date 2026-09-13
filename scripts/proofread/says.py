#!/usr/bin/env python3
"""The other direction: the word for something a reader wants to say.

Everything else in the product answers a word somebody else wrote. Taplex answered the
question the other way round - type what you want to say, get the word, and get that word's
own entry under it so the machine's answer can be judged rather than taken - and the merge
dropped it.

So this serves both directions of a real model the way a reader's own host would, asks for an
English word in Spanish, and checks that what comes back is the Spanish word with its entry:
how it is said, and what it means back.

  uv run scripts/proofread/says.py
"""
import base64
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
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-says")
MODELS = os.environ.get("PHONETIX_MODELS", "/tmp/phonetix-models")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8933"))
SHOTS = os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-says")

# Both directions, because saying something runs the model the other way: the reader types in
# the language they already have.
SANDBOX = "https://storage.googleapis.com/bergamot-models-sandbox/0.3.3"
PAIRS = {
    ("es", "en"): {
        "model": "model.esen.intgemm.alphas.bin",
        "lex": "lex.50.50.esen.s2t.bin",
        "vocab": "vocab.esen.spm",
    },
    ("en", "es"): {
        "model": "model.enes.intgemm.alphas.bin",
        "lex": "lex.50.50.enes.s2t.bin",
        # The same vocabulary both ways: the pair is published with one, and asking for an
        # enes one gets a 404.
        "vocab": "vocab.esen.spm",
    },
}

# A word the fixture pack does hold in Spanish, so the entry under the machine's answer is a
# real one: what is being checked is that the answer arrives with its entry, not what a model
# says this month.
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
                raise SystemExit(
                    f"the {source}-{target} model could not be fetched ({name}). "
                    f"Put it in {MODELS} or set PHONETIX_MODELS.")


def build_packs():
    os.makedirs(WORK, exist_ok=True)
    for lang in ("es",):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source,
                   os.path.join(WORK, f"{lang}.pack")], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[:400]}")


def serve():
    registry = json.dumps([
        {"from": source, "to": target,
         "files": {key: {"name": name} for key, name in files.items()}}
        for (source, target), files in PAIRS.items()
    ]).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
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


def ask(cdp, session, message, tries=1, gap=5):
    expression = (
        "chrome.runtime.sendMessage(" + json.dumps(message) + ")"
        ".then(r => JSON.stringify(r)).catch(e => JSON.stringify({failed: String(e)}))"
    )
    last = None
    for _ in range(tries):
        got = cdp.send("Runtime.evaluate", {
            "expression": expression, "awaitPromise": True, "returnByValue": True,
        }, session=session, timeout=300)
        last = got.get("result", {}).get("value")
        if last and '"failed"' not in last:
            return json.loads(last)
        time.sleep(gap)
    return json.loads(last) if last else {"failed": "no answer"}


def in_the_view(cdp, extid):
    """The say screen as a reader meets it, driven the way a reader drives it."""
    trouble = []
    target = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/popup.html"})
    session = cdp.send(
        "Target.attachToTarget", {"targetId": target["targetId"], "flatten": True},
    )["sessionId"]
    cdp.send("Runtime.enable", session=session)
    cdp.send("Page.enable", session=session)
    cdp.send("Emulation.setDeviceMetricsOverride", {
        "width": 384, "height": 760, "deviceScaleFactor": 1, "mobile": False,
    }, session=session)
    time.sleep(3)

    def evaluate(expression, wait=False):
        got = cdp.send("Runtime.evaluate", {
            "expression": expression, "awaitPromise": wait, "returnByValue": True,
        }, session=session, timeout=300)
        return got.get("result", {}).get("value")

    opened = evaluate("""
        (() => {
          const row = document.querySelector('button.nav[data-row=say]');
          if (!row) return 'no row';
          row.click();
          return 'opened';
        })()
    """)
    if opened != "opened":
        return [f"the settings view has no way to ask: {opened}"]
    time.sleep(1)
    # Typed and committed the way a reader commits a field: the value changes and the field
    # reports it, which is what the view listens for.
    typed = evaluate(f"""
        (() => {{
          const field = document.querySelector('[data-view=say] input');
          if (!field) return 'no field';
          field.value = {json.dumps(WANTED)};
          field.dispatchEvent(new Event('change', {{bubbles: true}}));
          return 'typed';
        }})()
    """)
    if typed != "typed":
        return [f"the say screen has no field to type in: {typed}"]
    # The engine has the direction open by now, but the view has a round trip of its own.
    for _ in range(12):
        time.sleep(5)
        drawn = evaluate("""
            (() => {
              const card = document.querySelector('[data-view=say] .answer .card');
              if (!card) return '';
              return card.innerText.replace(/[\\s]+/g, ' ').slice(0, 120);
            })()
        """)
        if drawn:
            break
    print(f"  the view answers with: {drawn!r}")
    if not drawn:
        trouble.append("the say screen drew no card for a word that was answered")
    elif EXPECTED not in drawn.lower():
        trouble.append(f"the card on the say screen is not about {EXPECTED!r}: {drawn!r}")

    os.makedirs(SHOTS, exist_ok=True)
    got = cdp.send("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": True},
                   session=session)
    path = os.path.join(SHOTS, "say.png")
    with open(path, "wb") as f:
        f.write(base64.b64decode(got["data"]))
    print(f"  a picture of it: {path}")
    return trouble


def main():
    fetch_models()
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

        # The question itself. The model for the reverse direction is fetched on the first
        # ask, so this is given room to do it.
        said = ask(cdp, session, {
            "phonetix": "say",
            "data": {"text": WANTED, "source": "es", "target": "en"},
        }, tries=4, gap=15)
        answer = (said.get("ok") or {}).get("answer")
        print(f"  {WANTED!r} in Spanish: {json.dumps(answer)[:240] if answer else 'nothing'}")

        if not answer:
            failures.append(f"nothing came back for {WANTED!r}")
        else:
            if answer.get("spelling", "").lower() != EXPECTED:
                failures.append(
                    f"the word is {answer.get('spelling')!r}, not {EXPECTED!r}")
            # The entry under it is what makes the answer judgeable: how it is said, and what
            # it means back in the reader's own language.
            if not answer.get("ipa"):
                failures.append(f"the word came back without its pronunciation: {answer}")
            if not answer.get("says"):
                failures.append(f"the word came back without a meaning: {answer}")
            print(f"  said {answer.get('ipa')!r}, means {answer.get('says')}")

        # And a word nothing can be made of comes back as nothing, rather than as the reader's
        # own text handed back to them wearing a card.
        nothing = ask(cdp, session, {
            "phonetix": "say", "data": {"text": "", "source": "es", "target": "en"},
        })
        if (nothing.get("ok") or {}).get("answer") is not None:
            failures.append(f"an empty question was answered: {nothing}")

        # And a direction the reader's host publishes no model for says so, rather than
        # telling them their word does not exist.
        none = ask(cdp, session, {
            "phonetix": "say", "data": {"text": "bench", "source": "fr", "target": "en"},
        })
        print(f"  a direction with no model: {none.get('ok')}")
        if not (none.get("ok") or {}).get("missing"):
            failures.append(f"a direction with no model was not reported as one: {none}")

        # And the surface a reader actually has: the row in the settings view, the field
        # behind it, and the card it answers with. A handler nobody can reach answers nobody.
        failures += in_the_view(cdp, extid)
    finally:
        cdp.close()

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the product answers the word for something a reader wants to say")


if __name__ == "__main__":
    main()
