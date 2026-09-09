#!/usr/bin/env python3
"""The core, answering inside a real browser with the extension loaded.

The node check proves the WebAssembly build agrees with the phone. It does not prove the
extension can reach that build: a service worker has its own module loading, its own content
security policy, and its own storage, and every one of those can refuse a compiled module
while the crate itself is perfectly fine.

So this drives Chrome with the built extension, points it at a local host serving packs the
way the release host would, and asks the background for the same words the node check asks
for. Both answers have to be the same text.

  uv run python scripts/proofread/lex_extension.py
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
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-lex-extension")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8923"))

# The same words the two-platform check uses, so a disagreement here is a disagreement about
# the extension rather than about which words were asked.
WORDS = ["perro", "perros", "camino", "banco", "murciélago"]

SERVED = []

# A control. With this set the extension is asked for a different reader's language than the
# reference was, so every word has to come back as a disagreement: a check that cannot be made
# to fail is not evidence.
TARGET = "en" if os.environ.get("PHONETIX_DRIFT") else "de"


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900, **kw)


def build_packs():
    """Two packs from lines of the dump, as CI would build them."""
    os.makedirs(WORK, exist_ok=True)
    for lang in ("es", "de"):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        out = os.path.join(WORK, f"{lang}.pack")
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source, out], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[-400:]}")


def reference():
    """What the same packs answer under node, which is the yardstick."""
    out = os.path.join(WORK, "wasm")
    got = run(["cargo", "build", "-q", "-p", "lexcore-wasm",
               "--target", "wasm32-unknown-unknown", "--release"], cwd=CORE)
    if got.returncode != 0:
        raise SystemExit(f"the wasm build failed: {got.stderr[-400:]}")
    wasm = os.path.join(CORE, "target/wasm32-unknown-unknown/release/lexcore_wasm.wasm")
    got = run(["wasm-bindgen", "--target", "nodejs", "--out-dir", out, wasm])
    if got.returncode != 0:
        raise SystemExit(f"wasm-bindgen failed: {got.stderr[-400:]}")
    script = os.path.join(WORK, "ask.js")
    with open(script, "w") as f:
        f.write(
            "const fs = require('fs');\n"
            f"const {{ Core }} = require({out + '/lexcore_wasm.js'!r});\n"
            "const core = new Core();\n"
            "core.openPack(fs.readFileSync(process.argv[2]));\n"
            "core.openPack(fs.readFileSync(process.argv[3]));\n"
            "for (const word of process.argv.slice(4)) {\n"
            "  console.log(word + '\\t' + core.lookUp(word, 'es', 'de'));\n"
            "}\n"
        )
    got = run(["node", script, os.path.join(WORK, "es.pack"), os.path.join(WORK, "de.pack"),
               *WORDS])
    if got.returncode != 0:
        raise SystemExit(f"node failed: {got.stderr[-400:]}")
    return dict(line.split("\t", 1) for line in got.stdout.strip().splitlines() if "\t" in line)


def serve():
    """A stand-in for the release host, serving the packs it would serve."""
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            SERVED.append(self.path)
            name = os.path.basename(self.path)
            path = os.path.join(WORK, name)
            if self.path.startswith("/packs/") and os.path.exists(path):
                body = open(path, "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_error(404)

    httpd = http.server.HTTPServer(("127.0.0.1", PORT), Handler)
    httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def ask(cdp, session, message, tries=1):
    """One message to the background, in the shape the host speaks."""
    expression = (
        "chrome.runtime.sendMessage(" + json.dumps(message) + ")"
        ".then(r => JSON.stringify(r)).catch(e => JSON.stringify({failed: String(e)}))"
    )
    last = None
    for _ in range(tries):
        got = cdp.send("Runtime.evaluate", {
            "expression": expression, "awaitPromise": True, "returnByValue": True,
        }, session=session)
        last = got.get("result", {}).get("value")
        if last and '"failed"' not in last:
            return json.loads(last)
        time.sleep(2)
    return json.loads(last) if last else {"failed": "no answer"}


def main():
    build_packs()
    want = reference()
    serve()
    base = f"http://127.0.0.1:{PORT}"

    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    failures = []
    try:
        target = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/viewbook.html"})
        session = cdp.send(
            "Target.attachToTarget", {"targetId": target["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=session)
        time.sleep(3)

        # The host and the reader's own language, written the way the popup writes them.
        # Nothing about the host is in the source.
        cdp.send("Runtime.evaluate", {
            "expression": f"chrome.storage.local.set({{packBaseUrl:'{base}',"
                          f"targetLanguage:'de',selectedLanguage:'es'}})",
            "awaitPromise": True, "returnByValue": True,
        }, session=session)

        # Both packs, fetched from the host and opened in the core.
        for lang in ("es", "de"):
            answer = ask(cdp, session, {"phonetix": "openPack", "data": {"lang": lang}}, tries=6)
            if answer.get("ok") != lang:
                failures.append(f"{lang}: the pack did not open ({answer})")

        got = ask(cdp, session, {"phonetix": "languages", "data": {}})
        if sorted(got.get("ok") or []) != ["de", "es"]:
            failures.append(f"the core holds {got.get('res')}, not both packs")

        # A batch of runs, the way a page asks: the core finds the words, decides which are
        # annotated and what each means, and the host draws exactly that.
        sentence = "El perro corre por el camino y descansa en el banco."
        batch = ask(cdp, session, {
            "phonetix": "annotate",
            "data": {
                "runs": [{"id": 11, "text": sentence}],
                "source": "es", "target": TARGET,
                "options": {"mode": "gloss", "density": 1},
            },
        })
        drawn = (batch.get("ok") or {}).get("tokens")
        if not drawn:
            failures.append(f"the core annotated nothing ({batch})")
        else:
            words = [t["spelling"] for t in drawn]
            if words != sentence.replace(".", "").split():
                failures.append(f"the words of the run came back as {words}")
            # Every token has to say where it is, in the host's own indexing, or the page
            # would be annotated in the wrong places.
            for token in drawn:
                if sentence[token["start"]:token["end"]] != token["spelling"]:
                    failures.append(
                        f"{token['spelling']!r} claims {token['start']}..{token['end']}, "
                        f"which is {sentence[token['start']:token['end']]!r}")
            perro = next((t for t in drawn if t["spelling"] == "perro"), None)
            says = "Hund" if TARGET == "de" else "dog"
            if not perro or perro.get("gloss") != says:
                failures.append(f"perro was annotated {perro and perro.get('gloss')!r}")
            if not perro or not perro.get("inline"):
                failures.append("nothing was drawn at the densest setting")
            print(f"\n  {len(drawn)} words annotated, "
                  f"{sum(1 for t in drawn if t['inline'])} of them drawn, "
                  f"{sum(1 for t in drawn if t['gloss'])} answered")

        # A sparse setting draws fewer of them, which is the reader's bar doing its one job.
        sparse = ask(cdp, session, {
            "phonetix": "annotate",
            "data": {
                "runs": [{"id": 12, "text": sentence}],
                "source": "es", "target": TARGET,
                "options": {"mode": "gloss", "density": 50},
            },
        })
        few = [t for t in ((sparse.get("ok") or {}).get("tokens") or []) if t["inline"]]
        many = [t for t in (drawn or []) if t["inline"]]
        if drawn and len(few) >= len(many):
            failures.append(f"a sparse page drew {len(few)} of {len(many)}")

        # The words themselves, compared with what node got from the same bytes.
        for word in WORDS:
            answer = ask(cdp, session, {"phonetix": "lookUp", "data": {"word": word, "source": "es", "target": TARGET}})
            here = answer.get("ok")
            if here is None:
                failures.append(f"{word}: the extension answered nothing ({answer})")
                continue
            # Compared as values rather than as text: both sides parsed the same JSON, and
            # key order is not something either promises.
            there = json.loads(want[word])
            if here != there:
                failures.append(f"{word}: extension {here} vs core {there}")
            else:
                said = here.get("says") or here.get("glosses") or ["nothing"]
                print(f"  {word:12} {here['state']:10} {said[0]}")
    finally:
        cdp.close()

    fetched = [p for p in SERVED if p.startswith("/packs/")]
    print(f"\npacks served from the host: {fetched}")
    if len(fetched) != 2:
        failures.append(f"expected both packs to come from the host, got {fetched}")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print(f"PASS - the extension's core answers {len(WORDS)} words as the core does")


if __name__ == "__main__":
    main()
