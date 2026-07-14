#!/usr/bin/env python3
"""Hovering a word may repaint it. It may not move it.

Every geometric check of this so far has been fooled: an element's box hides a
padding shift (the box grows, its edge does not move), and a centred box hides a
sideways one. So this measures the thing the reader actually sees — the pixels.

The IPA of a word is replaced with the word itself, so the layer revealed on hover
is the very same text. Then the letters must land on exactly the pixels they were
already on: the glyphs are thresholded out of a screenshot taken before and during
a real hover, and their bounding box and mass compared. Any shift, any growth, and
the two differ.

Run: uv run --with pillow python scripts/proofread/hover_pixels.py
"""
import base64
import functools
import http.server
import io
import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

from PIL import Image

PORT = 8931
PAGE = (
    b"<!doctype html><html lang='en'><meta charset='utf-8'>"
    b"<body style='background:#fff;margin:0;padding:40px;font:20px/1.6 serif'>"
    b"<p id='prose'>The quick brown fox jumps over the lazy dog while the river runs "
    b"quietly past the old stone bridge and the morning light returns.</p></body></html>"
)


def serve(port):
    """A content script does not run on a data: URL, so the page is served."""
    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)

    httpd = http.server.HTTPServer(("127.0.0.1", port), H)
    httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

failures = []


def glyphs(img: Image.Image):
    """The dark pixels of the text: their bounding box and how many there are."""
    grey = img.convert("L")
    mask = grey.point(lambda v: 255 if v < 128 else 0)
    return mask.getbbox(), sum(1 for p in mask.getdata() if p)


def main():
    serve(PORT)
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    cdp.ensure_extension()

    tid = cdp.send("Target.createTarget", {"url": "about:blank"})["targetId"]
    s = cdp.send("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]
    cdp.send("Page.enable", session=s)
    cdp.send("Runtime.enable", session=s)
    cdp.send("Emulation.setDeviceMetricsOverride",
             {"width": 900, "height": 400, "deviceScaleFactor": 2, "mobile": False}, session=s)
    cdp.send("Page.navigate", {"url": f"http://127.0.0.1:{PORT}/"}, session=s, timeout=45)

    def js(expr):
        r = cdp.send("Runtime.evaluate", {"expression": expr, "returnByValue": True}, session=s)
        return r.get("result", {}).get("value")

    # wait for the extension to transform the page
    for _ in range(25):
        time.sleep(1.5)
        if js("document.querySelectorAll('#prose .phonetix').length"):
            break
    else:
        print("FAIL - the extension never transformed the page")
        sys.exit(1)

    for mode, hidden, shown in [
        ("px-mode-hover", ".px-orig", ".px-ipa"),
        ("px-mode-reveal", ".px-ipa", ".px-orig"),
    ]:
        # The revealed layer is made the same text as the layer at rest, so that a
        # correct hover is pixel-for-pixel invisible and any movement is not.
        box = js(f"""(() => {{
          const h = document.documentElement;
          h.classList.remove('px-mode-hover', 'px-mode-reveal');
          h.classList.add('{mode}');
          const span = [...document.querySelectorAll('#prose .phonetix')][4];
          span.querySelector('{shown}').textContent = span.querySelector('{hidden}').textContent;
          const r = span.getBoundingClientRect();
          return JSON.stringify({{
            x: r.left + r.width / 2, y: r.top + r.height / 2,
            // Only the word itself: the blur is meant to soften what lies around it,
            // so a neighbour inside the frame would register as a change.
            clip: {{x: r.left - 1, y: r.top - 2, width: r.width + 2, height: r.height + 4}},
          }});
        }})()""")
        info = json.loads(box)
        clip = dict(info["clip"], scale=2)

        def shot():
            data = cdp.send("Page.captureScreenshot", {"format": "png", "clip": clip}, session=s)["data"]
            return Image.open(io.BytesIO(base64.b64decode(data)))

        cdp.send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": 5, "y": 5}, session=s)
        time.sleep(0.4)
        before = shot()

        cdp.send("Input.dispatchMouseEvent",
                 {"type": "mouseMoved", "x": info["x"], "y": info["y"]}, session=s)
        time.sleep(0.6)
        hovered = js("[...document.querySelectorAll('#prose .phonetix')][4].matches(':hover')")
        after = shot()

        if not hovered:
            failures.append(f"{mode}: the browser never entered :hover")
            continue

        box_before, mass_before = glyphs(before)
        box_after, mass_after = glyphs(after)

        moved = max(abs(a - b) for a, b in zip(box_before, box_after))
        grew = abs(mass_after - mass_before) / max(mass_before, 1)

        ok = moved <= 1 and grew < 0.02
        print(f"  {'PASS' if ok else 'FAIL'}  {mode}: text box {box_before} -> {box_after}, "
              f"ink {mass_before} -> {mass_after}")
        if not ok:
            failures.append(
                f"{mode}: the same text moved {moved}px and changed mass by {grew:.1%} on hover")

    cdp.send("Target.closeTarget", {"targetId": tid})
    cdp.close()

    if failures:
        print("\nFAIL - hovering moves the word:")
        for f in failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - hovering a word repaints it without moving it")


if __name__ == "__main__":
    main()
