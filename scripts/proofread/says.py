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
# A page for the panel to open over, in the reader's own language.
PAGE = (
    b"<!doctype html><html lang=en><meta charset=utf-8><title>Say</title>"
    b"<body><p>Reading a paragraph teaches pronunciation quietly.</p></body></html>"
)

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
    for lang in ("es", "de"):
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
            if self.path == "/page.html":
                body, kind = PAGE, "text/html; charset=utf-8"
            elif self.path == "/models.json":
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
    """The panel as a reader meets it: opened from the settings view over a page, typed into,
    and answered with a card."""
    trouble = []
    page_target = cdp.send("Target.createTarget", {"url": f"http://127.0.0.1:{PORT}/page.html"})
    page = cdp.send(
        "Target.attachToTarget", {"targetId": page_target["targetId"], "flatten": True},
    )["sessionId"]
    cdp.send("Runtime.enable", session=page)
    cdp.send("Page.enable", session=page)
    cdp.send("Emulation.setDeviceMetricsOverride", {
        "width": 900, "height": 760, "deviceScaleFactor": 1, "mobile": False,
    }, session=page)
    time.sleep(3)

    view = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/popup.html"})
    session = cdp.send(
        "Target.attachToTarget", {"targetId": view["targetId"], "flatten": True},
    )["sessionId"]
    cdp.send("Runtime.enable", session=session)
    time.sleep(3)

    def evaluate(on, expression):
        got = cdp.send("Runtime.evaluate", {
            "expression": expression, "returnByValue": True,
        }, session=on, timeout=300)
        return got.get("result", {}).get("value")

    # The settings view's own button, pressed: it opens the panel on the page it is over.
    pressed = evaluate(session, """
        (() => {
          const button = document.querySelector('[data-does=open-panel]');
          if (!button) return 'no button to open the panel';
          if (button.disabled) return 'the button is off: the view found no page';
          button.click();
          return 'pressed';
        })()
    """)
    if pressed != "pressed":
        return [f"the settings view has no way to ask: {pressed}"]

    in_panel = """
      (() => {
        const host = document.getElementById('phonetix-card-host-ask');
        const root = host && host.shadowRoot;
        if (!root) return null;
        return (%s)(root);
      })()
    """
    typed = None
    for _ in range(20):
        time.sleep(0.5)
        # Typed the way a field is typed into: its value set through the element's own setter
        # and the input reported, which is what the panel listens for.
        typed = evaluate(page, in_panel % ("""
            (root) => {
              const field = root.querySelector('[data-ask] .field input');
              if (!field) return null;
              const set = Object.getOwnPropertyDescriptor(
                window.HTMLInputElement.prototype, 'value').set;
              set.call(field, %s);
              field.dispatchEvent(new Event('input', {bubbles: true}));
              return 'typed';
            }
        """ % json.dumps(WANTED)))
        if typed:
            break
    if typed != "typed":
        return ["the button opened no panel to type in"]
    # The engine has the direction open by now, but the panel has a round trip of its own.
    drawn = ""
    for _ in range(24):
        time.sleep(2.5)
        drawn = evaluate(page, in_panel % """
            (root) => {
              const card = root.querySelector('[data-ask] .answer .card');
              return card ? card.innerText.replace(/\\s+/g, ' ').slice(0, 120) : '';
            }
        """) or ""
        if EXPECTED in drawn.lower():
            break
    print(f"  the panel answers with: {drawn!r}")
    if not drawn:
        trouble.append("the panel drew no card for a word that was answered")
    elif EXPECTED not in drawn.lower():
        trouble.append(f"the card in the panel is not about {EXPECTED!r}: {drawn!r}")

    os.makedirs(SHOTS, exist_ok=True)
    got = cdp.send("Page.captureScreenshot", {"format": "png"}, session=page)
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
                          f"targetLanguage:'en',selectedLanguage:'es',learning:'es'}})",
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
        # A language nothing is published for, since the published release has most pairs.
        none = ask(cdp, session, {
            "phonetix": "say", "data": {"text": "bench", "source": "xx", "target": "en"},
        })
        print(f"  a direction with no model: {none.get('ok')}")
        if not (none.get("ok") or {}).get("missing"):
            failures.append(f"a direction with no model was not reported as one: {none}")

        # And the surface a reader actually has: the row in the settings view, the field
        # behind it, and the card it answers with. A handler nobody can reach answers nobody.
        failures += in_the_view(cdp, extid)

        # Last, since fetching German changes which language the view above offers first.
        # And a direction with no model, between two languages the reader holds dictionaries
        # for: the dictionaries answer it themselves, "Hund" through its gloss "dog" to
        # "perro", rather than the reader being told nothing can be had.
        ask(cdp, session, {"phonetix": "getPack", "data": {"lang": "de"}}, tries=3)
        by_hand = ask(cdp, session, {
            "phonetix": "say", "data": {"text": "Hund", "source": "es", "target": "de"},
        }, tries=3, gap=5)
        found = ((by_hand.get("ok") or {}).get("answer") or {})
        print(f"  'Hund' in Spanish, with no German to Spanish model: "
              f"{found.get('spelling')!r} ({found.get('state')})")
        if found.get("spelling") != "perro":
            failures.append(f"the dictionaries did not answer 'Hund' in Spanish: {by_hand}")

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
