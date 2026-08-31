#!/usr/bin/env python3
"""Turning the extension off must give the page back exactly as it was.

The trailing punctuation of a word is folded into its span and sliced off the following
text node, so it lives only on the span. If revert used the bare word, every comma,
period, colon and bracket vanished from the page when the extension was toggled off —
which is what happened after dragging the frequency slider and toggling on a real page.
This transcribes a punctuation-heavy paragraph, churns the settings the way dragging the
slider does, toggles the extension off, and asserts the page text is byte-identical to
the original.

  PHONETIX_CHROMIUM=<chrome> uv run python scripts/proofread/revert_fidelity.py
"""
import http.server
import sys
import threading
import time

from browser import open_browser

# Every kind of trailing mark the carry logic handles: comma, period, colon, semicolon,
# a closing bracket, an ellipsis, and an apostrophe inside a word.
TEXT = ("You're right, not the real popup box, so I missed. Also: a real bug "
        "(frequency) rises leftward; commas, colons: gone… really.")
PAGE = ("<!doctype html><html lang=en><meta charset=utf-8>"
        "<body style='font:18px sans-serif'><p id=p>" + TEXT + "</p></body></html>").encode()

PORT = 8987


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
    if kind == "firefox":
        print("revert fidelity: skipped on Firefox (toggling needs the signed popup)")
        return
    print("revert fidelity under", kind)
    d = open_browser(kind)
    d.navigate(f"http://127.0.0.1:{PORT}/")
    d.wait_spans()
    n = d.eval("document.querySelectorAll('.phonetix').length")

    # Drag the frequency slider (rapid density changes), swap to Full IPA, then off.
    for dens in ("4", "2", "8", "3", "30"):
        d.set_setting({"local:selectedMode": "sprinkle", "local:sprinkleDensity": dens})
    d.set_setting({"local:selectedMode": "showOriginalOnHover"})
    time.sleep(0.5)
    d.set_setting({"local:extension_enabled": "false"})
    time.sleep(1.5)

    reverted = (d.eval("document.getElementById('p').innerText") or "").strip()
    d.close()

    ok = reverted == TEXT
    print(f"  spans made: {n}")
    print(f"  {'PASS' if ok else 'FAIL'}  page text restored exactly after toggle-off")
    if not ok:
        print("   original:", repr(TEXT))
        print("   reverted:", repr(reverted))
        sys.exit(1)
    print("\nPASS - reverting gives the page back byte-for-byte")


if __name__ == "__main__":
    main()
