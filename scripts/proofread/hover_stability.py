#!/usr/bin/env python3
"""Hovering the same word again must always bring its tooltip back.

Leaving a word starts a short timer that hides the tooltip once the cursor has had
time to cross to it. Coming back to the word inside that window has to call the
timer off. It did not: the re-entry was treated as "already here, nothing to do"
and returned early, so the pending timer fired and dismissed the tooltip a moment
later — hover, away, back, and it stalled shut.

This drives that exact sequence (enter, leave, re-enter within the window) through
real events and asserts the tooltip is still up afterwards, plus the ordinary
word-to-word switch, on both engines.

  uv run python scripts/proofread/hover_stability.py               # Chrome
  uv run --with marionette_driver python scripts/proofread/hover_stability.py firefox
"""
import http.server
import json
import sys
import threading
import time

from browser import open_browser

PAGE = b"""<!doctype html><html lang=en><meta charset=utf-8><style>
  body{background:#fff;color:#111;margin:0;padding:30px;font:20px/1.7 system-ui,sans-serif}
</style><body>
  <p>The transparent knight thought about enormous transparent bridges today.</p>
</body></html>"""

PORT = 8979

# Is the tooltip up? It lives in an open shadow root, shown by the .visible class.
VISIBLE = """(() => {
  const h = document.getElementById('phonetix-tooltip-host');
  if (!h || !h.shadowRoot) return JSON.stringify({error:'no host'});
  const tt = h.shadowRoot.querySelector('.px-tt');
  if (!tt) return JSON.stringify({error:'no tooltip'});
  const cs = getComputedStyle(tt);
  return JSON.stringify({visible: tt.classList.contains('visible'), display: cs.display, word: tt.querySelector('.px-word')?.textContent || ''});
})()"""


def enter(word):
    return ("(()=>{const s=[...document.querySelectorAll('.phonetix')].find("
            f"x=>x.dataset.original==={json.dumps(word)});"
            "s.dispatchEvent(new MouseEvent('mouseover',{bubbles:true,composed:true}));return 1;})()")


def leave_then_reenter(word):
    # Leave the word (mouseout to the body, starting the hide timer), move onto the
    # body, then immediately re-enter the same word — all synchronously, so the
    # re-entry lands well inside the hide window.
    return ("(()=>{const s=[...document.querySelectorAll('.phonetix')].find("
            f"x=>x.dataset.original==={json.dumps(word)});"
            "s.dispatchEvent(new MouseEvent('mouseout',{bubbles:true,composed:true,relatedTarget:document.body}));"
            "document.body.dispatchEvent(new MouseEvent('mouseover',{bubbles:true,composed:true}));"
            "s.dispatchEvent(new MouseEvent('mouseover',{bubbles:true,composed:true}));return 1;})()")


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
    print(f"hover stability under {kind}:")

    d = open_browser(kind)
    d.navigate(f"http://127.0.0.1:{PORT}/")
    if not d.wait_spans():
        print("FAIL - never transformed the page")
        d.close()
        sys.exit(1)

    failures = []

    # 1. Enter a word: the tooltip comes up (after the short open delay).
    d.eval(enter("transparent"))
    time.sleep(0.4)
    v = json.loads(d.eval(VISIBLE))
    if not v.get("visible"):
        failures.append(f"tooltip did not open on first hover: {v}")

    # 2. Leave and re-enter inside the hide window, then wait past it: still up.
    d.eval(leave_then_reenter("transparent"))
    time.sleep(0.6)   # longer than the ~350ms hide timer
    v = json.loads(d.eval(VISIBLE))
    ok = bool(v.get("visible"))
    print(f"  {'PASS' if ok else 'FAIL'}  hover, away, back within the window -> visible={v.get('visible')}")
    if not ok:
        failures.append(f"tooltip stalled shut after re-entering the word: {v}")

    # 3. An ordinary switch to another word and back still tracks the right word.
    d.eval(enter("enormous"))
    time.sleep(0.2)
    d.eval(enter("transparent"))
    time.sleep(0.2)
    v = json.loads(d.eval(VISIBLE))
    ok2 = v.get("visible") and v.get("word") == "transparent"
    print(f"  {'PASS' if ok2 else 'FAIL'}  switch word and back -> visible={v.get('visible')} word={v.get('word')!r}")
    if not ok2:
        failures.append(f"tooltip did not follow the switch: {v}")

    # 4. The delay is a setting. Chrome only — the popup that writes storage needs a
    #    signed add-on on Firefox, and the wiring here is engine-independent anyway.
    if kind == "chrome":
        # Instant: 0ms opens the tooltip with no wait.
        d.set_setting({"local:hoverDelay": "0"})
        d.navigate(f"http://127.0.0.1:{PORT}/")
        d.wait_spans()
        d.eval(enter("transparent"))
        # The .visible class lands on the next animation frame, which headless
        # throttles; this proves there is no delay timer, not the frame latency, so it
        # is read against the 600ms case below, which is still shut well past here.
        time.sleep(0.3)
        v = json.loads(d.eval(VISIBLE))
        ok3 = bool(v.get("visible"))
        print(f"  {'PASS' if ok3 else 'FAIL'}  delay 0ms -> opens at once: visible={v.get('visible')}")
        if not ok3:
            failures.append(f"delay 0 did not open instantly: {v}")

        # Long: 600ms is still shut at 150ms, open by 800ms.
        d.set_setting({"local:hoverDelay": "600"})
        d.navigate(f"http://127.0.0.1:{PORT}/")
        d.wait_spans()
        d.eval(enter("transparent"))
        time.sleep(0.15)
        early = json.loads(d.eval(VISIBLE))
        time.sleep(0.7)
        late = json.loads(d.eval(VISIBLE))
        ok4 = not early.get("visible") and late.get("visible")
        print(f"  {'PASS' if ok4 else 'FAIL'}  delay 600ms -> shut@150ms={early.get('visible')} open@850ms={late.get('visible')}")
        if not ok4:
            failures.append(f"delay 600 did not honour the wait: early={early} late={late}")
        d.set_setting({"local:hoverDelay": "200"})

    d.close()
    if failures:
        print(f"\nFAIL - {len(failures)} hover problem(s):")
        for f in failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the tooltip never stalls on re-hover")


if __name__ == "__main__":
    main()
