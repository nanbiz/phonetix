#!/usr/bin/env python3
"""Which language a word is asked for in, in the panel over the page.

The list is every language there is, and a reader asks in two or three of them. So it is a
list of the product's own rather than a strip of chips in alphabetical order: it can be
searched, the ones asked in lately are at the top of it, and what was picked last time is
still picked when the panel comes back.

  uv run python scripts/proofread/asks.py
"""
import base64
import base64
import http.server
import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

PORT = int(os.environ.get("PHONETIX_ASK_PORT", "8929"))
SHOTS = os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-ask")
SHOTS = os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-ask")
PAGE = (
    b"<!doctype html><html lang=en><meta charset=utf-8><title>Ask</title>"
    b"<body><p>Reading a paragraph teaches pronunciation quietly.</p></body></html>"
)

# What a reader types to find a language a long way down an alphabetical list, and what they
# find there.
TYPED = "pol"
FOUND = "pl"


def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def evaluate(cdp, session, expression):
    got = cdp.send("Runtime.evaluate", {
        "expression": expression, "awaitPromise": True, "returnByValue": True,
    }, session=session)
    return got.get("result", {}).get("value")


def wait_for(cdp, session, expression, want, tries=20):
    for _ in range(tries):
        got = evaluate(cdp, session, expression)
        if want(got):
            return got
        time.sleep(0.5)
    return got


# Everything this asks of the panel it asks through its shadow root, the way a reader reaches
# it: the panel is drawn in one so a page's stylesheet cannot reach it.
IN_PANEL = """
  (() => {
    const host = document.getElementById('phonetix-card-host-ask');
    const root = host && host.shadowRoot;
    if (!root) return null;
    return (%s)(root);
  })()
"""


def shot(cdp, session, name):
    """The panel as it is drawn, kept so there is something to look at: every check here is
    about the DOM, and how a row sits in a panel is not in the DOM."""
    os.makedirs(SHOTS, exist_ok=True)
    got = cdp.send("Page.captureScreenshot", {"format": "png"}, session=session)
    path = os.path.join(SHOTS, f"{name}.png")
    with open(path, "wb") as f:
        f.write(base64.b64decode(got["data"]))
    return path


def shot(cdp, session, name):
    """The panel as it is drawn, kept so there is something to look at: every check here asks
    the DOM, and how a row sits in a panel is not in the DOM."""
    os.makedirs(SHOTS, exist_ok=True)
    got = cdp.send("Page.captureScreenshot", {"format": "png"}, session=session)
    path = os.path.join(SHOTS, f"{name}.png")
    with open(path, "wb") as f:
        f.write(base64.b64decode(got["data"]))
    return path


