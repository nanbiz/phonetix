#!/usr/bin/env python3
"""The pictures in the README, taken from the extension that is actually built.

A screenshot is a claim about what the product looks like, and the ones in docs/ were taken
before the merge: they show a tooltip with rows the card no longer has. A stale screenshot on
a public repository is the first thing a stranger sees and the first thing that is wrong.

So these are shot from the real build, on a real page, through the same driver the checks use.

  uv run tools/shoot_hero.py

Writes docs/hero.png and docs/tooltip.png. What is on the page is text this repository wrote;
nothing of the machine it was taken on is in the frame.
"""
# /// script
# dependencies = ["pillow"]
# ///

import base64
import http.server
import json
import os
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "scripts", "proofread"))
from harness import PipeCDP  # noqa: E402

CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-hero")
PORT = int(os.environ.get("PHONETIX_HERO_PORT", "8931"))

# A sentence with words the fixture packs answer, so the picture shows the product working
# rather than the product failing to find anything.
SENTENCE = "El perro corre por el camino y descansa en el banco de la calle."

PAGE = ("""<!doctype html><html lang="es"><meta charset="utf-8"><body>
<style>
  body { margin: 0; background: #ffffff; font: 400 21px/2.1 Georgia, 'Times New Roman', serif;
         color: #1c1b19; }
  main { max-width: 46rem; margin: 0 auto; padding: 4.5rem 2rem; }
  h1 { font-size: 15px; letter-spacing: .18em; text-transform: uppercase; color: #8a8478;
       font-family: system-ui, sans-serif; font-weight: 600; margin: 0 0 2rem; }
  p { margin: 0 0 1.6rem; }
</style>
<main><h1>Lectura</h1>
<p id="prose">""" + SENTENCE + """</p>
<p>La calle del banco es estrecha y el camino sube despacio hacia el parque.</p>
</main></body></html>""").encode()


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900, **kw)


def build_packs():
    os.makedirs(WORK, exist_ok=True)
    for lang in ("es", "de"):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source,
                   os.path.join(WORK, f"{lang}.pack")], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[:300]}")


def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path.startswith("/packs/"):
                path = os.path.join(WORK, os.path.basename(self.path))
                if not os.path.exists(path):
                    self.send_error(404)
                    return
                body, kind = open(path, "rb").read(), "application/octet-stream"
            else:
                body, kind = PAGE, "text/html; charset=utf-8"
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def evaluate(cdp, session, expression, timeout=30):
    got = cdp.send("Runtime.evaluate", {
        "expression": expression, "awaitPromise": True, "returnByValue": True,
    }, session=session, timeout=timeout)
    return got.get("result", {}).get("value")


def remember(cdp, session, settings, seconds=30):
    """Write the reader's settings and wait until the browser has them.

    An extension page is a page: for the first moment after it is created there is no
    `chrome` in it, and a write sent then is lost with nothing said. So the write is tried
    until reading it back returns what was written, and it is the read-back rather than the
    write that decides the picture is being shot with the settings it claims.
    """
    wanted = json.dumps(settings)
    until = time.time() + seconds
    while time.time() < until:
        evaluate(cdp, session,
                 f"(async () => {{ await chrome.storage.local.set({wanted}); }})()")
        got = evaluate(cdp, session,
                       "chrome.storage.local.get(null).then(v=>JSON.stringify(v))")
        if got and all(json.loads(got).get(k) == v for k, v in settings.items()):
            return
        time.sleep(1)
    raise SystemExit("the settings never reached the browser")


def settle(cdp, session, seconds=40):
    """Wait until the page stops gaining annotations.

    Annotating is a walk over the text that answers in batches, and a batch takes as long as
    the engines behind it, so a screenshot on a timer catches whatever share happened to be
    done. What is wanted is the page a reader sees a moment later: the count no longer moving,
    and long enough after the last batch that the next one would have started.
    """
    seen, still = -1, 0
    until = time.time() + seconds
    while time.time() < until:
        count = evaluate(cdp, session, "document.querySelectorAll('.px-w').length") or 0
        still = still + 1 if count == seen and count else 0
        if still >= 8:
            return count
        seen = count
        time.sleep(1)
    return seen


def around(cdp, session, expression, margin=28):
    """A rectangle around what an expression selects, in CSS pixels, with room to breathe."""
    got = evaluate(cdp, session, """
        (() => {
          const parts = (%s).filter(Boolean).map(e => e.getBoundingClientRect());
          if (!parts.length) return null;
          const box = {
            left: Math.min(...parts.map(r => r.left)),
            top: Math.min(...parts.map(r => r.top)),
            right: Math.max(...parts.map(r => r.right)),
            bottom: Math.max(...parts.map(r => r.bottom)),
          };
          return JSON.stringify(box);
        })()
    """ % expression)
    if not got:
        return None
    box = json.loads(got)
    left, top = max(0, box["left"] - margin), max(0, box["top"] - margin)
    return {
        "x": left, "y": top,
        "width": box["right"] - left + margin, "height": box["bottom"] - top + margin,
        "scale": 3,
    }


