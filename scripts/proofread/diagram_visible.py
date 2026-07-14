#!/usr/bin/env python3
"""The mouth diagram must actually be visible, not a blank or a one-colour blob.

The sagittal sections went invisible once: the new series are filled silhouettes,
and inverting them onto the dark panel made a dark shape on a dark ground. Nothing
noticed, because every check only asked whether an <img> existed, not whether it
showed anything. So this screenshots the diagram and asserts it has real contrast —
both dark and light pixels — which a blank or single-colour blob does not.

Run: uv run --with pillow python scripts/proofread/diagram_visible.py
"""
import base64
import io
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

from PIL import Image

PAGE = ("data:text/html;charset=utf-8,"
        "<!doctype html><html lang='en'><meta charset='utf-8'>"
        "<body style='background:#fff;font:22px sans-serif;padding:40px'>"
        "<p>The cat sat.</p></body>")


def main():
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    cdp.ensure_extension()

    tid = cdp.send("Target.createTarget", {"url": "about:blank"})["targetId"]
    s = cdp.send("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]
    cdp.send("Page.enable", session=s)
    cdp.send("Runtime.enable", session=s)
    cdp.send("Emulation.setDeviceMetricsOverride",
             {"width": 640, "height": 500, "deviceScaleFactor": 2, "mobile": False}, session=s)
    # A content script does not run on data: URLs, so serve over http via the suite's
    # server pattern would be heavier; instead navigate a real page.
    import http.server, threading
    html = b"<!doctype html><html lang=en><meta charset=utf-8><body style='background:#fff;font:22px sans-serif;padding:40px'><p>The cat sat here.</p></body>"

    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", str(len(html)))
            self.end_headers()
            self.wfile.write(html)

    httpd = http.server.HTTPServer(("127.0.0.1", 8981), H)
    httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    cdp.send("Page.navigate", {"url": "http://127.0.0.1:8981/"}, session=s, timeout=45)

    def js(expr):
        r = cdp.send("Runtime.evaluate", {"expression": expr, "returnByValue": True}, session=s)
        return r.get("result", {}).get("value")

    for _ in range(20):
        time.sleep(1.5)
        if js("document.querySelectorAll('.phonetix').length"):
            break

    box = js("(() => { const sp=[...document.querySelectorAll('.phonetix')].find(s=>s.dataset.original==='cat');"
             " const r=sp.getBoundingClientRect(); return JSON.stringify({x:r.left+r.width/2, y:r.top+r.height/2}); })()")
    b = json.loads(box)
    for _ in range(3):
        cdp.send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": b["x"], "y": b["y"]}, session=s)
        time.sleep(0.4)
    time.sleep(3.5)  # tooltip opens (700ms) and the diagram is fetched

    # the diagram img's position on screen (through the open shadow root)
    rect = js("(() => { const h=document.getElementById('phonetix-tooltip-host');"
              " const img=h && h.shadowRoot && h.shadowRoot.querySelector('.px-detail-diagram img');"
              " if (!img) return null; const r=img.getBoundingClientRect();"
              " return JSON.stringify({x:r.left, y:r.top, w:r.width, h:r.height}); })()")
    if not rect:
        print("FAIL - the diagram never rendered an image for 'k' (which has one)")
        cdp.close()
        sys.exit(1)
    r = json.loads(rect)

    clip = {"x": r["x"], "y": r["y"], "width": r["w"], "height": r["h"], "scale": 2}
    data = cdp.send("Page.captureScreenshot", {"format": "png", "clip": clip}, session=s)["data"]
    img = Image.open(io.BytesIO(base64.b64decode(data))).convert("L")
    cdp.close()

    px = list(img.getdata())
    darkest, lightest = min(px), max(px)
    spread = lightest - darkest
    # A visible line drawing spans from near-black ink to a light ground; a blank or a
    # one-colour blob has almost no spread.
    ok = spread >= 60
    print(f"diagram luminance {darkest}..{lightest} (spread {spread})")
    if not ok:
        print("FAIL - the diagram shows no contrast; it is blank or a single-colour blob")
        sys.exit(1)
    print("PASS - the diagram is visible")


if __name__ == "__main__":
    main()
