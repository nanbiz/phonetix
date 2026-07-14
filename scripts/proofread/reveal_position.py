#!/usr/bin/env python3
"""The word revealed on hover must land on the word, in every layout and browser.

This kept shipping broken because each test covered one narrow case (a single-line
paragraph in Chrome) while the bug showed up elsewhere: multi-line wrapping text, a
flex row, a truncated label, and above all on Firefox, where absolutely positioning
the hidden layer inside a multi-line inline element put it at the paragraph top. So
this hovers the same word in six layouts and checks the reveal lands on it, and runs
against either engine through the shared Driver — one test body, no per-browser copy.

  uv run python scripts/proofread/reveal_position.py            # Chrome
  uv run --with marionette_driver python scripts/proofread/reveal_position.py firefox
"""
import http.server
import json
import sys
import threading

from browser import open_browser

PAGE = b"""<!doctype html><html lang=en><meta charset=utf-8><style>
  body{background:#fff;color:#111;margin:0;padding:20px;font:16px/1.5 sans-serif}
  #wrap{width:260px;font-family:serif}
  #big{font-size:22px;line-height:3}
  #small{font-size:11px}
  .row{display:flex;align-items:center;gap:10px;width:300px}
  .lab{flex:1;min-width:0}
  .clip{flex:1;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
</style><body>
  <p id=single>The transparent word sits on one short line here.</p>
  <p id=wrap>The transparent quick brown fox jumps over the lazy dog while the
     transparent river runs quietly past the old transparent stone bridge today.</p>
  <p id=big>A transparent tall line of text here.</p>
  <p id=small>A transparent tiny line of text here.</p>
  <div class=row><span class=lab>flex transparent label here</span><span>x</span></div>
  <div class=row><span class=clip>clipped transparent label that overflows the box for sure now</span><span>y</span></div>
</body></html>"""

PORT = 8971
CASES = ["#single", "#wrap", "#big", "#small", ".lab", ".clip"]

PROBE = """(() => {
  const ctx = document.querySelector(%s);
  const sp = [...ctx.querySelectorAll('.phonetix')].find(s => s.dataset.original === 'transparent');
  if (!sp) return JSON.stringify({error: 'no transparent span'});
  const shown = [...sp.children].find(c => getComputedStyle(c).display !== 'none') || sp;
  const w = shown.getBoundingClientRect();
  const rev = document.querySelector('.px-reveal');
  const on = rev && getComputedStyle(rev).display !== 'none';
  const r = on ? rev.getBoundingClientRect() : null;
  return JSON.stringify({
    x: w.left + w.width / 2, y: w.top + w.height / 2,
    revealX: r ? r.left + r.width / 2 : null, revealY: r ? r.top + r.height / 2 : null,
    on: !!on, text: rev ? rev.textContent : null,
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


def main():
    serve()
    kind = sys.argv[1] if len(sys.argv) > 1 else "chrome"
    print(f"reveal position under {kind}:")

    # The reveal fires in either hover mode, and the default mode is one, so this
    # test sets nothing — it works the same whichever layer is the running text.
    d = open_browser(kind)
    d.navigate(f"http://127.0.0.1:{PORT}/")
    if not d.wait_spans():
        print("FAIL - the extension never transformed the page")
        d.close()
        sys.exit(1)

    failures = []
    for case in CASES:
        info = json.loads(d.eval(PROBE % json.dumps(case)) or "{}")
        if info.get("error"):
            failures.append(f"{case}: {info['error']}")
            continue
        d.hover(info["x"], info["y"])
        after = json.loads(d.eval(PROBE % json.dumps(case)) or "{}")
        if not after.get("on"):
            failures.append(f"{case}: no reveal appeared on hover")
            continue
        dx, dy = abs(after["revealX"] - after["x"]), abs(after["revealY"] - after["y"])
        ok = dx <= 2 and dy <= 2
        print(f"  {'PASS' if ok else 'FAIL'}  {case:8} off by ({dx:.0f}, {dy:.0f})px  [{after['text']}]")
        if not ok:
            failures.append(f"{case}: reveal off the word by ({dx:.0f}, {dy:.0f})px")
        d.hover(2, 2)

    d.close()
    if failures:
        print(f"\nFAIL - the reveal missed the word in {len(failures)} layout(s):")
        for f in failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the reveal lands on the word in every layout")


if __name__ == "__main__":
    main()
