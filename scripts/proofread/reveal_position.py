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
  // The revealed layer is the child lifted to position:absolute on hover; the shown
  // layer is the other, which still defines the word's box.
  const kids = [...sp.children];
  const rev = kids.find(c => getComputedStyle(c).position === 'absolute') || null;
  const shown = kids.find(c => c !== rev && getComputedStyle(c).display !== 'none') || sp;
  // Glyph boxes (Range), not layout boxes: a box centre stays put while the text
  // inside it shifts, so the box hid a 1px drop of the letters themselves.
  const gr = e => { const r = document.createRange(); r.selectNodeContents(e); return r.getBoundingClientRect(); };
  const w = gr(shown);
  const on = !!rev;
  const rg = on ? gr(rev) : null;         // revealed glyphs
  const wbox = shown.getBoundingClientRect();
  const rbox = on ? rev.getBoundingClientRect() : null;
  // The background the word actually sits on, walked up to the page.
  let pageBg = 'rgba(0, 0, 0, 0)';
  for (let e = sp; e; e = e.parentElement) {
    const bg = getComputedStyle(e).backgroundColor;
    if (bg && bg !== 'transparent' && !bg.startsWith('rgba(0, 0, 0, 0)')) { pageBg = bg; break; }
  }
  return JSON.stringify({
    x: wbox.left + wbox.width / 2, y: wbox.top + wbox.height / 2,
    // Where the word's own glyphs start (top-left), and where the reveal's start.
    wordTop: w.top, wordLeft: w.left,
    revealTop: rg ? rg.top : null, revealLeft: rg ? rg.left : null,
    // Boxes, to prove the reveal covers the whole word (no part peeks out).
    wordBoxLeft: wbox.left, wordBoxRight: wbox.right,
    revBoxLeft: rbox ? rbox.left : null, revBoxRight: rbox ? rbox.right : null,
    revealBg: rev ? getComputedStyle(rev).backgroundColor : null,
    pageBg,
    on, text: rev ? rev.textContent : null,
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
        # The reveal shares the word's own box, so its glyphs start on the word's:
        # top and left align exactly, not "within a pixel". A separate measured
        # overlay could only get near; this must be 0.
        dx = abs(after["revealLeft"] - after["wordLeft"])
        dy = abs(after["revealTop"] - after["wordTop"])
        # The word itself must not move when the reveal opens over it: its own glyphs
        # sit at the same place before and after hover, or the line jumps ("moves down").
        shift = abs(after["wordTop"] - info["wordTop"])
        # It must cover the whole word — no letter of the word peeks out past either
        # side of the reveal, even when the revealed form is the shorter of the two.
        peek_l = max(0.0, after["wordBoxLeft"] - after["revBoxLeft"])
        peek_r = max(0.0, after["wordBoxRight"] - after["revBoxRight"])
        bg_ok = after["revealBg"] == after["pageBg"]
        aligned = dx < 0.05 and dy < 0.05
        still = shift < 0.05
        covered = peek_l < 0.5 and peek_r < 0.5
        ok = aligned and still and covered and bg_ok
        notes = []
        if not bg_ok:
            notes.append(f"BG {after['revealBg']} != page {after['pageBg']}")
        if not still:
            notes.append(f"word moved {shift:.2f}px")
        if not covered:
            notes.append(f"word peeks out (l={peek_l:.1f} r={peek_r:.1f})")
        note = ("  " + "; ".join(notes)) if notes else ""
        print(f"  {'PASS' if ok else 'FAIL'}  {case:8} start off ({dx:.2f}, {dy:.2f})px shift {shift:.2f}px{note}")
        if not aligned:
            failures.append(f"{case}: reveal starts off the word by ({dx:.2f}, {dy:.2f})px")
        if not still:
            failures.append(f"{case}: the word moved {shift:.2f}px when the reveal opened")
        if not covered:
            failures.append(f"{case}: the word peeks out around the reveal (l={peek_l:.1f} r={peek_r:.1f})")
        if not bg_ok:
            failures.append(f"{case}: reveal background {after['revealBg']} does not match the page {after['pageBg']}")
        # Dismiss the tooltip and clear the hovered word before the next case: the
        # tooltip is instant now and would otherwise sit over the next word, so a
        # synthetic hover would land on it instead of the word.
        d.eval("(()=>{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));"
               "document.querySelectorAll('.phonetix').forEach(s=>s.dispatchEvent("
               "new MouseEvent('mouseout',{bubbles:true,composed:true,relatedTarget:document.body})));return 1;})()")
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