def main():
    serve()
    base = f"http://127.0.0.1:{PORT}"
    failures = []

    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    try:
        book = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/viewbook.html"})
        settings = cdp.send(
            "Target.attachToTarget", {"targetId": book["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=settings)
        time.sleep(2)
        # Nothing remembered yet, so the panel opens on whatever it can work out.
        evaluate(cdp, settings, "chrome.storage.local.set({on:true,targetLanguage:'en'})")

        target = cdp.send("Target.createTarget", {"url": f"{base}/page.html"})
        page = cdp.send(
            "Target.attachToTarget", {"targetId": target["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=page)
        cdp.send("Page.enable", session=page)
        time.sleep(2)

        def open_panel():
            evaluate(cdp, settings, """
                chrome.tabs.query({}).then(tabs => {
                  const tab = tabs.find(t => (t.url || '').includes(':%d/'));
                  return chrome.tabs.sendMessage(tab.id, {phonetix:'askForAWord', data:{}});
                }).then(() => 'sent', e => String(e))
            """ % PORT)

        open_panel()
        drawn = wait_for(cdp, page, IN_PANEL % "(root) => !!root.querySelector('[data-ask]')",
                         lambda got: got is True)
        if drawn is not True:
            print("FAIL - the shortcut opened no panel")
            sys.exit(1)

        # A control that says which language, not fifty buttons: what a reader sees before
        # they touch anything is one name.
        shown = evaluate(cdp, page, IN_PANEL % """
            (root) => JSON.stringify({
              select: (root.querySelector('[data-row=say-into] .select') || {}).textContent || '',
              chips: root.querySelectorAll('.ask-langs .chip').length,
            })
        """)
        print(f"  the panel: {shot(cdp, page, 'panel')}")
        print(f"  the panel: {shot(cdp, page, 'panel')}")
        shown = json.loads(shown or "{}")
        if shown.get("chips"):
            failures.append(f"the panel still offers {shown['chips']} languages as chips")
        if not (shown.get("select") or "").strip():
            failures.append("the panel does not say which language it answers in")

        # Opened, searched, and picked - the three things the old strip could not do.
        evaluate(cdp, page, IN_PANEL % """
            (root) => root.querySelector('[data-row=say-into] .select').click()
        """)
        # Asked for after the click rather than in it: what the view draws in answer to a
        # press is drawn on the next frame, and a list counted inside the press is empty.
        listed = wait_for(cdp, page, IN_PANEL % """
            (root) => root.querySelectorAll('[data-sheet] .choice').length
        """, lambda got: bool(got))
        if not listed or listed < 20:
            failures.append(f"the list offers {listed} languages, not the ones there are")
        filtered = evaluate(cdp, page, IN_PANEL % ("""
            (root) => {
              const field = root.querySelector('[data-sheet] .field input');
              if (!field) return 'no field to search in';
              const set = Object.getOwnPropertyDescriptor(
                window.HTMLInputElement.prototype, 'value').set;
              set.call(field, %s);
              field.dispatchEvent(new Event('input', {bubbles: true}));
              return 'searched';
            }
        """ % json.dumps(TYPED)))
        if filtered != "searched":
            failures.append(f"the list cannot be searched: {filtered}")
        time.sleep(0.5)
        left = evaluate(cdp, page, IN_PANEL % """
            (root) => JSON.stringify(
              [...root.querySelectorAll('[data-sheet] .choice')].map(c => c.dataset.choice))
        """)
        left = json.loads(left or "[]")
        print(f"  searching {TYPED!r} leaves: {left}")
        if FOUND not in left:
            failures.append(f"searching {TYPED!r} does not find {FOUND}")
        if len(left) > 5:
            failures.append(f"searching {TYPED!r} leaves {len(left)} languages, which is no filter")

        picked = evaluate(cdp, page, IN_PANEL % ("""
            (root) => {
              const choice = root.querySelector('[data-sheet] [data-choice=%s]');
              if (!choice) return 'not offered';
              choice.click();
              return 'picked';
            }
        """ % FOUND))
        if picked != "picked":
            failures.append(f"the language could not be picked: {picked}")
        time.sleep(1)
        after = evaluate(cdp, page, IN_PANEL % """
            (root) => JSON.stringify({
              sheet: !!root.querySelector('[data-sheet]'),
              select: (root.querySelector('[data-row=say-into] .select') || {}).textContent || '',
            })
        """)
        after = json.loads(after or "{}")
        if after.get("sheet"):
            failures.append("the list stayed up after a language was picked")
        if "Polish" not in (after.get("select") or ""):
            failures.append(f"the panel says {after.get('select')!r} after Polish was picked")

        # Remembered, and offered first next time: that is the whole point of remembering it.
        kept = wait_for(
            cdp, settings,
            "chrome.storage.local.get(['learning','recentLanguages']).then(v => JSON.stringify(v))",
            lambda got: got and FOUND in got)
        print(f"  what was kept: {kept}")
        kept = json.loads(kept or "{}")
        if kept.get("learning") != FOUND:
            failures.append(f"the choice was not kept: {kept.get('learning')!r}")
        if (kept.get("recentLanguages") or [None])[0] != FOUND:
            failures.append(f"it is not at the top of the ones asked in lately: {kept}")

        # Closed and opened again, the way a reader comes back to it: the shortcut takes
        # down the panel it put up.
        open_panel()
        gone = wait_for(cdp, page, IN_PANEL % "(root) => !!root.querySelector('[data-ask]')",
                        lambda got: got is not True)
        if gone is True:
            failures.append("the shortcut would not take the panel down again")
        open_panel()
        back = wait_for(cdp, page, IN_PANEL % "(root) => !!root.querySelector('[data-ask]')",
                        lambda got: got is True)
        if back is not True:
            failures.append("the panel would not come back")
        evaluate(cdp, page, IN_PANEL % """
            (root) => {
              const select = root.querySelector('[data-row=say-into] .select');
              if (select) select.click();
            }
        """)
        again = wait_for(cdp, page, IN_PANEL % """
            (root) => {
              const select = root.querySelector('[data-row=say-into] .select');
              const first = root.querySelector('[data-sheet] .choice');
              if (!select || !first) return null;
              return JSON.stringify({
                says: select.textContent.trim(),
                first: first.dataset.choice,
              });
            }
        """, lambda got: bool(got))
        print(f"  coming back: {again}")
        print(f"  the list: {shot(cdp, page, 'list')}")
        again = json.loads(again or "{}")
        if "Polish" not in (again.get("says") or ""):
            failures.append(f"the panel came back saying {again.get('says')!r}")
        if again.get("first") != FOUND:
            failures.append(f"the list came back with {again.get('first')!r} at the top")
    finally:
        cdp.close()

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the panel picks a language from a list, searches it, and remembers it")


if __name__ == "__main__":
    main()
