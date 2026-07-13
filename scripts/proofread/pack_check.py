#!/usr/bin/env python3
"""Verify downloadable dictionary packs: point the extension at a local mock host,
   load a page, and confirm the dictionary is fetched from the host (and cached).
   The real host URL is a runtime setting, never in source.
   Run: uv run python scripts/proofread/pack_check.py"""
import os, sys, time, threading, http.server, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DICTS = os.path.join(ROOT, "public", "dictionaries")
REQUESTS = []
FR_HTML = b"<!doctype html><html lang='fr'><meta charset='utf-8'><body><main><p>La cuisine francaise traditionnelle avec des recettes simples et rapides pour tous.</p></main></body></html>"

def serve(port):
    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            REQUESTS.append(self.path)
            if self.path.startswith("/dictionaries/"):
                p = os.path.join(DICTS, os.path.basename(self.path))
                if os.path.exists(p):
                    b = open(p, "rb").read()
                    self.send_response(200); self.send_header("Content-Type", "application/gzip")
                    self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b); return
                self.send_error(404); return
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(FR_HTML))); self.end_headers(); self.wfile.write(FR_HTML)
    httpd = http.server.HTTPServer(("127.0.0.1", port), H); httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

def main():
    port = 8921; serve(port)
    base = f"http://127.0.0.1:{port}"
    cdp = PipeCDP(); cdp.send("Target.setDiscoverTargets", {"discover": True})
    # Wait for the extension rather than sniffing targets once: on a cold runner
    # the worker is not up yet, and a missing id would silently leave the pack
    # host unset, letting the bundled dictionary pass this test.
    extid = cdp.ensure_extension()
    def tab(url):
        tid = cdp.send("Target.createTarget", {"url": "about:blank"})["targetId"]
        s = cdp.send("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]
        cdp.send("Page.enable", session=s); cdp.send("Runtime.enable", session=s)
        cdp.send("Page.navigate", {"url": url}, session=s); return s, tid
    try:
        # configure the pack host in extension storage (as the popup would), then
        # read it back: a write that silently failed would leave the bundled
        # dictionary serving the page and this test passing for the wrong reason.
        s, tid = tab(f"chrome-extension://{extid}/popup.html"); time.sleep(3)
        stored = None
        for _ in range(10):
            r = cdp.send("Runtime.evaluate", {"expression":
                f"chrome.storage.local.set({{packBaseUrl:'{base}'}})"
                f".then(() => chrome.storage.local.get('packBaseUrl'))"
                f".then(v => JSON.stringify(v))",
                "awaitPromise": True, "returnByValue": True}, session=s)
            stored = r.get("result", {}).get("value")
            if stored and base in stored:
                break
            time.sleep(1)
        if not stored or base not in stored:
            print(f"FAIL - could not configure the pack host (storage={stored})")
            cdp.close(); sys.exit(1)
        cdp.send("Target.closeTarget", {"targetId": tid}); time.sleep(1)

        # load a French page — fr dict should be fetched from the mock host
        s, tid = tab(f"{base}/fr.html"); time.sleep(6)
        n = 0
        for _ in range(12):
            n = cdp.send("Runtime.evaluate", {"expression":
                "document.querySelectorAll('.phonetix').length", "returnByValue": True}, session=s)["result"]["value"]
            if n > 5: break
            time.sleep(2)
        cdp.send("Target.closeTarget", {"targetId": tid})
    finally:
        cdp.close()

    fetched = any("/dictionaries/fr.json.gz" in r for r in REQUESTS)
    print("pack host requests:", [r for r in REQUESTS if "dictionaries" in r])
    print(f"spans on french page: {n}")
    ok = fetched and n > 5
    print("PASS" if ok else "FAIL", "- dictionary served from pack host and used")
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
