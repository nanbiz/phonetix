#!/usr/bin/env python3
"""A dictionary rebuilt and published again reaches a reader who already holds the old one.

A pack is downloaded once and kept, and kept it was for good: the packs were rebuilt with the
narrow transcriptions in them, and nobody who already held a pack would ever have seen one.
So this serves a Spanish pack, lets the extension fetch and keep it, publishes a different one
in its place - the same dictionary with a word more - restarts the extension the way a browser
does, and asks for that word. Then it takes the host away and asks again, which is answered
only if the new pack is the one now kept.

  uv run python scripts/proofread/pack_update.py
"""
import hashlib
import http.server
import json
import os
import shutil
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-pack-update")
PORT = int(os.environ.get("PHONETIX_UPDATE_PORT", "8936"))
FIXTURE = os.path.join(CORE, "packbuild", "fixtures", "es.jsonl")
# A word the first pack does not have and the second does.
ADDED = {"word": "gato", "pos": "noun", "lang_code": "es",
         "senses": [{"glosses": ["cat"]}], "sounds": [{"ipa": "/ˈɡa.to/"}]}

serving = {"pack": None, "up": True}


def build(name, extra=None):
    source = os.path.join(WORK, f"{name}.jsonl")
    shutil.copy(FIXTURE, source)
    if extra:
        with open(source, "a") as f:
            f.write(json.dumps(extra, ensure_ascii=False) + "\n")
    out = os.path.join(WORK, f"{name}.pack")
    got = subprocess.run(["cargo", "run", "-q", "-p", "packbuild", "--", "es", source, out],
                         cwd=CORE, capture_output=True, text=True)
    if got.returncode != 0:
        raise SystemExit(f"packbuild failed: {got.stderr[-400:]}")
    return out


def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if not serving["up"]:
                self.send_error(503)
                return
            body = open(serving["pack"], "rb").read()
            if self.path == "/packs.json":
                body = json.dumps([{
                    "id": "lex-es", "lang": "es", "built": int(os.path.getmtime(serving["pack"])),
                    "entries": 0, "keys": 0, "glosses": 0, "bytes": len(body),
                    "sha256": hashlib.sha256(body).hexdigest(),
                }]).encode()
                kind = "application/json"
            elif self.path == "/es.pack":
                kind = "application/octet-stream"
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


def main():
    os.makedirs(WORK, exist_ok=True)
    first, second = build("first"), build("second", ADDED)
    serving["pack"] = first
    serve()
    base = f"http://127.0.0.1:{PORT}"
    failures = []
    profile = os.path.join(WORK, "profile")
    shutil.rmtree(profile, ignore_errors=True)
    os.makedirs(profile)

    def browser():
        """The browser started on the reader's profile, as it is after a restart."""
        cdp = PipeCDP(profile=profile)
        cdp.send("Target.setDiscoverTargets", {"discover": True})
        extid = cdp.ensure_extension()
        target = cdp.send("Target.createTarget",
                          {"url": f"chrome-extension://{extid}/viewbook.html"})
        session = cdp.send("Target.attachToTarget",
                           {"targetId": target["targetId"], "flatten": True})["sessionId"]
        cdp.send("Runtime.enable", session=session)
        time.sleep(2)

        def ask(expression):
            got = cdp.send("Runtime.evaluate", {
                "expression": f"(async () => JSON.stringify(await ({expression})))()",
                "awaitPromise": True, "returnByValue": True}, session=session, timeout=120)
            value = got.get("result", {}).get("value")
            return json.loads(value) if value else None
        return cdp, ask

    def cat(ask):
        said = ask("chrome.runtime.sendMessage({phonetix: 'lookUp', data: {word: 'gato',"
                   " source: 'es', target: 'en', accent: '', before: '', drawn: ''}})")
        answer = (said or {}).get("ok") or {}
        # Read into English the dictionary's own sense is the answer.
        return (answer.get("says") or []) + (answer.get("glosses") or [])

    def opened(ask):
        return ask("chrome.runtime.sendMessage({phonetix: 'openPack', data: {lang: 'es'}})")

    try:
        cdp, ask = browser()
        ask(f"chrome.storage.local.set({{packBaseUrl: '{base}', targetLanguage: 'en'}})")
        held = opened(ask)
        before = cat(ask)
        cdp.close()
        print(f"  the first pack, held: {held}; gato means {before}")
        if (held or {}).get("ok") != "es":
            failures.append(f"the first pack did not open: {held}")
        if before:
            failures.append(f"the first pack already knows gato: {before}")

        # Published again, with the word in it, and the browser started again: the pack it
        # holds is what it opens, and the newer one is fetched behind it.
        serving["pack"] = second
        cdp, ask = browser()
        opened(ask)
        after = []
        for _ in range(30):
            after = cat(ask)
            if after:
                break
            time.sleep(1)
        cdp.close()
        print(f"  after the new pack was published: gato means {after}")
        if "cat" not in after:
            failures.append(f"the new pack never replaced the held one: gato means {after}")

        # And kept: with the host gone, the next start answers from what is held.
        serving["up"] = False
        cdp, ask = browser()
        opened(ask)
        kept = cat(ask)
        cdp.close()
        print(f"  with the host gone: gato means {kept}")
        if "cat" not in kept:
            failures.append(f"the new pack was not the one kept: gato means {kept}")
    finally:
        shutil.rmtree(profile, ignore_errors=True)

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a pack published again replaces the one a reader holds, and is the one kept")


if __name__ == "__main__":
    main()
