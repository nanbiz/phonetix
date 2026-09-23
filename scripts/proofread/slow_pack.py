#!/usr/bin/env python3
"""A dictionary that takes a minute to arrive, fetched behind a page's back.

The page asks for a language, the worker answers with what it carries and fetches the
dictionary behind that answer, and nothing else happens. Chrome documents that a worker which
neither receives an event nor calls an extension API for thirty seconds is ended, and a
response body streaming in is neither, so the fetch reads the body in pieces and keeps the
worker awake while it does (src/host/whole.ts).

This serves the pack a little at a time over a minute, asks for the language once, closes the
extension's own page and leaves the worker alone: any message in between would count as
activity. Only afterwards is it asked what it holds.

What this does not show is the worker being ended without that: a browser driven over DevTools
kept the worker alive either way here, before the change as after it. What it holds to is that
a download a minute long, read the way it now is, arrives whole and is kept.

  uv run scripts/proofread/slow_pack.py
"""
import hashlib
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
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-slow-pack")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "0"))

# How long the pack takes to arrive, and how long the worker is then left alone: well past the
# thirty seconds after which an idle worker is ended.
DRIP_S = 60
LEFT_ALONE_S = DRIP_S + 20


def build_pack():
    os.makedirs(WORK, exist_ok=True)
    out = os.path.join(WORK, "es.pack")
    got = subprocess.run(
        ["cargo", "run", "-q", "-p", "packbuild", "--", "es",
         os.path.join(CORE, "packbuild", "fixtures", "es.jsonl"), out],
        cwd=CORE, capture_output=True, text=True, timeout=1800)
    if got.returncode != 0:
        raise SystemExit(f"packbuild failed: {got.stderr[:400]}")
    return out


def serve(pack):
    body = open(pack, "rb").read()
    listing = json.dumps([{
        "id": "lex-es", "lang": "es", "built": 0, "entries": 3, "keys": 4, "glosses": 3,
        "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest(),
    }]).encode()
    served = {"started": None, "finished": None}

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path == "/packs.json":
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(listing)))
                self.end_headers()
                self.wfile.write(listing)
                return
            if self.path != "/es.pack":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            served["started"] = time.time()
            # A piece every second or so, the way a slow line delivers it: the response
            # starts at once and the body takes the whole minute.
            pieces = 40
            size = max(1, -(-len(body) // pieces))
            try:
                for at in range(0, len(body), size):
                    self.wfile.write(body[at:at + size])
                    self.wfile.flush()
                    time.sleep(DRIP_S / pieces)
                served["finished"] = time.time()
            except (BrokenPipeError, ConnectionResetError):
                served["finished"] = "cut off"

    # Whatever port is free unless one is asked for: the ordinary ones are often taken here.
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    served["port"] = httpd.server_address[1]
    return served


def ask(cdp, session, message):
    expression = (
        "chrome.runtime.sendMessage(" + json.dumps(message) + ")"
        ".then(r => JSON.stringify(r)).catch(e => JSON.stringify({failed: String(e)}))"
    )
    got = cdp.send("Runtime.evaluate", {
        "expression": expression, "awaitPromise": True, "returnByValue": True,
    }, session=session, timeout=180)
    value = got.get("result", {}).get("value")
    return json.loads(value) if value else {"failed": "no answer"}


def main():
    served = serve(build_pack())
    base = f"http://127.0.0.1:{served['port']}"
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
            "expression": f"chrome.storage.local.set({{packBaseUrl:'{base}'}})",
            "awaitPromise": True, "returnByValue": True,
        }, session=session)

        # The page asks for the language; the worker answers with what it carries and starts
        # fetching the dictionary behind that answer.
        opened = ask(cdp, session, {"phonetix": "openPack", "data": {"lang": "es"}})
        print(f"  asked for Spanish: {opened}")
        # And then nothing, for longer than an idle worker is allowed, with no page of the
        # extension open either: a reader asked from a web page and went on reading it.
        cdp.send("Target.closeTarget", {"targetId": target["targetId"]})
        time.sleep(LEFT_ALONE_S)
        target = cdp.send("Target.createTarget",
                          {"url": f"chrome-extension://{extid}/viewbook.html"})
        session = cdp.send(
            "Target.attachToTarget", {"targetId": target["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=session)
        time.sleep(3)
        finished = served["finished"]
        took = (finished - served["started"]) if isinstance(finished, float) else None
        print(f"  the pack was served over {took:.0f}s" if took else
              f"  the pack was {finished or 'never asked for'}")
        held = ask(cdp, session, {"phonetix": "packs", "data": {}})
        print(f"  held afterwards: {(held.get('ok') or {}).get('held')}")
        if served["started"] is None:
            failures.append("the dictionary was never asked for")
        elif not isinstance(finished, float):
            failures.append(f"the download was {finished or 'never finished'}")
        if "es" not in ((held.get("ok") or {}).get("held") or []):
            failures.append("the dictionary that took a minute to arrive was never kept")
    finally:
        cdp.close()

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a dictionary that takes a minute to arrive is still kept")


if __name__ == "__main__":
    main()
