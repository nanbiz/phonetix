#!/usr/bin/env python3
"""A reveal must never survive the content it belongs to.

Single-page apps (YouTube) swap the DOM when you navigate without moving the mouse,
so the hovered word never gets a mouseout. The reveal, an absolute layer over that
word, would stay painted over the new page's text — the last video's title showing
over the next one. This hovers a word, then replaces the DOM under a still cursor
(and, separately, moves the cursor away), and asserts no reveal is left behind, on
both engines.

  uv run python scripts/proofread/stale_reveal.py               # Chrome
  uv run --with marionette_driver python scripts/proofread/stale_reveal.py firefox
"""
import http.server
import json
import sys
import threading
import time

from browser import open_browser

# A small feed, close to how a video site nests titles in cards, so the swap below
# removes a real subtree rather than a bare paragraph.
PAGE = b"""<!doctype html><html lang=en><meta charset=utf-8><style>
  body{background:#fff;color:#111;margin:0;padding:20px;font:18px/1.6 system-ui,sans-serif}
  .card{padding:8px;border-bottom:1px solid #eee}
</style><body>
  <div id=feed>
    <div class=card><h3>The transparent knight thought through the night</h3></div>
    <div class=card><h3>Another transparent story about enormous things</h3></div>
  </div>
</body></html>"""

PORT = 8977

# How many elements are currently a reveal: the hovered word carries px-hover, and its
# hidden layer is the absolute child. After the swap there must be none.
COUNT_REVEALS = """(() => {
  const hovered = document.querySelectorAll('.px-hover').length;
  let painted = 0;
  document.querySelectorAll('.phonetix > *').forEach(c => {
    const s = getComputedStyle(c);
    if (s.position === 'absolute' && s.display !== 'none') painted++;
  });
  return JSON.stringify({hovered, painted});
})()"""

FIND = """(() => {
  const sp = [...document.querySelectorAll('.phonetix')].find(s => s.dataset.original === 'transparent');
  if (!sp) return JSON.stringify({error:'no span'});
  const b = sp.getBoundingClientRect();
  return JSON.stringify({cx: b.left+b.width/2, cy: b.top+b.height/2});
})()"""


def serve():
    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)

    httpd = http.server.HTTPServer(("127.0.0.1", PORT), H)
    httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def main():
    serve()
    kind = sys.argv[1] if len(sys.argv) > 1 else "chrome"
    print(f"stale reveal under {kind}:")

    d = open_browser(kind)
    d.navigate(f"http://127.0.0.1:{PORT}/")
    if not d.wait_spans():
        print("FAIL - never transformed the page")
        d.close()
        sys.exit(1)

    failures = []

    # 1. Hover a word, then swap the whole feed (a fresh subtree) without moving the
    #    cursor — exactly what an in-page navigation does.
    pos = json.loads(d.eval(FIND) or "{}")
    d.hover(pos["cx"], pos["cy"])
    on = json.loads(d.eval(COUNT_REVEALS))
    if not on["hovered"] or not on["painted"]:
        failures.append(f"reveal did not appear to begin with: {on}")
    # Replace the feed's contents (removes the hovered word), then let the observer run.
    d.eval("(()=>{document.getElementById('feed').innerHTML="
           "'<div class=card><h3>A different transparent headline entirely</h3></div>';return 1;})()")
    time.sleep(0.8)
    after = json.loads(d.eval(COUNT_REVEALS))
    ok1 = after["hovered"] == 0 and after["painted"] == 0
    print(f"  {'PASS' if ok1 else 'FAIL'}  DOM swapped under a still cursor -> reveals left: {after}")
    if not ok1:
        failures.append(f"a reveal survived the content swap: {after}")

    # 2. Hover the fresh word, then move the cursor to empty space: the reveal clears.
    if d.wait_spans():
        pos2 = json.loads(d.eval(FIND) or "{}")
        if not pos2.get("error"):
            d.hover(pos2["cx"], pos2["cy"])
            mid = json.loads(d.eval(COUNT_REVEALS))
            d.hover(3, 3)
            end = json.loads(d.eval(COUNT_REVEALS))
            ok2 = mid["painted"] and end["hovered"] == 0 and end["painted"] == 0
            print(f"  {'PASS' if ok2 else 'FAIL'}  cursor moved to empty space -> reveals left: {end}")
            if not ok2:
                failures.append(f"a reveal survived the cursor leaving: {end}")

    d.close()
    if failures:
        print(f"\nFAIL - {len(failures)} stale reveal(s):")
        for f in failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - no reveal outlives the word it belonged to")


if __name__ == "__main__":
    main()
