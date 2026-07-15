#!/usr/bin/env python3
"""Reproduce the hover-reveal bugs the user sees, with a real screenshot.

Two failures reported from real use:
  - the reveal box lands below the word, not on it, and opens only sometimes;
  - when the replacement (e.g. a short IPA) is narrower than the word, the ends
    of the original word stick out around the box.

This hovers words whose IPA is clearly shorter than the spelling ("thought",
"knight", "through"), measures the reveal box against the word box, and writes a
screenshot so the result can be looked at, not guessed.

  uv run --with marionette_driver python scripts/proofread/repro_reveal.py firefox
  uv run python scripts/proofread/repro_reveal.py            # Chrome
"""
import http.server
import json
import sys
import threading

from browser import open_browser

PAGE = b"""<!doctype html><html lang=en><meta charset=utf-8><style>
  body{background:#fff;color:#111;margin:0;padding:30px;font:20px/1.6 Georgia,serif}
</style><body>
  <p id=p>I thought the knight rode through the night with enormous thoroughness.</p>
</body></html>"""

PORT = 8973
WORDS = ["thought", "knight", "through", "enormous"]

PROBE = """(() => {
  const sp = [...document.querySelectorAll('.phonetix')].find(s => s.dataset.original === %s);
  if (!sp) return JSON.stringify({error:'no span'});
  const kids = [...sp.children];
  const rev = kids.find(c => getComputedStyle(c).position === 'absolute') || null;
  const shown = kids.find(c => c !== rev && getComputedStyle(c).display !== 'none') || sp;
  const wb = shown.getBoundingClientRect();
  const rb = rev ? rev.getBoundingClientRect() : null;
  return JSON.stringify({
    word: sp.dataset.original, ipa: sp.dataset.ipa,
    wordLeft: wb.left, wordRight: wb.right, wordTop: wb.top, wordW: wb.width,
    revLeft: rb?rb.left:null, revRight: rb?rb.right:null, revTop: rb?rb.top:null, revW: rb?rb.width:null,
    on: !!rev, revText: rev?rev.textContent:null,
    cx: wb.left+wb.width/2, cy: wb.top+wb.height/2,
  });
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


def shot(d, path):
    """Save a screenshot from whichever driver this is."""
    if hasattr(d, "client"):                     # Firefox / Marionette
        png = d.client.screenshot(format="binary", full=False)
        open(path, "wb").write(png)
    else:                                        # Chrome / CDP
        r = d.cdp.send("Page.captureScreenshot", {}, session=d.s)
        import base64
        open(path, "wb").write(base64.b64decode(r["data"]))


def main():
    serve()
    kind = sys.argv[1] if len(sys.argv) > 1 else "chrome"
    print(f"repro reveal under {kind}:")

    d = open_browser(kind)
    # The default mode shows the IPA as the running text and reveals the original word
    # on hover — and here the original is shorter than the IPA, the case where the word
    # used to peek out around a too-narrow reveal.
    d.navigate(f"http://127.0.0.1:{PORT}/")
    if not d.wait_spans():
        print("FAIL - never transformed the page")
        d.close()
        sys.exit(1)

    for w in WORDS:
        before = json.loads(d.eval(PROBE % json.dumps(w)) or "{}")
        if before.get("error"):
            print(f"  {w}: {before['error']}")
            continue
        d.hover(before["cx"], before["cy"])
        a = json.loads(d.eval(PROBE % json.dumps(w)) or "{}")
        if not a.get("on"):
            print(f"  {w:9} NO REVEAL opened")
            continue
        peek_l = a["wordLeft"] < a["revLeft"] - 0.5
        peek_r = a["wordRight"] > a["revRight"] + 0.5
        dy = a["revTop"] - a["wordTop"]
        covers = "" if not (peek_l or peek_r) else f"  PEEK left={peek_l} right={peek_r}"
        print(f"  {w:9} ipa={a['ipa']!r} wordW={a['wordW']:.0f} revW={a['revW']:.0f} "
              f"topΔ={dy:+.1f}{covers}")
        d.hover(2, 2)

    # A screenshot of one hovered short word, to look at.
    tgt = json.loads(d.eval(PROBE % json.dumps("thought")) or "{}")
    d.hover(tgt["cx"], tgt["cy"])
    out = f"/tmp/reveal_{kind}.png"
    shot(d, out)
    print(f"\nscreenshot: {out}")
    d.close()


if __name__ == "__main__":
    main()