def shoot(cdp, session, into, clip=None):
    # Cut to what is on the page rather than to the window: a picture with a third of it
    # empty is a picture a reader has to look past to see the thing being shown. The scale
    # lives in the clip, so the file is three times the pixels of what it frames.
    what = {"format": "png"}
    if clip:
        what["clip"] = clip
    got = cdp.send("Page.captureScreenshot", what, session=session)
    with open(into, "wb") as f:
        f.write(base64.b64decode(got["data"]))
    return into


def main():
    build_packs()
    serve()
    base = f"http://127.0.0.1:{PORT}"

    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    try:
        # The settings a reader would have: reading Spanish into German, every word annotated.
        book = cdp.send("Target.createTarget",
                        {"url": f"chrome-extension://{extid}/viewbook.html"})
        held = cdp.send("Target.attachToTarget",
                        {"targetId": book["targetId"], "flatten": True})["sessionId"]
        cdp.send("Runtime.enable", session=held)
        settings = {
            "packBaseUrl": base, "targetLanguage": "de", "selectedLanguage": "es",
            "on": True, "layer": "gloss+ipa", "density": 1,
        }
        remember(cdp, held, settings)
        for lang in ("es", "de"):
            got = evaluate(cdp, held,
                           "chrome.runtime.sendMessage({phonetix:'openPack',data:{lang:'"
                           + lang + "'}}).then(r=>JSON.stringify(r),e=>'ERR '+e)", timeout=90)
            if got in (None, "null", '{"ok":null}'):
                raise SystemExit(f"the {lang} pack did not open: {got}")

        page = cdp.send("Target.createTarget", {"url": base})
        session = cdp.send("Target.attachToTarget",
                           {"targetId": page["targetId"], "flatten": True})["sessionId"]
        cdp.send("Runtime.enable", session=session)
        cdp.send("Emulation.setDeviceMetricsOverride", {
            "width": 1180, "height": 620, "deviceScaleFactor": 1, "mobile": False,
        }, session=session)
        drawn = settle(cdp, session)
        if not drawn:
            raise SystemExit("nothing was annotated; the picture would show an empty page")
        print(f"  {drawn} words annotated")

        os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
        page_shot = shoot(cdp, session, os.path.join(ROOT, "docs", "hero.png"),
                          around(cdp, session, "[document.querySelector('main')]", 36))
        print(f"  {page_shot}")

        # And the card, opened on a word the way a reader opens one.
        where = evaluate(cdp, session, """
            (() => {
              const word = [...document.querySelectorAll('.px-w')]
                .find(w => w.textContent.includes('camino'));
              if (!word) return null;
              const r = word.getBoundingClientRect();
              return JSON.stringify({x: r.left + r.width / 2, y: r.top + r.height / 2});
            })()
        """)
        if where:
            at = json.loads(where)
            opened = False
            for step in range(12):
                cdp.send("Input.dispatchMouseEvent", {
                    "type": "mouseMoved", "x": at["x"] + step % 2, "y": at["y"] + step % 2,
                    "buttons": 0,
                }, session=session)
                time.sleep(1)
                opened = evaluate(cdp, session, """
                    (() => { const h = document.getElementById('phonetix-card-host');
                      return !!(h && h.shadowRoot
                                && h.shadowRoot.textContent.trim().length > 2); })()
                """)
                if opened:
                    break
            # The card fades in, and a picture of it half there is a picture of nothing.
            time.sleep(1.5)
            if opened:
                # The card, with the line it was opened from above it, so the picture shows
                # what a reader did rather than a panel floating on its own.
                card = shoot(cdp, session, os.path.join(ROOT, "docs", "tooltip.png"),
                             # The card is inside the host's shadow root; the host itself is a
                             # zero-sized anchor and measuring it frames nothing.
                             around(cdp, session,
                                    "[document.getElementById('phonetix-card-host')"
                                    "?.shadowRoot?.querySelector('.card'),"
                                    " document.getElementById('prose')]", 32))
                print(f"  {card}")
            else:
                print("  the card did not open; docs/tooltip.png left as it was")
    finally:
        cdp.close()
    print("\nthe pictures are from the build in .output")
    return 0


if __name__ == "__main__":
    sys.exit(main())
